/*
Variation 4: The "Modern Functional & Composable" Developer
- Style: Functional, composable, with a focus on dependency injection and pure-as-possible functions.
- Structure: Separates server setup, route definitions, handlers (as factories), and services.
- Conventions: Arrow functions, camelCase, dependency injection via function arguments.

Required packages (package.json):
{
  "dependencies": {
    "fastify": "^4.26.2",
    "@fastify/multipart": "^8.2.0",
    "@fastify/sensible": "^5.5.0",
    "csv-parse": "^5.5.5",
    "csv-stringify": "^6.4.6",
    "sharp": "^0.33.3",
    "uuid": "^9.0.1",
    "fs-extra": "^11.2.0"
  }
}
To run: node main.js
*/

const Fastify = require('fastify');
const path = require('path');
const fs = require('fs-extra');
const os = require('os');
const { v4: uuid } = require('uuid');
const sharp = require('sharp');
const { parse } = require('csv-parse');
const { stringify } = require('csv-stringify');
const { pipeline } = require('stream/promises');

// --- Services (Dependencies) ---
const createServices = () => {
    // Mock DB
    const db = {
        users: new Map(),
        posts: new Map(),
    };
    const adminId = uuid();
    db.users.set(adminId, { id: adminId, email: 'admin@example.com', role: 'ADMIN', is_active: true });
    db.posts.set(uuid(), { id: uuid(), user_id: adminId, title: 'Functional Post', content: '...', status: 'PUBLISHED' });

    // File System Service
    const fileSystemService = {
        tempDir: path.join(os.tmpdir(), 'functional-app-uploads'),
        uploadsDir: path.join(__dirname, 'uploads'),
        async setup() {
            await fs.ensureDir(this.tempDir);
            await fs.ensureDir(this.uploadsDir);
        },
        async cleanup(filePath) {
            await fs.remove(filePath);
        },
    };

    // User Service
    const userService = {
        findById: (id) => db.users.get(id),
        importFromStream: async (readStream) => {
            const parser = readStream.pipe(parse({ columns: true }));
            let count = 0;
            for await (const record of parser) {
                const user = { id: uuid(), ...record, is_active: record.is_active === 'true' };
                db.users.set(user.id, user);
                count++;
            }
            return { count };
        },
    };

    // Post Service
    const postService = {
        getExportStream: () => {
            const stringifier = stringify({ header: true });
            process.nextTick(() => {
                db.posts.forEach(post => stringifier.write(post));
                stringifier.end();
            });
            return stringifier;
        },
    };

    // Image Service
    const imageService = {
        processAvatar: (readStream, outPath) => {
            const transformer = sharp().resize(200, 200).webp();
            return pipeline(readStream, transformer, fs.createWriteStream(outPath));
        },
    };

    return { fileSystemService, userService, postService, imageService };
};

// --- Route Handlers (as factories for dependency injection) ---
const createFileUploadHandlers = ({ fileSystemService, userService, postService, imageService }) => ({
    handleUserImport: async (request, reply) => {
        const part = await request.file();
        if (part.mimetype !== 'text/csv') return reply.badRequest('Only CSV files are allowed.');

        const tempPath = path.join(fileSystemService.tempDir, uuid());
        
        try {
            await pipeline(part.file, fs.createWriteStream(tempPath));
            const { count } = await userService.importFromStream(fs.createReadStream(tempPath));
            return { message: `Imported ${count} users successfully.` };
        } finally {
            await fileSystemService.cleanup(tempPath);
        }
    },

    handleAvatarUpload: async (request, reply) => {
        const { userId } = request.params;
        if (!userService.findById(userId)) return reply.notFound('User not found.');

        const part = await request.file();
        if (!part.mimetype.startsWith('image/')) return reply.badRequest('Only image files are allowed.');

        const avatarPath = path.join(fileSystemService.uploadsDir, `${userId}.webp`);
        await imageService.processAvatar(part.file, avatarPath);
        
        return { message: 'Avatar processed.', path: `/${path.basename(fileSystemService.uploadsDir)}/${userId}.webp` };
    },

    handlePostsExport: (request, reply) => {
        const stream = postService.getExportStream();
        reply
            .header('Content-Type', 'text/csv')
            .header('Content-Disposition', 'attachment; filename="posts.csv"');
        return reply.send(stream);
    },
});

// --- Route Definitions ---
const registerRoutes = (fastify, handlers) => {
    fastify.post('/users/import', handlers.handleUserImport);
    fastify.post('/users/:userId/avatar', handlers.handleAvatarUpload);
    fastify.get('/posts/export', handlers.handlePostsExport);
    return fastify;
};

// --- Server Bootstrap ---
const buildServer = async () => {
    const server = Fastify({ logger: { level: 'info' } });

    // Setup services and handlers
    const services = createServices();
    await services.fileSystemService.setup();
    const handlers = createFileUploadHandlers(services);

    // Register plugins
    server.register(require('@fastify/multipart'));
    server.register(require('@fastify/sensible')); // for reply.badRequest, etc.

    // Register routes
    server.register((instance, opts, done) => {
        registerRoutes(instance, handlers);
        done();
    }, { prefix: '/api/files' });

    return server;
};

// --- Main Execution ---
const start = async () => {
    try {
        const server = await buildServer();
        await server.listen({ port: 3000 });
        console.log('Server listening on http://localhost:3000');
    } catch (err) {
        console.error('Error starting server:', err);
        process.exit(1);
    }
};

start();