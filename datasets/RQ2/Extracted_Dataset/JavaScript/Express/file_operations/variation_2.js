/*
 * Variation 2: Class-based, OOP Approach
 *
 * Structure:
 * - server.js (Main entry point)
 * - routes.js (Route definitions)
 * - FileProcessor.js (Class encapsulating all logic)
 *
 * This example simulates a multi-file structure within a single snippet.
 *
 * To run this:
 * 1. `npm install express multer sharp xlsx uuid`
 * 2. Create directories: `temp/uploads/`, `public/images/`
 * 3. `node <filename>.js`
 */

const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const xlsx = require('xlsx');
const sharp = require('sharp');

// --- Mock Database (could be passed via constructor) ---
const DB = {
    Users: [],
    Posts: [{
        id: uuidv4(),
        user_id: uuidv4(),
        title: 'OOP Post',
        content: 'Content from the class-based approach.',
        status: 'PUBLISHED'
    }]
};

// --- FileProcessor.js ---
class FileProcessor {
    constructor(database) {
        this.db = database;
        this._tempDir = path.join(__dirname, 'temp/uploads');
        this._publicDir = path.join(__dirname, 'public/images');
        this._ensureDirs();
    }

    _ensureDirs() {
        if (!fs.existsSync(this._tempDir)) fs.mkdirSync(this._tempDir, { recursive: true });
        if (!fs.existsSync(this._publicDir)) fs.mkdirSync(this._publicDir, { recursive: true });
    }

    async _cleanupTempFile(filePath) {
        try {
            await fs.promises.unlink(filePath);
        } catch (err) {
            console.error(`Failed to cleanup temp file: ${filePath}`, err);
        }
    }

    async importUsers(req, res, next) {
        if (!req.file) {
            return res.status(400).json({ message: 'File is required.' });
        }

        const filePath = req.file.path;
        try {
            let newUsers;
            if (req.file.mimetype === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') {
                newUsers = this._parseXlsx(filePath);
            } else {
                return res.status(415).json({ message: 'Unsupported file type. Please upload an XLSX file.' });
            }
            
            this.db.Users.push(...newUsers);
            res.status(201).json({
                message: `Successfully imported ${newUsers.length} users.`,
                totalUsers: this.db.Users.length
            });
        } catch (error) {
            next(error);
        } finally {
            this._cleanupTempFile(filePath);
        }
    }

    _parseXlsx(filePath) {
        const workbook = xlsx.readFile(filePath);
        const sheetName = workbook.SheetNames[0];
        const sheet = workbook.Sheets[sheetName];
        const data = xlsx.utils.sheet_to_json(sheet);

        return data.map(row => ({
            id: uuidv4(),
            email: row.email,
            password_hash: row.password_hash,
            role: ['ADMIN', 'USER'].includes(row.role) ? row.role : 'USER',
            is_active: row.is_active === false ? false : true,
            created_at: new Date().toISOString()
        }));
    }

    async processPostImage(req, res, next) {
        if (!req.file) {
            return res.status(400).json({ message: 'Image file is required.' });
        }
        const { id: postId } = req.params;
        const post = this.db.Posts.find(p => p.id === postId);
        if (!post) {
            this._cleanupTempFile(req.file.path);
            return res.status(404).json({ message: 'Post not found.' });
        }

        const outputFilename = `post_${postId}_${Date.now()}.jpeg`;
        const outputPath = path.join(this._publicDir, outputFilename);

        try {
            await sharp(req.file.path)
                .resize(1200)
                .jpeg({ quality: 80 })
                .toFile(outputPath);
            
            // In a real app, update post.imageUrl = `/images/${outputFilename}`
            res.status(200).json({ imageUrl: `/images/${outputFilename}` });
        } catch (error) {
            next(error);
        } finally {
            this._cleanupTempFile(req.file.path);
        }
    }

    exportPosts(req, res, next) {
        try {
            const headers = [
                { id: 'id', title: 'ID' },
                { id: 'user_id', title: 'AuthorID' },
                { id: 'title', title: 'Title' },
                { id: 'status', title: 'Status' }
            ];
            
            const data = this.db.Posts.map(p => `${p.id},${p.user_id},"${p.title.replace(/"/g, '""')}",${p.status}`).join('\n');
            const csvContent = headers.map(h => h.title).join(',') + '\n' + data;

            res.setHeader('Content-Type', 'text/csv');
            res.setHeader('Content-Disposition', 'attachment; filename="posts_export.csv"');
            res.status(200).end(csvContent);
        } catch (error) {
            next(error);
        }
    }
}

// --- routes.js ---
const fileRouter = express.Router();
const fileProcessor = new FileProcessor(DB);

// Multer config for this router
const storage = multer.diskStorage({
    destination: (req, file, cb) => cb(null, 'temp/uploads/'),
    filename: (req, file, cb) => cb(null, `${uuidv4()}-${file.originalname}`)
});
const uploadMiddleware = multer({ storage });

// Mock Admin Check Middleware
const isAdmin = (req, res, next) => {
    // In a real app, this would check req.user.role from a JWT or session
    const userIsAdmin = true; 
    if (userIsAdmin) {
        next();
    } else {
        res.status(403).json({ message: 'Forbidden' });
    }
};

fileRouter.post(
    '/users/import',
    isAdmin,
    uploadMiddleware.single('userData'),
    (req, res, next) => fileProcessor.importUsers(req, res, next)
);

fileRouter.put(
    '/posts/:id/image',
    uploadMiddleware.single('postImage'),
    (req, res, next) => fileProcessor.processPostImage(req, res, next)
);

fileRouter.get(
    '/posts/export',
    isAdmin,
    (req, res, next) => fileProcessor.exportPosts(req, res, next)
);

// --- server.js ---
const app = express();
const PORT = 3002;

app.use(express.json());
app.use('/api', fileRouter);

// Centralized error handler
app.use((err, req, res, next) => {
    console.error('An error occurred:', err);
    res.status(500).json({ error: 'Internal Server Error' });
});

app.listen(PORT, () => {
    console.log(`Variation 2 (OOP) server running on http://localhost:${PORT}`);
});