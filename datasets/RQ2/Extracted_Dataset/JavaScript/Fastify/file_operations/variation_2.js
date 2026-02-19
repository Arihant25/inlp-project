/*
Variation 2: The "OOP/Class-based" Developer
- Style: Object-Oriented, with logic encapsulated in Controller and Service classes.
- Structure: A main app file, a Controller class for handlers, and a Service class for business logic.
- Conventions: PascalCase for classes, camelCase for methods/variables, dependency injection in constructor.

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
To run: node app.js
*/

const Fastify = require('fastify');
const path = require('path');
const fs = require('fs-extra');
const os = require('os');
const { v4: uuidv4 } = require('uuid');
const sharp = require('sharp');
const { parse } = require('csv-parse');
const { stringify } = require('csv-stringify');
const { pipeline } = require('stream/promises');

// --- Mock Database Service ---
class DatabaseService {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
        this._seedData();
    }

    _seedData() {
        const adminUserId = uuidv4();
        this.users.set(adminUserId, {
            id: adminUserId,
            email: 'admin@example.com',
            password_hash: 'hashed_password',
            role: 'ADMIN',
            is_active: true,
            created_at: new Date().toISOString(),
        });
        const postId = uuidv4();
        this.posts.set(postId, {
            id: postId,
            user_id: adminUserId,
            title: 'First Post',
            content: 'This is the content of the first post.',
            status: 'PUBLISHED',
        });
    }

    findUserById(id) {
        return this.users.get(id);
    }

    saveUser(user) {
        this.users.set(user.id, user);
    }

    getAllPosts() {
        return Array.from(this.posts.values());
    }
}

// --- Business Logic Service ---
class FileService {
    constructor(dbService) {
        this.dbService = dbService;
        this.tempDir = path.join(os.tmpdir(), 'my-app-oop');
        fs.ensureDirSync(this.tempDir);
    }

    async processUserImport(fileStream, originalFilename) {
        const tempFilePath = path.join(this.tempDir, `${uuidv4()}-${originalFilename}`);
        await pipeline(fileStream, fs.createWriteStream(tempFilePath));

        const parser = fs.createReadStream(tempFilePath).pipe(parse({ columns: true }));
        let count = 0;
        for await (const record of parser) {
            const newUser = {
                id: uuidv4(),
                email: record.email,
                password_hash: 'default_hashed_password',
                role: record.role || 'USER',
                is_active: record.is_active === 'true',
                created_at: new Date().toISOString(),
            };
            this.dbService.saveUser(newUser);
            count++;
        }

        await fs.remove(tempFilePath); // Cleanup
        return { count };
    }

    async processAvatar(fileStream, userId) {
        const uploadsDir = path.join(__dirname, 'uploads', 'avatars');
        await fs.ensureDir(uploadsDir);
        const filePath = path.join(uploadsDir, `${userId}.webp`);

        const transformer = sharp().resize(200, 200).webp({ quality: 80 });
        await pipeline(fileStream, transformer, fs.createWriteStream(filePath));
        
        return `/uploads/avatars/${userId}.webp`;
    }

    getPostsExportStream() {
        const stringifier = stringify({ header: true, columns: ['id', 'user_id', 'title', 'status'] });
        const posts = this.dbService.getAllPosts();
        
        process.nextTick(() => {
            posts.forEach(post => stringifier.write(post));
            stringifier.end();
        });

        return stringifier;
    }
}

// --- Controller for handling HTTP requests ---
class FileController {
    constructor(fileService, dbService) {
        this.fileService = fileService;
        this.dbService = dbService;
    }

    async importUsers(request, reply) {
        const part = await request.file();
        if (!part || part.mimetype !== 'text/csv') {
            return reply.code(400).send({ error: 'CSV file is required.' });
        }
        try {
            const result = await this.fileService.processUserImport(part.file, part.filename);
            reply.send({ message: `Successfully imported ${result.count} users.` });
        } catch (error) {
            request.log.error(error, 'User import failed');
            reply.code(500).send({ error: 'Could not process the file.' });
        }
    }

    async uploadAvatar(request, reply) {
        const { id } = request.params;
        if (!this.dbService.findUserById(id)) {
            return reply.code(404).send({ error: 'User not found.' });
        }

        const part = await request.file();
        if (!part || !part.mimetype.startsWith('image/')) {
            return reply.code(400).send({ error: 'Image file is required.' });
        }

        try {
            const avatarPath = await this.fileService.processAvatar(part.file, id);
            reply.send({ message: 'Avatar updated.', path: avatarPath });
        } catch (error) {
            request.log.error(error, 'Avatar upload failed');
            reply.code(500).send({ error: 'Could not process the image.' });
        }
    }

    async exportPosts(request, reply) {
        const csvStream = this.fileService.getPostsExportStream();
        reply.header('Content-Type', 'text/csv');
        reply.header('Content-Disposition', 'attachment; filename="posts_export.csv"');
        return reply.send(csvStream);
    }
}

// --- Main Application Setup ---
const main = async () => {
    const fastify = Fastify({ logger: true });

    // Dependency Injection
    const dbService = new DatabaseService();
    const fileService = new FileService(dbService);
    const fileController = new FileController(fileService, dbService);

    // Register plugins
    fastify.register(require('@fastify/multipart'));

    // Register routes
    fastify.register(async (instance) => {
        instance.post('/users/import', fileController.importUsers.bind(fileController));
        instance.post('/users/:id/avatar', fileController.uploadAvatar.bind(fileController));
        instance.get('/posts/export', fileController.exportPosts.bind(fileController));
    }, { prefix: '/api' });

    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

main();