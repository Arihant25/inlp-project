/*
 * Variation 4: Minimalist, Single-File "Startup" Style
 *
 * Structure:
 * - Everything in a single index.js file.
 * - Helper functions defined at the top.
 * - Routes defined directly on the app object.
 * - Uses snake_case for variables and functions.
 *
 * This style is common for prototypes, microservices, or serverless functions.
 *
 * To run this:
 * 1. `npm install express multer sharp csv-parser uuid`
 * 2. Create directories: `_temp/`, `_public/img/`
 * 3. `node <filename>.js`
 */

const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const csv_parser = require('csv-parser');
const sharp = require('sharp');
const { Readable } = require('stream');

// --- App Setup ---
const app = express();
const PORT = 3004;
const TEMP_DIR = '_temp';
const PUBLIC_IMG_DIR = '_public/img';

// Ensure directories exist
fs.mkdirSync(TEMP_DIR, { recursive: true });
fs.mkdirSync(PUBLIC_IMG_DIR, { recursive: true });

// --- Mock Data Store ---
const USERS_DB = new Map();
const POSTS_DB = new Map();
// Seed with one post
const initial_post_id = uuidv4();
POSTS_DB.set(initial_post_id, {
    id: initial_post_id,
    user_id: uuidv4(),
    title: 'Minimalist Post',
    content: 'A simple post.',
    status: 'PUBLISHED'
});

// --- Helper Functions ---
const cleanup_file = (file_path) => {
    if (!file_path) return;
    fs.unlink(file_path, (err) => {
        if (err) console.error(`CLEANUP FAILED for ${file_path}:`, err);
        else console.log(`Cleaned up ${file_path}`);
    });
};

const process_image = async (input_path, post_id) => {
    const output_filename = `post-${post_id}-${Date.now()}.jpg`;
    const output_path = path.join(PUBLIC_IMG_DIR, output_filename);
    await sharp(input_path)
        .resize(500, 500, { fit: 'cover' })
        .jpeg({ quality: 90 })
        .toFile(output_path);
    return `/img/${output_filename}`;
};

// --- Middleware ---
const upload_config = multer({ dest: TEMP_DIR });

// Mock auth: adds a user to the request
const inject_user = (req, res, next) => {
    req.user = { id: 'user-123', role: 'ADMIN' };
    next();
};

// --- Routes ---

// 1. File Upload: Bulk import users from a CSV
app.post('/users/bulk-add', inject_user, upload_config.single('user_file'), (req, res, next) => {
    if (req.user.role !== 'ADMIN') {
        cleanup_file(req.file?.path);
        return res.status(403).json({ error: 'Permission denied' });
    }
    if (!req.file) {
        return res.status(400).json({ error: 'No file provided' });
    }

    const results = [];
    fs.createReadStream(req.file.path)
        .pipe(csv_parser())
        .on('data', (data) => {
            const new_id = uuidv4();
            const new_user = {
                id: new_id,
                email: data.email,
                password_hash: `hashed_${data.password}`,
                role: 'USER',
                is_active: true,
                created_at: new Date().toISOString()
            };
            USERS_DB.set(new_id, new_user);
            results.push(new_id);
        })
        .on('end', () => {
            cleanup_file(req.file.path);
            res.status(201).json({ message: `Imported ${results.length} users.`, user_ids: results });
        })
        .on('error', (err) => {
            cleanup_file(req.file.path);
            next(err);
        });
});

// 2. Image Processing: Upload an image for a post
app.post('/posts/:post_id/image', inject_user, upload_config.single('post_image'), async (req, res, next) => {
    const { post_id } = req.params;
    if (!req.file) {
        return res.status(400).json({ error: 'No image provided' });
    }
    if (!POSTS_DB.has(post_id)) {
        cleanup_file(req.file.path);
        return res.status(404).json({ error: 'Post not found' });
    }

    try {
        const image_url = await process_image(req.file.path, post_id);
        // Update post in DB
        const post = POSTS_DB.get(post_id);
        post.image_url = image_url;
        POSTS_DB.set(post_id, post);
        res.json({ message: 'Image uploaded and processed', url: image_url });
    } catch (err) {
        next(err);
    } finally {
        cleanup_file(req.file.path);
    }
});

// 3. File Download with Streaming: Get a CSV report of all posts
app.get('/posts/report', inject_user, (req, res) => {
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'attachment; filename="posts_report.csv"');

    const stream = new Readable();
    stream._read = () => {}; // No-op
    stream.pipe(res);

    // Write header
    stream.push('id,user_id,title,status\n');

    // Write data from our "DB"
    for (const post of POSTS_DB.values()) {
        stream.push(`${post.id},${post.user_id},"${post.title}",${post.status}\n`);
    }

    // End the stream
    stream.push(null);
});

// --- Final Error Handler ---
app.use((err, req, res, next) => {
    console.error(err);
    res.status(500).json({ error: 'An internal server error occurred.' });
});

// --- Start Server ---
app.listen(PORT, () => {
    console.log(`Variation 4 (Minimalist) server running on http://localhost:${PORT}`);
});