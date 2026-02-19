/*
Variation 3: The "All-in-One Script" Developer (The Pragmatist)
- Style: A single, self-contained script. Less modular, but direct and easy to grasp for smaller tasks.
- Structure: Everything in one file, with helper functions defined at the top.
- Conventions: snake_case for variables and functions, CommonJS `require`, direct library usage in handlers.

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
To run: node index.js
*/

const fastify = require('fastify')({ logger: true });
const multipart = require('@fastify/multipart');
const path = require('path');
const fs = require('fs-extra');
const os = require('os');
const { v4: generate_uuid } = require('uuid');
const sharp = require('sharp');
const { parse } = require('csv-parse');
const { stringify } = require('csv-stringify');
const { pipeline } = require('stream/promises');

// --- Mock Data Store ---
const data_store = {
    users: {},
    posts: {},
};

// --- Initial Data ---
const init_data = () => {
    const admin_user_id = generate_uuid();
    data_store.users[admin_user_id] = {
        id: admin_user_id,
        email: 'admin@example.com',
        password_hash: 'some_hash',
        role: 'ADMIN',
        is_active: true,
        created_at: new Date().toISOString(),
    };
    const post_id = generate_uuid();
    data_store.posts[post_id] = {
        id: post_id,
        user_id: admin_user_id,
        title: 'My First Post',
        content: 'Content here.',
        status: 'PUBLISHED',
    };
    console.log('Data store initialized.');
};
init_data();

// --- Setup Temporary Directory ---
const temp_dir = path.join(os.tmpdir(), 'pragmatic-app');
fs.ensureDirSync(temp_dir);
console.log(`Temporary directory created at: ${temp_dir}`);

// Register Fastify plugins
fastify.register(multipart);

// --- Route 1: Batch User Import via CSV ---
fastify.post('/import-users-csv', async (request, reply) => {
    const file_data = await request.file();
    if (!file_data || file_data.mimetype !== 'text/csv') {
        return reply.code(400).send({ error: 'A CSV file is required.' });
    }

    const temp_file_path = path.join(temp_dir, `${generate_uuid()}_${file_data.filename}`);
    
    try {
        await pipeline(file_data.file, fs.createWriteStream(temp_file_path));
        
        const parser = fs.createReadStream(temp_file_path).pipe(parse({ columns: true, trim: true }));
        let imported_count = 0;
        
        for await (const user_record of parser) {
            const new_user = {
                id: generate_uuid(),
                email: user_record.email,
                password_hash: 'default_pass',
                role: user_record.role || 'USER',
                is_active: user_record.is_active === 'true',
                created_at: new Date().toISOString(),
            };
            data_store.users[new_user.id] = new_user;
            imported_count++;
        }
        
        reply.send({ status: 'success', message: `${imported_count} users imported.` });

    } catch (err) {
        request.log.error(err, 'CSV import failed');
        reply.code(500).send({ error: 'Internal server error during file processing.' });
    } finally {
        // Temporary file cleanup
        await fs.remove(temp_file_path);
    }
});

// --- Route 2: Upload and Process User Profile Picture ---
fastify.post('/user/:id/profile-picture', async (request, reply) => {
    const user_id = request.params.id;
    if (!data_store.users[user_id]) {
        return reply.code(404).send({ error: 'User not found.' });
    }

    const image_data = await request.file();
    if (!image_data || !image_data.mimetype.startsWith('image/')) {
        return reply.code(400).send({ error: 'An image file is required.' });
    }

    const uploads_dir = path.join(__dirname, 'public', 'images');
    await fs.ensureDir(uploads_dir);
    const output_path = path.join(uploads_dir, `${user_id}.webp`);

    try {
        const image_processor = sharp().resize(150, 150, { fit: 'cover' }).webp({ quality: 75 });
        await pipeline(image_data.file, image_processor, fs.createWriteStream(output_path));
        
        reply.send({ status: 'success', message: 'Profile picture updated.', path: `/public/images/${user_id}.webp` });
    } catch (err) {
        request.log.error(err, 'Image processing failed');
        reply.code(500).send({ error: 'Could not process image.' });
    }
});

// --- Route 3: Stream a CSV Export of All Posts ---
fastify.get('/export-posts-csv', async (request, reply) => {
    reply.header('Content-Type', 'text/csv');
    reply.header('Content-Disposition', 'attachment; filename="posts.csv"');

    const stringifier = stringify({
        header: true,
        columns: ['id', 'user_id', 'title', 'content', 'status']
    });

    // Pipe the stringifier to the reply stream
    stringifier.pipe(reply.raw);

    // Write data
    for (const post_id in data_store.posts) {
        stringifier.write(data_store.posts[post_id]);
    }
    stringifier.end();
});

// --- Start the server ---
const start_server = async () => {
    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

start_server();