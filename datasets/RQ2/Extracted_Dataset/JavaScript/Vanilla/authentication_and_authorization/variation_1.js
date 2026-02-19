<script>
// Variation 1: Procedural / Functional Style
// This style uses standalone functions grouped by functionality, common in older codebases or simpler scripts.

const crypto = require('crypto');

// --- Configuration & Constants ---
const JWT_SECRET = 'a-secure-secret-key-for-hs256';
const PASSWORD_SALT = 'a-fixed-salt-for-demo'; // In production, use a unique salt per user
const ROLES = { ADMIN: 'ADMIN', USER: 'USER' };
const POST_STATUS = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' };

// --- Mock Database ---
const db = {
    users: new Map(),
    posts: new Map(),
};

// --- In-Memory Session Store ---
const sessionStore = new Map();

// --- Utility Functions ---
function base64urlEncode(data) {
    return Buffer.from(JSON.stringify(data))
        .toString('base64')
        .replace(/=/g, '')
        .replace(/\+/g, '-')
        .replace(/\//g, '_');
}

function base64urlDecode(str) {
    str = str.replace(/-/g, '+').replace(/_/g, '/');
    while (str.length % 4) {
        str += '=';
    }
    return JSON.parse(Buffer.from(str, 'base64').toString());
}

async function hashPassword(password) {
    return new Promise((resolve, reject) => {
        crypto.scrypt(password, PASSWORD_SALT, 64, (err, derivedKey) => {
            if (err) reject(err);
            resolve(derivedKey.toString('hex'));
        });
    });
}

async function verifyPassword(password, hash) {
    const newHash = await hashPassword(password);
    return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(newHash, 'hex'));
}

// --- JWT Functions ---
function generateJwtToken(user) {
    const header = { alg: 'HS256', typ: 'JWT' };
    const payload = {
        sub: user.id,
        role: user.role,
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + (60 * 60), // Expires in 1 hour
    };

    const encodedHeader = base64urlEncode(header);
    const encodedPayload = base64urlEncode(payload);
    const signatureInput = `${encodedHeader}.${encodedPayload}`;

    const signature = crypto
        .createHmac('sha256', JWT_SECRET)
        .update(signatureInput)
        .digest('base64url');

    return `${signatureInput}.${signature}`;
}

function verifyJwtToken(token) {
    try {
        const [encodedHeader, encodedPayload, signature] = token.split('.');
        if (!encodedHeader || !encodedPayload || !signature) {
            throw new Error('Invalid token format');
        }

        const signatureInput = `${encodedHeader}.${encodedPayload}`;
        const expectedSignature = crypto
            .createHmac('sha256', JWT_SECRET)
            .update(signatureInput)
            .digest('base64url');

        if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) {
            throw new Error('Invalid signature');
        }

        const payload = base64urlDecode(encodedPayload);
        if (payload.exp * 1000 < Date.now()) {
            throw new Error('Token expired');
        }

        return { valid: true, payload };
    } catch (error) {
        return { valid: false, error: error.message };
    }
}

// --- Authentication & Authorization Functions ---
async function loginUser(email, password) {
    const user = Array.from(db.users.values()).find(u => u.email === email);
    if (!user || !user.is_active) {
        return { success: false, message: 'Authentication failed' };
    }

    const isPasswordValid = await verifyPassword(password, user.password_hash);
    if (!isPasswordValid) {
        return { success: false, message: 'Authentication failed' };
    }

    const token = generateJwtToken(user);
    const sessionId = crypto.randomUUID();
    sessionStore.set(sessionId, { userId: user.id, createdAt: new Date() });

    return { success: true, token, sessionId };
}

// Middleware-style functions
function authenticateRequest(request) {
    const authHeader = request.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return { authenticated: false, error: 'Missing or malformed token' };
    }
    const token = authHeader.split(' ')[1];
    const verificationResult = verifyJwtToken(token);

    if (!verificationResult.valid) {
        return { authenticated: false, error: verificationResult.error };
    }

    const user = db.users.get(verificationResult.payload.sub);
    if (!user || !user.is_active) {
        return { authenticated: false, error: 'User not found or inactive' };
    }

    request.user = user; // Attach user to request
    return { authenticated: true, user };
}

function authorizeRequest(request, allowedRoles) {
    if (!request.user || !allowedRoles.includes(request.user.role)) {
        return { authorized: false, error: 'Forbidden' };
    }
    return { authorized: true };
}

// --- OAuth2 Client Mock ---
function handleOAuth2Callback(oauthProviderData) {
    // In a real app, you'd exchange a code for a token, then fetch user info.
    const { email, providerId } = oauthProviderData;
    let user = Array.from(db.users.values()).find(u => u.email === email);

    if (!user) {
        // Provision a new user if they don't exist
        const newUserId = crypto.randomUUID();
        user = {
            id: newUserId,
            email: email,
            password_hash: `oauth-${providerId}`, // Not a real password
            role: ROLES.USER,
            is_active: true,
            created_at: new Date().toISOString(),
        };
        db.users.set(newUserId, user);
    }

    const token = generateJwtToken(user);
    return { success: true, token };
}


// --- Main Execution Logic (Demonstration) ---
(async () => {
    console.log('--- Variation 1: Procedural/Functional Demo ---');

    // 1. Setup: Create users
    const adminId = crypto.randomUUID();
    const userId = crypto.randomUUID();
    db.users.set(adminId, {
        id: adminId,
        email: 'admin@example.com',
        password_hash: await hashPassword('admin123'),
        role: ROLES.ADMIN,
        is_active: true,
        created_at: new Date().toISOString(),
    });
    db.users.set(userId, {
        id: userId,
        email: 'user@example.com',
        password_hash: await hashPassword('user123'),
        role: ROLES.USER,
        is_active: true,
        created_at: new Date().toISOString(),
    });

    // 2. Login
    console.log('\nLogging in admin...');
    const loginResult = await loginUser('admin@example.com', 'admin123');
    if (!loginResult.success) {
        console.error('Login failed:', loginResult.message);
        return;
    }
    console.log('Admin login successful. Session ID:', loginResult.sessionId);
    const adminToken = loginResult.token;

    // 3. Authenticate and Authorize a request
    console.log('\nSimulating an admin request to a protected route...');
    const mockAdminRequest = { headers: { authorization: `Bearer ${adminToken}` } };

    const authResult = authenticateRequest(mockAdminRequest);
    if (!authResult.authenticated) {
        console.error('Authentication failed:', authResult.error);
        return;
    }
    console.log('Authentication successful for user:', authResult.user.email);

    const rbacResult = authorizeRequest(mockAdminRequest, [ROLES.ADMIN]);
    if (!rbacResult.authorized) {
        console.error('Authorization failed:', rbacResult.error);
        return;
    }
    console.log('Authorization successful. Admin can access the resource.');

    // 4. Simulate a failed authorization
    console.log('\nSimulating a user request to an admin-only route...');
    const userLoginResult = await loginUser('user@example.com', 'user123');
    const userToken = userLoginResult.token;
    const mockUserRequest = { headers: { authorization: `Bearer ${userToken}` } };
    authenticateRequest(mockUserRequest); // Auth succeeds
    const userRbacResult = authorizeRequest(mockUserRequest, [ROLES.ADMIN]);
    console.log(`User authorization for admin route: ${userRbacResult.authorized ? 'Success' : 'Failed'} (Error: ${userRbacResult.error})`);

    // 5. OAuth2 Mock
    console.log('\nSimulating OAuth2 login...');
    const oauthResult = handleOAuth2Callback({ email: 'new.user@oauth.com', providerId: 'google-12345' });
    console.log('OAuth2 login successful for new user:', oauthResult.success);
    const decoded = verifyJwtToken(oauthResult.token);
    console.log('New user role from OAuth token:', decoded.payload.role);

})();
</script>