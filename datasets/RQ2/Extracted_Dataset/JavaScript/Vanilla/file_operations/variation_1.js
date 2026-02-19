<script>
// Variation 1: Functional / Procedural Style
// This approach uses a collection of standalone, stateless functions.
// It's straightforward and easy to reason about for smaller, focused tasks.
// Naming convention: camelCase for functions and variables.

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { Writable } = require('stream');
const { randomUUID } = require('crypto');

// --- Mock Database ---
const DB = {
    users: new Map(),
    posts: new Map(),
};

// --- Domain Models (for context) ---
/**
 * @typedef {'ADMIN' | 'USER'} UserRole
 * @typedef {'DRAFT' | 'PUBLISHED'} PostStatus
 *
 * @typedef {object} User
 * @property {string} id
 * @property {string} email
 * @property {string} password_hash
 * @property {UserRole} role
 * @property {boolean} is_active
 * @property {Date} created_at
 *
 * @typedef {object} Post
 * @property {string} id
 * @property {string} user_id
 * @property {string} title
 * @property {string} content
 * @property {PostStatus} status
 */

// --- Core Functionality: File Operations ---

/**
 * Creates a temporary file path and ensures the directory exists.
 * @param {string} prefix - A prefix for the temporary file name.
 * @returns {string} The full path to the temporary file.
 */
const createTempFile = (prefix = 'temp_') => {
    const tempDir = path.join(os.tmpdir(), 'my-app-uploads');
    if (!fs.existsSync(tempDir)) {
        fs.mkdirSync(tempDir, { recursive: true });
    }
    const tempFileName = prefix + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
    return path.join(tempDir, tempFileName);
};

/**
 * Cleans up (deletes) a file.
 * @param {string} filePath - The path to the file to delete.
 */
const cleanupTempFile = (filePath) => {
    fs.unlink(filePath, (err) => {
        if (err) console.error(`Failed to cleanup temp file: ${filePath}`, err);
        else console.log(`Cleaned up temp file: ${filePath}`);
    });
};

/**
 * Parses a CSV buffer into an array of objects.
 * Assumes the first row is the header.
 * @param {Buffer} buffer - The CSV data as a buffer.
 * @returns {Array<Object>} An array of objects representing the CSV rows.
 */
const parseCsvContent = (buffer) => {
    const content = buffer.toString('utf-8');
    const lines = content.trim().split(/\r?\n/);
    if (lines.length < 2) return [];

    const headers = lines[0].split(',');
    const data = [];

    for (let i = 1; i < lines.length; i++) {
        const values = lines[i].split(',');
        if (values.length !== headers.length) continue;
        const entry = {};
        for (let j = 0; j < headers.length; j++) {
            entry[headers[j].trim()] = values[j].trim();
        }
        data.push(entry);
    }
    return data;
};

/**
 * SIMULATED image resizing.
 * In a real Node.js application, this would require a library like 'sharp' or 'jimp'.
 * This function simulates the process by truncating the buffer.
 * @param {Buffer} imageBuffer - The original image buffer.
 * @param {number} targetWidth - The desired width (for simulation).
 * @returns {Promise<Buffer>} A promise that resolves with the "resized" image buffer.
 */
const processImage = (imageBuffer, targetWidth = 150) => {
    return new Promise((resolve) => {
        console.log(`Simulating image resize to width ${targetWidth}px...`);
        // This is a placeholder. Real resizing is a complex binary operation.
        // We'll just take a slice of the buffer to simulate a smaller file.
        const simulatedResizedBuffer = imageBuffer.slice(0, Math.floor(imageBuffer.length / 2));
        console.log(`Original size: ${imageBuffer.length} bytes, Resized: ${simulatedResizedBuffer.length} bytes`);
        resolve(simulatedResizedBuffer);
    });
};

/**
 * Streams a file from the filesystem to the HTTP response.
 * @param {http.ServerResponse} res - The response object.
 * @param {string} filePath - The path to the file to download.
 * @param {string} downloadName - The name the file should have when downloaded.
 */
const streamFileDownload = (res, filePath, downloadName) => {
    if (!fs.existsSync(filePath)) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('File not found.');
        return;
    }

    const stat = fs.statSync(filePath);
    res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${downloadName}"`,
    });

    const readStream = fs.createReadStream(filePath);
    readStream.pipe(res);
};

/**
 * Manually parses a multipart/form-data request body.
 * @param {http.IncomingMessage} req - The request object.
 * @returns {Promise<{fields: Object, files: Object}>} A promise resolving with parsed fields and files.
 */
const parseMultipartForm = (req) => {
    return new Promise((resolve, reject) => {
        const contentType = req.headers['content-type'];
        if (!contentType || !contentType.includes('multipart/form-data')) {
            return reject(new Error('Invalid content-type for multipart form.'));
        }

        const boundaryMatch = contentType.match(/boundary=(.+)/);
        if (!boundaryMatch) return reject(new Error('No boundary in multipart form.'));
        const boundary = `--${boundaryMatch[1]}`;
        const finalBoundary = `${boundary}--`;

        const fields = {};
        const files = {};
        let currentPart = null;
        let buffer = Buffer.alloc(0);

        const parser = new Writable({
            write(chunk, encoding, callback) {
                buffer = Buffer.concat([buffer, chunk]);
                let boundaryIndex;

                while ((boundaryIndex = buffer.indexOf(boundary)) !== -1) {
                    const partData = buffer.slice(0, boundaryIndex);
                    buffer = buffer.slice(boundaryIndex + boundary.length);

                    if (partData.length > 0) {
                        if (currentPart) {
                            // Finish previous part
                            const contentEnd = partData.lastIndexOf('\r\n');
                            currentPart.data = Buffer.concat([currentPart.data, partData.slice(0, contentEnd)]);
                            if (currentPart.filename) {
                                files[currentPart.name] = currentPart;
                            } else {
                                fields[currentPart.name] = currentPart.data.toString();
                            }
                        }
                    }

                    // Start new part
                    const headerEndIndex = buffer.indexOf('\r\n\r\n');
                    if (headerEndIndex === -1) break;

                    const headerData = buffer.slice(0, headerEndIndex).toString();
                    buffer = buffer.slice(headerEndIndex + 4);

                    const nameMatch = headerData.match(/name="([^"]+)"/);
                    const filenameMatch = headerData.match(/filename="([^"]+)"/);
                    const typeMatch = headerData.match(/Content-Type: (.+)/);

                    if (nameMatch) {
                        currentPart = {
                            name: nameMatch[1],
                            filename: filenameMatch ? filenameMatch[1] : null,
                            contentType: typeMatch ? typeMatch[1].trim() : 'application/octet-stream',
                            data: Buffer.alloc(0),
                        };
                    }
                }
                callback();
            }
        });

        req.pipe(parser);

        parser.on('finish', () => {
             if (currentPart) {
                const finalBoundaryIndex = buffer.indexOf(finalBoundary);
                if (finalBoundaryIndex !== -1) {
                    const contentEnd = buffer.slice(0, finalBoundaryIndex).lastIndexOf('\r\n');
                    currentPart.data = Buffer.concat([currentPart.data, buffer.slice(0, contentEnd)]);
                    if (currentPart.filename) {
                        files[currentPart.name] = currentPart;
                    } else {
                        fields[currentPart.name] = currentPart.data.toString();
                    }
                }
            }
            resolve({ fields, files });
        });

        parser.on('error', reject);
    });
};


// --- HTTP Server & Request Handling ---
const server = http.createServer(async (req, res) => {
    try {
        if (req.url === '/users/upload-csv' && req.method === 'POST') {
            const { files } = await parseMultipartForm(req);
            const csvFile = files['user_data'];
            if (!csvFile) {
                res.writeHead(400).end('CSV file "user_data" is required.');
                return;
            }

            const users = parseCsvContent(csvFile.data);
            users.forEach(u => {
                const id = randomUUID();
                DB.users.set(id, { ...u, id, created_at: new Date() });
            });

            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: `${users.length} users created.`, userIds: Array.from(DB.users.keys()) }));

        } else if (req.url === '/posts/upload-image' && req.method === 'POST') {
            const { fields, files } = await parseMultipartForm(req);
            const imageFile = files['post_image'];
            if (!imageFile || !fields.title || !fields.user_id) {
                res.writeHead(400).end('Fields "title", "user_id" and file "post_image" are required.');
                return;
            }

            const resizedBuffer = await processImage(imageFile.data);
            const tempFilePath = createTempFile('postimg_');
            fs.writeFileSync(tempFilePath, resizedBuffer);

            const postId = randomUUID();
            const newPost = {
                id: postId,
                user_id: fields.user_id,
                title: fields.title,
                content: `Image stored at ${tempFilePath}`,
                status: 'DRAFT'
            };
            DB.posts.set(postId, newPost);

            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: 'Post with image created.', post: newPost }));
            // In a real app, you might not want to cleanup immediately
            setTimeout(() => cleanupTempFile(tempFilePath), 30000);

        } else if (req.url.startsWith('/download/') && req.method === 'GET') {
            const filename = req.url.split('/')[2];
            // In a real app, you'd have a secure way to map requests to file paths.
            // This is a simplified example.
            const mockFilePath = path.join(os.tmpdir(), 'my-app-uploads', filename);
            streamFileDownload(res, mockFilePath, 'downloaded-file.jpg');

        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('Not Found');
        }
    } catch (error) {
        console.error('Server Error:', error);
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('Internal Server Error');
    }
});

const PORT = 3000;
// server.listen(PORT, () => {
//     console.log(`Functional Server running on http://localhost:${PORT}`);
// });

console.log('Variation 1: Functional/Procedural Style loaded.');
// To run: uncomment server.listen and run the script.
// Example CURL commands:
// curl -X POST -F "user_data=@/path/to/your/users.csv" http://localhost:3000/users/upload-csv
// curl -X POST -F "title=My Post" -F "user_id=some-uuid" -F "post_image=@/path/to/your/image.jpg" http://localhost:3000/posts/upload-image
// curl -X GET http://localhost:3000/download/postimg_... > downloaded.jpg

</script>