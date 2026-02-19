/*
 * Variation 3: Modular, Feature-based Approach
 *
 * Structure:
 * - index.js (Main server, aggregates feature routes)
 * - features/
 *   - user-import/
 *     - userImport.routes.js
 *     - userImport.controller.js
 *     - userImport.service.js
 *   - post-media/
 *     - postMedia.routes.js
 *     - ...
 *   - data-export/
 *     - dataExport.routes.js
 *     - ...
 *
 * This example simulates this modular structure within a single snippet.
 *
 * To run this:
 * 1. `npm install express multer sharp csv-parser uuid`
 * 2. Create directories: `temp_files/`, `public/post_images/`
 * 3. `node <filename>.js`
 */

const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const csv = require('csv-parser');
const sharp = require('sharp');

// --- Shared Mock DB & Utilities ---
const mockDatabase = {
    users: [],
    posts: [{
        id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
        user_id: 'd8f8f8f8-f8f8-f8f8-f8f8-f8f8f8f8f8f8',
        title: 'Modular Post',
        content: 'This post comes from a modular architecture.',
        status: 'PUBLISHED'
    }]
};

const fileUtils = {
    cleanup: (filePath) => {
        if (filePath) {
            fs.unlink(filePath, (err) => {
                if (err) console.error(`Failed to delete temp file: ${filePath}`, err);
            });
        }
    }
};

// --- Feature: User Import ---
const userImportService = {
    processUserCsvFile: (filePath) => new Promise((resolve, reject) => {
        const importedUsers = [];
        fs.createReadStream(filePath)
            .pipe(csv())
            .on('data', (data) => {
                const user = {
                    id: uuidv4(),
                    email: data.email,
                    password_hash: 'mock_hash', // In real world, hash the password
                    role: data.role || 'USER',
                    is_active: true,
                    created_at: new Date()
                };
                mockDatabase.users.push(user);
                importedUsers.push(user.id);
            })
            .on('end', () => resolve({ count: importedUsers.length, ids: importedUsers }))
            .on('error', reject);
    })
};

const userImportController = {
    handleUserImport: async (req, res, next) => {
        if (!req.file) return res.status(400).send({ error: 'No CSV file provided.' });
        try {
            const result = await userImportService.processUserCsvFile(req.file.path);
            res.status(201).json({ message: 'Users imported successfully', data: result });
        } catch (err) {
            next(err);
        } finally {
            fileUtils.cleanup(req.file.path);
        }
    }
};

const userImportRoutes = express.Router();
const userUpload = multer({ dest: 'temp_files/', fileFilter: (req, file, cb) => {
    file.mimetype === 'text/csv' ? cb(null, true) : cb(new Error('Only CSV files are allowed!'), false);
}});
userImportRoutes.post('/users', userUpload.single('usersCsv'), userImportController.handleUserImport);


// --- Feature: Post Media ---
const postMediaService = {
    processAndSaveImage: async (filePath, postId) => {
        const outputDir = path.join(__dirname, 'public/post_images');
        if (!fs.existsSync(outputDir)) fs.mkdirSync(outputDir, { recursive: true });
        
        const filename = `${postId}.webp`;
        const outputPath = path.join(outputDir, filename);

        await sharp(filePath)
            .resize({ width: 1024 })
            .webp({ quality: 75 })
            .toFile(outputPath);
        
        return `/post_images/${filename}`;
    }
};

const postMediaController = {
    handlePostImageUpload: async (req, res, next) => {
        const { postId } = req.params;
        if (!req.file) return res.status(400).send({ error: 'No image file provided.' });
        if (!mockDatabase.posts.find(p => p.id === postId)) {
            fileUtils.cleanup(req.file.path);
            return res.status(404).send({ error: 'Post not found.' });
        }

        try {
            const publicPath = await postMediaService.processAndSaveImage(req.file.path, postId);
            res.status(200).json({ message: 'Image processed.', path: publicPath });
        } catch (err) {
            next(err);
        } finally {
            fileUtils.cleanup(req.file.path);
        }
    }
};

const postMediaRoutes = express.Router();
const imageUpload = multer({ dest: 'temp_files/', limits: { fileSize: 5 * 1024 * 1024 } }); // 5MB limit
postMediaRoutes.post('/:postId/image', imageUpload.single('postImage'), postMediaController.handlePostImageUpload);


// --- Feature: Data Export ---
const dataExportService = {
    getPostsDataStream: () => {
        const { Readable } = require('stream');
        let csvData = 'id,title,status\n';
        mockDatabase.posts.forEach(p => {
            csvData += `${p.id},"${p.title}",${p.status}\n`;
        });
        return Readable.from(csvData);
    }
};

const dataExportController = {
    streamPostsReport: (req, res, next) => {
        try {
            const stream = dataExportService.getPostsDataStream();
            res.setHeader('Content-Type', 'text/csv');
            res.setHeader('Content-Disposition', 'attachment; filename=posts_report.csv');
            stream.pipe(res);
        } catch (err) {
            next(err);
        }
    }
};

const dataExportRoutes = express.Router();
dataExportRoutes.get('/posts', dataExportController.streamPostsReport);


// --- index.js (Main Server) ---
const app = express();
const PORT = 3003;

app.use('/import', userImportRoutes);
app.use('/posts', postMediaRoutes);
app.use('/export', dataExportRoutes);

// Main error handler
app.use((err, req, res, next) => {
    console.error(`[ERROR] ${err.message}`);
    res.status(err.status || 500).json({
        error: {
            message: err.message || 'An unexpected error occurred.',
        },
    });
});

app.listen(PORT, () => {
    console.log(`Variation 3 (Modular) server running on http://localhost:${PORT}`);
});