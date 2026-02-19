<script>
// Variation 3: Modern ES Modules / Service-Oriented Style
// This style uses a modular, functional approach with clear separation of concerns into services.
// We simulate modules using object literals.

const crypto = require('crypto');

// --- Constants & Mock DB ---
const config = {
    jwtSecret: 'es-module-style-super-secret',
    salt: 'a-random-salt-for-scrypt',
    roles: { ADMIN: 'ADMIN', USER: 'USER' },
};

const database = {
    users: new Map(),
    posts: new Map(),
    sessions: new Map(),
};

// --- Module: services/password.js ---
const passwordService = {
    hash: (password) => {
        return new Promise((resolve, reject) => {
            crypto.scrypt(password, config.salt, 64, (err, key) => {
                if (err) reject(err);
                resolve(key.toString('hex'));
            });
        });
    },
    compare: async (password, hash) => {
        const newHash = await passwordService.hash(password);
        return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(newHash, 'hex'));
    },
};

// --- Module: services/jwt.js ---
const jwtService = {
    _base64url: {
        encode: (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url'),
        decode: (str) => JSON.parse(Buffer.from(str, 'base64url').toString('utf8')),
    },
    sign: (payload, secret, expiresInSeconds = 3600) => {
        const header = { alg: 'HS256', typ: 'JWT' };
        const fullPayload = {
            ...payload,
            iat: Math.floor(Date.now() / 1000),
            exp: Math.floor(Date.now() / 1000) + expiresInSeconds,
        };
        const unsignedToken = `${jwtService._base64url.encode(header)}.${jwtService._base64url.encode(fullPayload)}`;
        const signature = crypto.createHmac('sha256', secret).update(unsignedToken).digest('base64url');
        return `${unsignedToken}.${signature}`;
    },
    verify: (token, secret) => {
        const [header, payload, signature] = token.split('.');
        if (!signature) return { error: 'Invalid token format' };

        const expectedSignature = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest('base64url');
        if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) {
            return { error: 'Invalid signature' };
        }

        const decodedPayload = jwtService._base64url.decode(payload);
        if (decodedPayload.exp * 1000 < Date.now()) {
            return { error: 'Token expired' };
        }
        return { payload: decodedPayload };
    },
};

// --- Module: services/auth.js ---
const authService = {
    login: async (email, password) => {
        const user = Array.from(database.users.values()).find(u => u.email === email);
        if (!user || !user.is_active) throw new Error('Invalid credentials');

        const passwordMatch = await passwordService.compare(password, user.password_hash);
        if (!passwordMatch) throw new Error('Invalid credentials');

        const token = jwtService.sign({ sub: user.id, role: user.role }, config.jwtSecret);
        const sessionId = crypto.randomUUID();
        database.sessions.set(sessionId, { userId: user.id });

        return { token, sessionId };
    },
    processOAuth2: (oauthProfile) => {
        let user = Array.from(database.users.values()).find(u => u.email === oauthProfile.email);
        if (!user) {
            const id = crypto.randomUUID();
            user = {
                id,
                email: oauthProfile.email,
                password_hash: `oauth|${oauthProfile.provider}`,
                role: config.roles.USER,
                is_active: true,
                created_at: new Date().toISOString(),
            };
            database.users.set(id, user);
        }
        const token = jwtService.sign({ sub: user.id, role: user.role }, config.jwtSecret);
        return { token };
    }
};

// --- Module: middleware/security.js ---
const securityMiddleware = {
    // Middleware to protect routes
    protect: (req) => {
        const token = req.headers?.authorization?.split(' ')[1];
        if (!token) {
            return { success: false, error: 'Authentication required' };
        }
        const { payload, error } = jwtService.verify(token, config.jwtSecret);
        if (error) {
            return { success: false, error };
        }
        const user = database.users.get(payload.sub);
        if (!user || !user.is_active) {
            return { success: false, error: 'User not found or disabled' };
        }
        req.user = user; // Attach user to the request object
        return { success: true };
    },
    // Higher-order function for RBAC
    restrictTo: (...allowedRoles) => {
        return (req) => {
            if (!req.user || !allowedRoles.includes(req.user.role)) {
                return { success: false, error: 'Forbidden' };
            }
            return { success: true };
        };
    },
};

// --- Main Execution Logic (Demonstration) ---
const main = async () => {
    console.log('--- Variation 3: Modern ES Modules/Service-Oriented Demo ---');

    // 1. Setup
    const adminId = crypto.randomUUID();
    database.users.set(adminId, {
        id: adminId,
        email: 'admin@service.com',
        password_hash: await passwordService.hash('pass-admin'),
        role: config.roles.ADMIN,
        is_active: true,
        created_at: new Date().toISOString(),
    });
    const userId = crypto.randomUUID();
    database.users.set(userId, {
        id: userId,
        email: 'user@service.com',
        password_hash: await passwordService.hash('pass-user'),
        role: config.roles.USER,
        is_active: true,
        created_at: new Date().toISOString(),
    });

    // 2. Login
    console.log('\nLogging in user...');
    try {
        const { token } = await authService.login('user@service.com', 'pass-user');
        console.log('User login successful.');

        // 3. Simulate a protected API call for a regular user
        console.log('\nUser accessing a general protected route...');
        const req = { headers: { authorization: `Bearer ${token}` } };

        let result = securityMiddleware.protect(req);
        if (!result.success) {
            console.error('Protection middleware failed:', result.error);
            return;
        }
        console.log(`User ${req.user.email} authenticated.`);

        // 4. RBAC check: User trying to access an admin route
        console.log('User trying to access an admin-only route...');
        const adminOnly = securityMiddleware.restrictTo(config.roles.ADMIN);
        result = adminOnly(req);
        console.log(`Authorization result: ${result.success ? 'Allowed' : 'Denied'} (Reason: ${result.error})`);

        // 5. RBAC check: User accessing a route for users
        console.log('User trying to access a user-accessible route...');
        const userOrAdmin = securityMiddleware.restrictTo(config.roles.ADMIN, config.roles.USER);
        result = userOrAdmin(req);
        console.log(`Authorization result: ${result.success ? 'Allowed' : 'Denied'}`);

    } catch (e) {
        console.error('An error occurred:', e.message);
    }
    
    // 6. OAuth2 Flow
    console.log('\nProcessing OAuth2 login for a new user...');
    const oauthProfile = { email: 'oauth.user@example.com', provider: 'google' };
    const { token: oauthToken } = authService.processOAuth2(oauthProfile);
    const { payload } = jwtService.verify(oauthToken, config.jwtSecret);
    console.log(`OAuth user provisioned with ID: ${payload.sub}`);
};

main();
</script>