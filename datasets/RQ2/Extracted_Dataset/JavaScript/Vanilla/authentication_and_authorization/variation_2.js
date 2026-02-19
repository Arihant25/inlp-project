<script>
// Variation 2: Object-Oriented Programming (OOP) Style
// This style encapsulates logic within classes, promoting structure and reusability.

const crypto = require('crypto');

// --- Domain Models ---
class User {
    constructor(id, email, password_hash, role, is_active = true) {
        this.id = id;
        this.email = email;
        this.password_hash = password_hash;
        this.role = role;
        this.is_active = is_active;
        this.created_at = new Date().toISOString();
    }
}

class Post {
    constructor(id, user_id, title, content, status = 'DRAFT') {
        this.id = id;
        this.user_id = user_id;
        this.title = title;
        this.content = content;
        this.status = status;
    }
}

// --- In-Memory Database Simulation ---
class Database {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
    }

    findUserByEmail(email) {
        for (const user of this.users.values()) {
            if (user.email === email) {
                return user;
            }
        }
        return null;
    }
}

// --- Core Services ---
class PasswordManager {
    static #SALT = 'a-different-salt-for-oop'; // In production, store salt with hash

    static async hash(password) {
        return new Promise((resolve, reject) => {
            crypto.scrypt(password, this.#SALT, 64, (err, derivedKey) => {
                if (err) reject(err);
                resolve(derivedKey.toString('hex'));
            });
        });
    }

    static async compare(password, storedHash) {
        const inputHash = await this.hash(password);
        try {
            return crypto.timingSafeEqual(Buffer.from(storedHash, 'hex'), Buffer.from(inputHash, 'hex'));
        } catch {
            return false;
        }
    }
}

class JWTManager {
    static #SECRET = 'a-very-secure-oop-secret-key';
    static #ALGORITHM = 'HS256';

    static #base64urlEncode(data) {
        return Buffer.from(JSON.stringify(data)).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
    }

    static #base64urlDecode(str) {
        str = str.replace(/-/g, '+').replace(/_/g, '/');
        while (str.length % 4) { str += '='; }
        return JSON.parse(Buffer.from(str, 'base64').toString());
    }

    static sign(payload) {
        const header = { alg: this.#ALGORITHM, typ: 'JWT' };
        const encodedHeader = this.#base64urlEncode(header);
        const encodedPayload = this.#base64urlEncode(payload);
        const signatureInput = `${encodedHeader}.${encodedPayload}`;

        const signature = crypto.createHmac('sha256', this.#SECRET).update(signatureInput).digest('base64url');
        return `${signatureInput}.${signature}`;
    }

    static verify(token) {
        const [header, payload, signature] = token.split('.');
        if (!header || !payload || !signature) throw new Error('Invalid JWT format');

        const signatureInput = `${header}.${payload}`;
        const expectedSignature = crypto.createHmac('sha256', this.#SECRET).update(signatureInput).digest('base64url');

        if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) {
            throw new Error('Invalid JWT signature');
        }

        const decodedPayload = this.#base64urlDecode(payload);
        if (decodedPayload.exp * 1000 < Date.now()) {
            throw new Error('JWT has expired');
        }
        return decodedPayload;
    }
}

class SessionManager {
    constructor() {
        this.sessions = new Map();
    }
    create(userId) {
        const sessionId = crypto.randomUUID();
        this.sessions.set(sessionId, { userId, loggedInAt: new Date() });
        return sessionId;
    }
    get(sessionId) {
        return this.sessions.get(sessionId);
    }
    invalidate(sessionId) {
        this.sessions.delete(sessionId);
    }
}

class OAuth2Client {
    constructor(database) {
        this.db = database;
    }

    processCallback(oauthData) {
        // Mock: Assume we received user data from OAuth provider
        let user = this.db.findUserByEmail(oauthData.email);
        if (!user) {
            const newId = crypto.randomUUID();
            const passwordPlaceholder = `oauth|${oauthData.provider}|${oauthData.id}`;
            user = new User(newId, oauthData.email, passwordPlaceholder, 'USER');
            this.db.users.set(newId, user);
        }
        return user;
    }
}

class AuthService {
    constructor(database, sessionManager) {
        this.db = database;
        this.sessionManager = sessionManager;
        this.oauthClient = new OAuth2Client(database);
    }

    async login(email, password) {
        const user = this.db.findUserByEmail(email);
        if (!user || !user.is_active) return null;

        const isValid = await PasswordManager.compare(password, user.password_hash);
        if (!isValid) return null;

        const token = JWTManager.sign({
            sub: user.id,
            role: user.role,
            iat: Math.floor(Date.now() / 1000),
            exp: Math.floor(Date.now() / 1000) + 3600,
        });
        const sessionId = this.sessionManager.create(user.id);
        return { user, token, sessionId };
    }

    loginWithOAuth(oauthData) {
        const user = this.oauthClient.processCallback(oauthData);
        const token = JWTManager.sign({
            sub: user.id,
            role: user.role,
            iat: Math.floor(Date.now() / 1000),
            exp: Math.floor(Date.now() / 1000) + 3600,
        });
        return { user, token };
    }

    // Middleware factory
    static authenticate() {
        return (req, db) => {
            try {
                const token = req.headers.authorization?.split(' ')[1];
                if (!token) throw new Error('No token provided');
                const payload = JWTManager.verify(token);
                req.user = db.users.get(payload.sub);
                if (!req.user || !req.user.is_active) throw new Error('User not found or inactive');
            } catch (error) {
                req.error = error.message;
            }
        };
    }

    // RBAC Middleware factory
    static requireRole(...roles) {
        return (req) => {
            if (!req.user || !roles.includes(req.user.role)) {
                req.error = 'Forbidden: Insufficient permissions';
            }
        };
    }
}

// --- Main Execution Logic (Demonstration) ---
(async () => {
    console.log('--- Variation 2: OOP Demo ---');

    // 1. Setup
    const db = new Database();
    const sessionManager = new SessionManager();
    const authService = new AuthService(db, sessionManager);

    const admin = new User(crypto.randomUUID(), 'admin@corp.com', await PasswordManager.hash('securepass1'), 'ADMIN');
    const user = new User(crypto.randomUUID(), 'user@corp.com', await PasswordManager.hash('securepass2'), 'USER');
    db.users.set(admin.id, admin);
    db.users.set(user.id, user);

    // 2. Login
    console.log('\nAttempting admin login...');
    const loginDetails = await authService.login('admin@corp.com', 'securepass1');
    if (!loginDetails) {
        console.error('Login failed.');
        return;
    }
    console.log(`Login successful for ${loginDetails.user.email}. Session: ${loginDetails.sessionId}`);
    const adminToken = loginDetails.token;

    // 3. Simulate API request with middleware
    console.log('\nSimulating admin request to create a post...');
    const req = { headers: { authorization: `Bearer ${adminToken}` } };

    const authenticateMiddleware = AuthService.authenticate();
    const authorizeAdminMiddleware = AuthService.requireRole('ADMIN');

    authenticateMiddleware(req, db);
    if (req.error) {
        console.error('Authentication failed:', req.error);
        return;
    }
    console.log('Authentication successful.');

    authorizeAdminMiddleware(req);
    if (req.error) {
        console.error('Authorization failed:', req.error);
        return;
    }
    console.log('Authorization successful. Admin can proceed.');

    // 4. Simulate failed authorization
    console.log('\nSimulating user trying to access admin resource...');
    const userLogin = await authService.login('user@corp.com', 'securepass2');
    const userReq = { headers: { authorization: `Bearer ${userLogin.token}` } };
    authenticateMiddleware(userReq, db);
    authorizeAdminMiddleware(userReq);
    console.log(`User authorization attempt result: ${userReq.error ? userReq.error : 'Success'}`);

    // 5. OAuth2 Flow
    console.log('\nSimulating OAuth2 login...');
    const oauthData = { provider: 'github', id: 'gh-98765', email: 'dev@github.com' };
    const oauthLoginDetails = authService.loginWithOAuth(oauthData);
    console.log(`OAuth login successful for ${oauthLoginDetails.user.email}.`);
    console.log('New user created in DB:', db.findUserByEmail('dev@github.com') != null);

})();
</script>