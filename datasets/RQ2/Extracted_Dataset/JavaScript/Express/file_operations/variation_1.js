/*
 * Variation 1: Functional, Service-Oriented Approach
 *
 * Structure:
 * - app.js (Server setup, routes)
 * - file.controller.js (Route handlers)
 * - file.service.js (Business logic)
 *
 * This example simulates a multi-file structure within a single snippet.
 *
 * To run this:
 * 1. `npm install express multer sharp csv-parser uuid`
 * 2. Create directories: `uploads/`, `processed_images/`, `reports/`
 * 3. `node <filename>.js`
 */

const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const csv = require('csv-parser');
const sharp = require('sharp');

// --- Mock Database ---
let users = [];
let posts = [{
    id: uuidv4(),
    user_id: uuidv4(),
    title: 'First Post',
    content: 'This is the content of the first post.',
    status: 'PUBLISHED'
}];

// --- file.service.js ---
const fileService = {
    processUserCsv: (filePath) => {
        return new Promise((resolve, reject) => {
            const newUsers = [];
            fs.createReadStream(filePath)
                .pipe(csv())
                .on('data', (row) => {
                    // Basic validation
                    if (row.email && row.password_hash) {
                        const newUser = {
                            id: uuidv4(),
                            email: row.email,
                            password_hash: row.password_hash,
                            role: row.role === 'ADMIN' ? 'ADMIN' : 'USER',
                            is_active: row.is_active ? row.is_active === 'true' : true,
                            created_at: new Date().toISOString()
                        };
                        users.push(newUser);
                        newUsers.push(newUser);
                    }
                })
                .on('end', () => {
                    console.log('CSV file successfully processed.');
                    resolve({
                        message: `${newUsers.length} users imported successfully.`,
                        importedCount: newUsers.length
                    });
                })
                .on('error', (error) => {
                    reject(error);
                });
        });
    },

    resizePostImage: async (filePath, postId) => {
        const outputDir = path.join(__dirname, 'processed_images');
        if (!fs.existsSync(outputDir)) fs.mkdirSync(outputDir, { recursive: true });

        const outputFilename = `${postId}-${Date.now()}.webp`;
        const outputPath = path.join(outputDir, outputFilename);

        await sharp(filePath)
            .resize(800, 600, { fit: 'inside' })
            .toFormat('webp')
            .toFile(outputPath);

        // In a real app, you'd update the Post entity with this image path
        console.log(`Image for post ${postId} processed and saved to ${outputPath}`);
        return { imagePath: `/images/${outputFilename}` };
    },

    generatePostsCsvStream: () => {
        const reportPath = path.join(__dirname, 'reports', `posts-report-${Date.now()}.csv`);
        const writer = fs.createWriteStream(reportPath);

        // Write headers
        writer.write('id,user_id,title,status\n');

        // Write post data
        posts.forEach(post => {
            writer.write(`${post.id},${post.user_id},"${post.title}",${post.status}\n`);
        });
        writer.end();

        return fs.createReadStream(reportPath);
    }
};

// --- file.controller.js ---
const fileController = {
    importUsers: async (req, res, next) => {
        if (!req.file) {
            return res.status(400).json({ error: 'No file uploaded.' });
        }
        try {
            const result = await fileService.processUserCsv(req.file.path);
            res.status(201).json(result);
        } catch (error) {
            next(error);
        } finally {
            // Temporary file management
            fs.unlink(req.file.path, (err) => {
                if (err) console.error('Error deleting temp file:', err);
            });
        }
    },

    uploadPostImage: async (req, res, next) => {
        if (!req.file) {
            return res.status(400).json({ error: 'No image uploaded.' });
        }
        const { postId } = req.params;
        // In a real app, you'd validate that the post exists and the user has permission
        if (!posts.find(p => p.id === postId)) {
             return res.status(404).json({ error: 'Post not found.' });
        }

        try {
            const result = await fileService.resizePostImage(req.file.path, postId);
            res.status(200).json(result);
        } catch (error) {
            next(error);
        } finally {
            fs.unlink(req.file.path, (err) => {
                if (err) console.error('Error deleting temp image:', err);
            });
        }
    },

    downloadPostsReport: (req, res, next) => {
        try {
            const fileStream = fileService.generatePostsCsvStream();
            res.setHeader('Content-Type', 'text/csv');
            res.setHeader('Content-Disposition', 'attachment; filename="posts_report.csv"');
            
            fileStream.pipe(res);

            fileStream.on('close', () => {
                // Cleanup the generated report file
                fs.unlink(fileStream.path, (err) => {
                    if (err) console.error('Error deleting temp report file:', err.message);
                });
            });

            fileStream.on('error', (err) => {
                next(err);
            });

        } catch (error) {
            next(error);
        }
    }
};

// --- app.js ---
const app = express();
const PORT = 3001;

// Setup Multer for temporary file storage
const upload = multer({ dest: 'uploads/' });

// Mock Authentication Middleware
const mockAuth = (req, res, next) => {
    req.user = { id: uuidv4(), role: 'ADMIN', is_active: true };
    next();
};

// Routes
app.post('/users/import', mockAuth, upload.single('userFile'), fileController.importUsers);
app.post('/posts/:postId/image', mockAuth, upload.single('postImage'), fileController.uploadPostImage);
app.get('/posts/export', mockAuth, fileController.downloadPostsReport);

// Global Error Handler
app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).send('Something broke!');
});

app.listen(PORT, () => {
    console.log(`Variation 1 (Functional) server running on http://localhost:${PORT}`);
});