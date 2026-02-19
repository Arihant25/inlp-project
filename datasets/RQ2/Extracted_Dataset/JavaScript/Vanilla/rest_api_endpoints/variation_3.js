<script>
const http = require('http');
const { URL } = require('url');
const { randomUUID } = require('crypto');

// --- Simulated Module: user.repository.js ---
const UserRepository = {
    _users: {}, // Using an object as a hash map for O(1) lookups

    _initialize: function() {
        const mockData = [
            { id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479', email: 'admin@example.com', password_hash: '$2b$10$fakedhashforadminexample', role: 'ADMIN', is_active: true, created_at: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString() },
            { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'user1@example.com', password_hash: '$2b$10$fakedhashforuserexample1', role: 'USER', is_active: true, created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString() },
            { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user2@example.com', password_hash: '$2b$10$fakedhashforuserexample2', role: 'USER', is_active: false, created_at: new Date().toISOString() }
        ];
        mockData.forEach(user => this._users[user.id] = user);
    },

    findAll: function() {
        return Object.values(this._users);
    },
    findById: function(id) {
        return this._users[id] || null;
    },
    save: function(userEntity) {
        this._users[userEntity.id] = userEntity;
        return userEntity;
    },
    deleteById: function(id) {
        if (this._users[id]) {
            delete this._users[id];
            return true;
        }
        return false;
    }
};
UserRepository._initialize();

// --- Simulated Module: user.service.js ---
const UserService = {
    _repository: UserRepository,
    VALID_ROLES: ['ADMIN', 'USER'],

    processFindAllUsers: function(queryParams) {
        let users = this._repository.findAll();

        if (queryParams.role) {
            users = users.filter(u => u.role === queryParams.role.toUpperCase());
        }
        if (queryParams.is_active !== undefined) {
            const isActive = queryParams.is_active === 'true';
            users = users.filter(u => u.is_active === isActive);
        }

        const page = parseInt(queryParams.page, 10) || 1;
        const limit = parseInt(queryParams.limit, 10) || 10;
        const startIndex = (page - 1) * limit;

        return {
            totalItems: users.length,
            data: users.slice(startIndex, startIndex + limit),
            totalPages: Math.ceil(users.length / limit),
            currentPage: page,
        };
    },

    processFindUserById: function(userId) {
        return this._repository.findById(userId);
    },

    processCreateUser: function(userDto) {
        if (!userDto.email || !userDto.password_hash || !userDto.role) {
            return { error: 'Validation failed: Missing required fields.', status: 400 };
        }
        if (!this.VALID_ROLES.includes(userDto.role)) {
            return { error: 'Validation failed: Invalid role.', status: 400 };
        }
        const newUserEntity = {
            id: randomUUID(),
            created_at: new Date().toISOString(),
            is_active: userDto.is_active !== false,
            ...userDto
        };
        return { data: this._repository.save(newUserEntity), status: 201 };
    },

    processUpdateUser: function(userId, userDto) {
        const existingUser = this._repository.findById(userId);
        if (!existingUser) {
            return { error: 'User not found.', status: 404 };
        }
        const updatedUser = { ...existingUser, ...userDto, id: userId };
        return { data: this._repository.save(updatedUser), status: 200 };
    },

    processDeleteUser: function(userId) {
        const wasDeleted = this._repository.deleteById(userId);
        return wasDeleted ? { status: 204 } : { error: 'User not found.', status: 404 };
    }
};

// --- Simulated Module: router.js ---
const Router = {
    _routes: [],
    
    register: function(method, pathRegex, handler) {
        this._routes.push({ method, pathRegex, handler });
    },

    resolve: async function(req, res) {
        const reqUrl = new URL(req.url, `http://${req.headers.host}`);
        
        for (const route of this._routes) {
            const match = reqUrl.pathname.match(route.pathRegex);
            if (match && req.method === route.method) {
                const params = match.groups || {};
                const query = Object.fromEntries(reqUrl.searchParams.entries());
                let body = null;
                if (['POST', 'PUT', 'PATCH'].includes(req.method)) {
                    body = await this._parseJsonBody(req);
                }
                return route.handler({ params, query, body, res });
            }
        }
        this._sendJsonResponse(res, 404, { message: "Resource not found" });
    },

    _parseJsonBody: (req) => new Promise((resolve, reject) => {
        let data = '';
        req.on('data', chunk => data += chunk);
        req.on('end', () => resolve(JSON.parse(data)));
        req.on('error', err => reject(err));
    }),

    _sendJsonResponse: (res, statusCode, payload) => {
        if (statusCode === 204) {
            res.writeHead(204).end();
            return;
        }
        res.writeHead(statusCode, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(payload));
    }
};

// --- Route Definitions ---
Router.register('GET', /^\/users$/, (ctx) => {
    const result = UserService.processFindAllUsers(ctx.query);
    Router._sendJsonResponse(ctx.res, 200, result);
});

Router.register('POST', /^\/users$/, (ctx) => {
    const { data, error, status } = UserService.processCreateUser(ctx.body);
    Router._sendJsonResponse(ctx.res, status, error ? { message: error } : data);
});

Router.register('GET', /^\/users\/(?<id>[a-f0-9-]+)$/, (ctx) => {
    const user = UserService.processFindUserById(ctx.params.id);
    user ? Router._sendJsonResponse(ctx.res, 200, user) : Router._sendJsonResponse(ctx.res, 404, { message: 'User not found' });
});

Router.register('PUT', /^\/users\/(?<id>[a-f0-9-]+)$/, (ctx) => {
    const { data, error, status } = UserService.processUpdateUser(ctx.params.id, ctx.body);
    Router._sendJsonResponse(ctx.res, status, error ? { message: error } : data);
});

Router.register('DELETE', /^\/users\/(?<id>[a-f0-9-]+)$/, (ctx) => {
    const { error, status } = UserService.processDeleteUser(ctx.params.id);
    Router._sendJsonResponse(ctx.res, status, error ? { message: error } : null);
});

// --- Server Initialization ---
const server = http.createServer((req, res) => {
    Router.resolve(req, res).catch(err => {
        console.error("Unhandled error:", err);
        Router._sendJsonResponse(res, 500, { message: "An unexpected error occurred." });
    });
});

const PORT = 3002;
server.listen(PORT, () => {
    console.log(`Variation 3 (Modular) server running on http://localhost:${PORT}`);
});
</script>