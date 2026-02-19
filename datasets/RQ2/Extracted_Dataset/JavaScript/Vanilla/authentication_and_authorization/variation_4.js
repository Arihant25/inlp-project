<script>
// Variation 4: Minimalist / Utility-First Style
// This style favors small, composable, and often pure utility functions.
// It avoids classes and complex structures for a more direct, functional approach.

const crypto = require('crypto');

// --- Configuration ---
const CONFIG = {
    JWT_SECRET: 'minimalist-and-functional-secret',
    SCRYPT_PARAMS: { salt: 'utility-salt', keylen: 64 },
    ROLES: { ADMIN: 'ADMIN', USER: 'USER' },
    TOKEN_EXPIRY_SECONDS: 3600,
};

// --- Mock Data Store ---
const DATA = {
    users: new Map(),
    posts: new Map(),
    sessions: new Map(),
};

// --- Crypto Utilities ---
const hash = (str) => new Promise((res, rej) =>
    crypto.scrypt(str, CONFIG.SCRYPT_PARAMS.salt, CONFIG.SCRYPT_PARAMS.keylen, (err, key) =>
        err ? rej(err) : res(key.toString('hex'))
    )
);

const compareHash = async (str, existingHash) => {
    const newHash = await hash(str);
    return crypto.timingSafeEqual(Buffer.from(existingHash, 'hex'), Buffer.from(newHash, 'hex'));
};

// --- JWT Utilities ---
const toBase64Url = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
const fromBase64Url = (str) => JSON.parse(Buffer.from(str, 'base64url').toString('utf8'));

const createSignature = (data, secret) => crypto.createHmac('sha256', secret).update(data).digest('base64url');

const createToken = (payload) => {
    const header = { alg: 'HS256', typ: 'JWT' };
    const fullPayload = {
        ...payload,
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + CONFIG.TOKEN_EXPIRY_SECONDS,
    };
    const unsigned = `${toBase64Url(header)}.${toBase64Url(fullPayload)}`;
    const signature = createSignature(unsigned, CONFIG.JWT_SECRET);
    return `${unsigned}.${signature}`;
};

const verifyToken = (token) => {
    try {
        const [header, payload, signature] = token.split('.');
        const expectedSignature = createSignature(`${header}.${payload}`, CONFIG.JWT_SECRET);
        if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) {
            return { ok: false, error: 'Invalid signature' };
        }
        const decoded = fromBase64Url(payload);
        if (decoded.exp * 1000 < Date.now()) {
            return { ok: false, error: 'Token expired' };
        }
        return { ok: true, payload: decoded };
    } catch (e) {
        return { ok: false, error: 'Malformed token' };
    }
};

// --- Auth Logic Utilities ---
const findUserByEmail = (email) => Array.from(DATA.users.values()).find(u => u.email === email);

const attemptLogin = async (email, password) => {
    const user = findUserByEmail(email);
    if (!user || !user.is_active || !(await compareHash(password, user.password_hash))) {
        return null;
    }
    return user;
};

const createSession = (userId) => {
    const sessionId = crypto.randomUUID();
    DATA.sessions.set(sessionId, { userId });
    return sessionId;
};

const processOauthLogin = (profile) => {
    let user = findUserByEmail(profile.email);
    if (!user) {
        const id = crypto.randomUUID();
        user = {
            id,
            email: profile.email,
            password_hash: `oauth|${profile.provider}`,
            role: CONFIG.ROLES.USER,
            is_active: true,
            created_at: new Date().toISOString(),
        };
        DATA.users.set(id, user);
    }
    return createToken({ sub: user.id, role: user.role });
};

// --- RBAC Utility ---
const isAllowed = (user, allowedRoles) => user && allowedRoles.includes(user.role);

// --- Request Handling Simulation ---
const getUserFromRequest = (req) => {
    const token = req.headers?.authorization?.split(' ')[1];
    if (!token) return null;
    const result = verifyToken(token);
    return result.ok ? DATA.users.get(result.payload.sub) : null;
};

// --- Main Execution Logic (Demonstration) ---
(async () => {
    console.log('--- Variation 4: Minimalist/Utility-First Demo ---');

    // 1. Setup
    const adminId = crypto.randomUUID();
    DATA.users.set(adminId, {
        id: adminId, email: 'admin@util.com', password_hash: await hash('adminpass'),
        role: CONFIG.ROLES.ADMIN, is_active: true, created_at: new Date().toISOString()
    });
    const userId = crypto.randomUUID();
    DATA.users.set(userId, {
        id: userId, email: 'user@util.com', password_hash: await hash('userpass'),
        role: CONFIG.ROLES.USER, is_active: true, created_at: new Date().toISOString()
    });

    // 2. Login Flow
    console.log('\nAdmin login...');
    const adminUser = await attemptLogin('admin@util.com', 'adminpass');
    if (adminUser) {
        const token = createToken({ sub: adminUser.id, role: adminUser.role });
        const sessionId = createSession(adminUser.id);
        console.log(`Login success. Session: ${sessionId}`);

        // 3. API Endpoint Simulation with RBAC
        console.log('\nSimulating API call to admin dashboard...');
        const mockRequest = { headers: { authorization: `Bearer ${token}` } };
        const currentUser = getUserFromRequest(mockRequest);

        if (currentUser) {
            console.log(`Authenticated as: ${currentUser.email}`);
            // Direct RBAC check inside the "controller" logic
            if (isAllowed(currentUser, [CONFIG.ROLES.ADMIN])) {
                console.log('Access Granted: Welcome to the admin dashboard.');
            } else {
                console.log('Access Denied: You are not an admin.');
            }
        } else {
            console.log('Authentication Failed.');
        }
    } else {
        console.log('Login failed.');
    }
    
    // 4. Failed RBAC check
    console.log('\nSimulating user trying to access admin dashboard...');
    const normalUser = await attemptLogin('user@util.com', 'userpass');
    const userToken = createToken({ sub: normalUser.id, role: normalUser.role });
    const userRequest = { headers: { authorization: `Bearer ${userToken}` } };
    const reqUser = getUserFromRequest(userRequest);
    if (reqUser && !isAllowed(reqUser, [CONFIG.ROLES.ADMIN])) {
        console.log('Access correctly denied for non-admin user.');
    }

    // 5. OAuth2 Flow
    console.log('\nSimulating OAuth2 login...');
    const oauthToken = processOauthLogin({ email: 'git.user@example.com', provider: 'github' });
    const verification = verifyToken(oauthToken);
    if (verification.ok) {
        console.log(`OAuth user successfully created and logged in. Role: ${verification.payload.role}`);
    }

})();
</script>