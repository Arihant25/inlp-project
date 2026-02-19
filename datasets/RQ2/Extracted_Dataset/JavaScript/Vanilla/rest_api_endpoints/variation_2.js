<script>
const http = require('http');
const { URL } = require('url');
const { randomUUID } = require('crypto');

// --- Data Layer ---
class UserDataStore {
    constructor() {
        this.users = new Map();
        this.initializeMockData();
    }

    initializeMockData() {
        const mockUsers = [
            { id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', email: 'admin@example.com', password_hash: '$2b$10$fakedhashforadminexample', role: 'ADMIN', is_active: true, created_at: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString() },
            { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'user1@example.com', password_hash: '$2b$10$fakedhashforuserexample1', role: 'USER', is_active: true, created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString() },
            { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user2@example.com', password_hash: '$2b$10$fakedhashforuserexample2', role: 'USER', is_active: false, created_at: new Date().toISOString() }
        ];
        mockUsers.forEach(user => this.users.set(user.id, user));
    }

    findAll() { return Array.from(this.users.values()); }
    findById(id) { return this.users.get(id); }
    save(user) { this.users.set(user.id, user); return user; }
    delete(id) { return this.users.delete(id); }
}

// --- Service Layer (Business Logic) ---
class UserService {
    constructor(dataStore) {
        this.dataStore = dataStore;
        this.USER_ROLES = new Set(['ADMIN', 'USER']);
    }

    listUsers(filters, pagination) {
        let allUsers = this.dataStore.findAll();

        if (filters.role) {
            allUsers = allUsers.filter(u => u.role === filters.role.toUpperCase());
        }
        if (filters.is_active !== undefined) {
            allUsers = allUsers.filter(u => u.is_active === filters.is_active);
        }

        const totalItems = allUsers.length;
        const page = pagination.page || 1;
        const limit = pagination.limit || 10;
        const startIndex = (page - 1) * limit;
        const paginatedUsers = allUsers.slice(startIndex, startIndex + limit);

        return {
            totalItems,
            totalPages: Math.ceil(totalItems / limit),
            currentPage: page,
            users: paginatedUsers
        };
    }

    getUserById(id) {
        return this.dataStore.findById(id);
    }

    createUser(userData) {
        if (!userData.email || !userData.password_hash || !userData.role) {
            throw new Error('Missing required fields');
        }
        if (!this.USER_ROLES.has(userData.role)) {
            throw new Error('Invalid role');
        }
        const newUser = {
            id: randomUUID(),
            email: userData.email,
            password_hash: userData.password_hash,
            role: userData.role,
            is_active: userData.is_active !== false,
            created_at: new Date().toISOString()
        };
        return this.dataStore.save(newUser);
    }

    updateUser(id, updateData) {
        const existingUser = this.dataStore.findById(id);
        if (!existingUser) return null;

        const updatedUser = { ...existingUser, ...updateData, id };
        return this.dataStore.save(updatedUser);
    }

    deleteUser(id) {
        return this.dataStore.delete(id);
    }
}

// --- Controller Layer (HTTP Handling) ---
class UserController {
    constructor(userService) {
        this.userService = userService;
    }

    async handleRequest(req, res) {
        const { method, url } = req;
        const { pathname, searchParams } = new URL(url, `http://${req.headers.host}`);
        const pathParts = pathname.split('/').filter(Boolean);

        try {
            if (pathParts[0] === 'users') {
                const userId = pathParts[1];
                if (userId) {
                    await this.handleUserById(method, res, userId);
                } else {
                    await this.handleUsersCollection(method, req, res, searchParams);
                }
            } else {
                this.sendJSON(res, 404, { message: 'Not Found' });
            }
        } catch (error) {
            this.sendJSON(res, 500, { message: 'Internal Server Error', error: error.message });
        }
    }

    async handleUsersCollection(method, req, res, searchParams) {
        switch (method) {
            case 'GET':
                const filters = {
                    role: searchParams.get('role'),
                    is_active: searchParams.has('is_active') ? searchParams.get('is_active') === 'true' : undefined
                };
                const pagination = {
                    page: parseInt(searchParams.get('page'), 10),
                    limit: parseInt(searchParams.get('limit'), 10)
                };
                const result = this.userService.listUsers(filters, pagination);
                this.sendJSON(res, 200, result);
                break;
            case 'POST':
                const body = await this.parseBody(req);
                const newUser = this.userService.createUser(body);
                this.sendJSON(res, 201, newUser);
                break;
            default:
                this.sendJSON(res, 405, { message: 'Method Not Allowed' });
        }
    }

    async handleUserById(method, res, userId) {
        switch (method) {
            case 'GET':
                const user = this.userService.getUserById(userId);
                user ? this.sendJSON(res, 200, user) : this.sendJSON(res, 404, { message: 'User not found' });
                break;
            case 'PUT':
            case 'PATCH':
                const body = await this.parseBody(req);
                const updatedUser = this.userService.updateUser(userId, body);
                updatedUser ? this.sendJSON(res, 200, updatedUser) : this.sendJSON(res, 404, { message: 'User not found' });
                break;
            case 'DELETE':
                const deleted = this.userService.deleteUser(userId);
                deleted ? res.writeHead(204).end() : this.sendJSON(res, 404, { message: 'User not found' });
                break;
            default:
                this.sendJSON(res, 405, { message: 'Method Not Allowed' });
        }
    }

    sendJSON(res, statusCode, payload) {
        res.writeHead(statusCode, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(payload));
    }

    parseBody(req) {
        return new Promise((resolve, reject) => {
            let body = '';
            req.on('data', chunk => body += chunk.toString());
            req.on('end', () => resolve(JSON.parse(body)));
            req.on('error', err => reject(err));
        });
    }
}

// --- Server Initialization ---
const userDataStore = new UserDataStore();
const userService = new UserService(userDataStore);
const userController = new UserController(userService);

const server = http.createServer((req, res) => userController.handleRequest(req, res));
const PORT = 3001;
server.listen(PORT, () => {
    console.log(`Variation 2 (OOP) server running on http://localhost:${PORT}`);
});
</script>