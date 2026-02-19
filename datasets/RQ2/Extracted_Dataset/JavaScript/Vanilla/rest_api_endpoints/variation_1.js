<script>
const http = require('http');
const { URL } = require('url');
const { randomUUID } = require('crypto');

// --- In-Memory Database ---
let users = [
    {
        id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
        email: 'admin@example.com',
        password_hash: '$2b$10$fakedhashforadminexample',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString()
    },
    {
        id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        email: 'user1@example.com',
        password_hash: '$2b$10$fakedhashforuserexample1',
        role: 'USER',
        is_active: true,
        created_at: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString()
    },
    {
        id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0',
        email: 'user2@example.com',
        password_hash: '$2b$10$fakedhashforuserexample2',
        role: 'USER',
        is_active: false,
        created_at: new Date().toISOString()
    }
];
const USER_ROLES = ['ADMIN', 'USER'];

// --- Utility Functions ---
function getRequestBody(req) {
    return new Promise((resolve, reject) => {
        try {
            let body = '';
            req.on('data', (chunk) => {
                body += chunk.toString();
            });
            req.on('end', () => {
                resolve(JSON.parse(body));
            });
        } catch (error) {
            reject(error);
        }
    });
}

function sendResponse(res, statusCode, data) {
    res.writeHead(statusCode, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data));
}

// --- Main Request Handler ---
const requestHandler = async (req, res) => {
    const reqUrl = new URL(req.url, `http://${req.headers.host}`);
    const path = reqUrl.pathname;
    const method = req.method;

    const userPathRegex = /^\/users\/([a-f0-9-]+)$/;
    const userIdMatch = path.match(userPathRegex);

    try {
        // Route: GET /users
        if (path === '/users' && method === 'GET') {
            let filteredUsers = [...users];
            const queryParams = reqUrl.searchParams;

            // Filtering
            if (queryParams.has('role')) {
                filteredUsers = filteredUsers.filter(u => u.role === queryParams.get('role').toUpperCase());
            }
            if (queryParams.has('is_active')) {
                filteredUsers = filteredUsers.filter(u => u.is_active.toString() === queryParams.get('is_active'));
            }

            // Pagination
            const page = parseInt(queryParams.get('page'), 10) || 1;
            const limit = parseInt(queryParams.get('limit'), 10) || 10;
            const startIndex = (page - 1) * limit;
            const endIndex = page * limit;
            const paginatedUsers = filteredUsers.slice(startIndex, endIndex);

            sendResponse(res, 200, {
                totalItems: filteredUsers.length,
                totalPages: Math.ceil(filteredUsers.length / limit),
                currentPage: page,
                users: paginatedUsers
            });
        }
        // Route: POST /users
        else if (path === '/users' && method === 'POST') {
            const body = await getRequestBody(req);
            if (!body.email || !body.password_hash || !body.role) {
                return sendResponse(res, 400, { error: 'Missing required fields: email, password_hash, role' });
            }
            if (!USER_ROLES.includes(body.role)) {
                return sendResponse(res, 400, { error: 'Invalid role specified' });
            }

            const newUser = {
                id: randomUUID(),
                email: body.email,
                password_hash: body.password_hash,
                role: body.role,
                is_active: body.is_active !== undefined ? body.is_active : true,
                created_at: new Date().toISOString()
            };
            users.push(newUser);
            sendResponse(res, 201, newUser);
        }
        // Route: GET /users/:id
        else if (userIdMatch && method === 'GET') {
            const id = userIdMatch[1];
            const user = users.find(u => u.id === id);
            if (user) {
                sendResponse(res, 200, user);
            } else {
                sendResponse(res, 404, { error: 'User not found' });
            }
        }
        // Route: PUT /users/:id
        else if (userIdMatch && (method === 'PUT' || method === 'PATCH')) {
            const id = userIdMatch[1];
            const userIndex = users.findIndex(u => u.id === id);
            if (userIndex === -1) {
                return sendResponse(res, 404, { error: 'User not found' });
            }

            const body = await getRequestBody(req);
            const updatedUser = { ...users[userIndex], ...body, id: id }; // Ensure ID is not changed
            users[userIndex] = updatedUser;
            sendResponse(res, 200, updatedUser);
        }
        // Route: DELETE /users/:id
        else if (userIdMatch && method === 'DELETE') {
            const id = userIdMatch[1];
            const initialLength = users.length;
            users = users.filter(u => u.id !== id);
            if (users.length < initialLength) {
                res.writeHead(204);
                res.end();
            } else {
                sendResponse(res, 404, { error: 'User not found' });
            }
        }
        // Route: Not Found
        else {
            sendResponse(res, 404, { error: 'Endpoint not found' });
        }
    } catch (error) {
        console.error('Server Error:', error);
        sendResponse(res, 500, { error: 'Internal Server Error' });
    }
};

// --- Server Initialization ---
const server = http.createServer(requestHandler);
const PORT = 3000;
server.listen(PORT, () => {
    console.log(`Variation 1 (Procedural) server running on http://localhost:${PORT}`);
});
</script>