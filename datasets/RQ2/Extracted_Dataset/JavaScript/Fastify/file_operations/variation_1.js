/*
Variation 1: The "Classic Plugin" Developer
- Style: Functional, modular, follows Fastify's plugin-centric architecture.
- Structure: Separates server, routes (plugin), and services.
- Conventions: camelCase, async/await, JSON schemas for validation.

Required packages (package.json):
{
  "dependencies": {
    "fastify": "^4.26.2",
    "@fastify/multipart": "^8.2.0",
    "csv-parse": "^5.5.5",
    "csv-stringify": "^6.4.6",
    "sharp": "^0.33.3",
    "uuid": "^9.0.1",
    "fs-extra": "^11.2.0"
  }
}
To run: node server.js
*/

const Fastify = require('fastify');
const path = require('path');
const fs = require('fs-extra');
const os = require('os');
const { v4: uuidv4 } = require('uuid');

// --- Mock Database Service ---
const db = {
    users: new Map(),
    posts: new Map(),
};

// Seed with some data
const adminUserId = uuidv4();
db.users.set(adminUserId, {
    id: adminUserId,
    email: 'admin@example.com',
    password_hash: 'hashed_password',
    role: 'ADMIN',
    is_active: true,
    created_at: new Date().toISOString(),
});
const postId = uuidv4();
db.posts.set(postId, {
    id: postId,
    user_id: adminUserId,
    title: 'First Post',
    content: 'This is the content of the first post.',
    status: 'PUBLISHED',
});


// --- Image Processing Service ---
const sharp = require('sharp');
const imageService = {
    resizeAvatar: async (inputStream, userId) => {
        const uploadsDir = path.join(__dirname, 'uploads', 'avatars');
        await fs.ensureDir(uploadsDir);
        const filePath = path.join(uploadsDir, `${userId}.webp`);
        
        const transformer = sharp()
            .resize(200, 200, { fit: 'cover' })
            .webp({ quality: 80 });

        await new Promise((resolve, reject) => {
            const writeStream = fs.createWriteStream(filePath);
            inputStream.pipe(transformer).pipe(writeStream);
            writeStream.on('finish', resolve);
            writeStream.on('error', reject);
            inputStream.on('error', reject); // Handle input stream errors
        });

        return `/uploads/avatars/${userId}.webp`;
    }
};

// --- CSV Processing Service ---
const { parse } = require('csv-parse');
const { stringify } = require('csv-stringify');
const util = require('util');
const stream = require('stream');
const pipeline = util.promisify(stream.pipeline);

const csvService = {
    importUsers: async (filePath) => {
        const parser = fs
            .createReadStream(filePath)
            .pipe(parse({ columns: true, skip_empty_lines: true }));
        
        const importedUsers = [];
        for await (const record of parser) {
            const newUser = {
                id: uuidv4(),
                email: record.email,
                password_hash: 'default_hashed_password', // In real app, generate hash
                role: record.role || 'USER',
                is_active: record.is_active ? record.is_active.toLowerCase() === 'true' : false,
                created_at: new Date().toISOString(),
            };
            db.users.set(newUser.id, newUser);
            importedUsers.push(newUser);
        }
        return { count: importedUsers.length, users: importedUsers };
    },

    exportPostsStream: () => {
        const stringifier = stringify({
            header: true,
            columns: ['id', 'user_id', 'title', 'content', 'status']
        });

        // Push data to the stream
        for (const post of db.posts.values()) {
            stringifier.write(post);
        }
        stringifier.end();

        return stringifier;
    }
};


// --- Fastify Plugin for File Operations Routes ---
const fileOperationsPlugin = async (fastify, options) => {
    const tempDir = path.join(os.tmpdir(), 'my-app-uploads');
    await fs.ensureDir(tempDir);

    // 1. User CSV Batch Upload Route
    fastify.post('/users/import', {
        schema: {
            consumes: ['multipart/form-data'],
            description: 'Upload a CSV file to batch-create users.',
        }
    }, async (request, reply) => {
        const data = await request.file();
        if (!data || data.mimetype !== 'text/csv') {
            return reply.code(400).send({ error: 'Invalid file type. Please upload a CSV.' });
        }

        const tempFilePath = path.join(tempDir, `${uuidv4()}-${data.filename}`);
        
        try {
            await pipeline(data.file, fs.createWriteStream(tempFilePath));
            const result = await csvService.importUsers(tempFilePath);
            reply.send({ message: `Successfully imported ${result.count} users.`, data: result.users });
        } catch (err) {
            fastify.log.error(err, 'Error processing user import CSV');
            reply.code(500).send({ error: 'Failed to process CSV file.' });
        } finally {
            await fs.remove(tempFilePath); // Temporary file management
        }
    });

    // 2. User Avatar Upload and Processing Route
    fastify.post('/users/:id/avatar', {
        schema: {
            consumes: ['multipart/form-data'],
            params: {
                type: 'object',
                properties: { id: { type: 'string', format: 'uuid' } }
            },
            description: 'Upload and resize a user avatar image.',
        }
    }, async (request, reply) => {
        const { id } = request.params;
        if (!db.users.has(id)) {
            return reply.code(404).send({ error: 'User not found.' });
        }

        const data = await request.file();
        if (!data || !data.mimetype.startsWith('image/')) {
            return reply.code(400).send({ error: 'Invalid file type. Please upload an image.' });
        }

        try {
            const avatarPath = await imageService.resizeAvatar(data.file, id);
            reply.send({ message: 'Avatar updated successfully.', path: avatarPath });
        } catch (err) {
            fastify.log.error(err, 'Error processing avatar image');
            reply.code(500).send({ error: 'Failed to process image.' });
        }
    });

    // 3. Post Data Export (Streaming Download)
    fastify.get('/posts/export', {
        schema: {
            description: 'Download all posts as a CSV file.',
        }
    }, async (request, reply) => {
        const csvStream = csvService.exportPostsStream();
        
        reply.header('Content-Type', 'text/csv');
        reply.header('Content-Disposition', 'attachment; filename="posts_export.csv"');
        
        return reply.send(csvStream);
    });
};


// --- Main Server Setup ---
const server = Fastify({
    logger: {
        transport: {
            target: 'pino-pretty'
        }
    }
});

// Register plugins
server.register(require('@fastify/multipart'));
server.register(fileOperationsPlugin, { prefix: '/api/v1' });

// Start server
const start = async () => {
    try {
        await server.listen({ port: 3000 });
        console.log('Server running at http://localhost:3000');
    } catch (err) {
        server.log.error(err);
        process.exit(1);
    }
};

start();