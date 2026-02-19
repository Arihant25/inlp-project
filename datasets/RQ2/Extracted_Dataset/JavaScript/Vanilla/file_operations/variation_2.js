<script>
// Variation 2: Class-based / OOP Style
// This approach encapsulates file handling logic within a `FileOperationService` class.
// It promotes code organization, reusability, and state management for complex operations.
// Naming convention: PascalCase for classes, camelCase for methods and properties.

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { Writable } = require('stream');
const { randomUUID } = require('crypto');

// --- Mock Datastore ---
const Datastore = {
    Users: new Map(),
    Posts: new Map(),
};

// --- Domain Models (for context) ---
/**
 * @typedef {'ADMIN' | 'USER'} UserRole
 * @typedef {'DRAFT' | 'PUBLISHED'} PostStatus
 */

class FileOperationService {
    constructor() {
        this.tempDirectory = path.join(os.tmpdir(), 'app-v2-temp');
        this._initializeTempDir();
    }

    /**
     * Ensures the temporary directory for file operations exists.
     * @private
     */
    _initializeTempDir() {
        if (!fs.existsSync(this.tempDirectory)) {
            fs.mkdirSync(this.tempDirectory, { recursive: true });
        }
    }

    /**
     * Generates a unique temporary file path.
     * @param {string} extension - Optional file extension.
     * @returns {string} The full path for a new temporary file.
     */
    _createTempFilePath(extension = '.tmp') {
        const uniqueId = randomUUID();
        return path.join(this.tempDirectory, `${uniqueId}${extension}`);
    }

    /**
     * Manually parses a multipart/form-data request body.
     * @param {http.IncomingMessage} req - The HTTP request object.
     * @returns {Promise<{fields: Object, files: Object}>} Parsed form data.
     */
    parseMultipartRequest(req) {
        return new Promise((resolve, reject) => {
            const contentType = req.headers['content-type'];
            if (!contentType || !contentType.includes('multipart/form-data')) {
                return reject(new Error('Request is not multipart/form-data.'));
            }

            const boundary = Buffer.from(`--${contentType.split('=')[1]}`);
            const chunks = [];

            req.on('data', chunk => chunks.push(chunk));
            req.on('end', () => {
                try {
                    const body = Buffer.concat(chunks);
                    const parts = this._splitBuffer(body, boundary).slice(1, -1); // Remove preamble and epilogue

                    const result = { fields: {}, files: {} };

                    for (const part of parts) {
                        const headerEnd = part.indexOf('\r\n\r\n');
                        if (headerEnd === -1) continue;

                        const headers = part.slice(0, headerEnd).toString();
                        const content = part.slice(headerEnd + 4, -2); // Remove trailing \r\n

                        const nameMatch = headers.match(/name="([^"]+)"/);
                        if (!nameMatch) continue;
                        const name = nameMatch[1];

                        const filenameMatch = headers.match(/filename="([^"]+)"/);
                        if (filenameMatch) {
                            const filename = filenameMatch[1];
                            const typeMatch = headers.match(/Content-Type: (.+)/);
                            const contentType = typeMatch ? typeMatch[1].trim() : 'application/octet-stream';
                            result.files[name] = { filename, contentType, content };
                        } else {
                            result.fields[name] = content.toString();
                        }
                    }
                    resolve(result);
                } catch (err) {
                    reject(err);
                }
            });
            req.on('error', reject);
        });
    }

    /**
     * Helper to split a buffer by a delimiter buffer.
     * @param {Buffer} buffer - The buffer to split.
     * @param {Buffer} delimiter - The delimiter.
     * @returns {Buffer[]} An array of buffer parts.
     * @private
     */
    _splitBuffer(buffer, delimiter) {
        const parts = [];
        let start = 0;
        let end = buffer.indexOf(delimiter, start);
        while (end !== -1) {
            parts.push(buffer.slice(start, end));
            start = end + delimiter.length;
            end = buffer.indexOf(delimiter, start);
        }
        parts.push(buffer.slice(start));
        return parts;
    }

    /**
     * Parses CSV data from a buffer.
     * @param {Buffer} csvBuffer - The buffer containing CSV data.
     * @returns {Array<Object>} Array of objects from CSV.
     */
    parseCsv(csvBuffer) {
        const lines = csvBuffer.toString('utf8').trim().split('\n');
        const headers = lines.shift().trim().split(',');
        return lines.map(line => {
            const values = line.trim().split(',');
            return headers.reduce((obj, header, index) => {
                obj[header] = values[index];
                return obj;
            }, {});
        });
    }

    /**
     * SIMULATED image processing.
     * In production, use a dedicated library. This is a placeholder.
     * @param {Buffer} imageBuffer - The original image data.
     * @returns {Promise<Buffer>} The processed image data.
     */
    async processImage(imageBuffer) {
        console.log(`Simulating image processing. Original size: ${imageBuffer.length}`);
        // Simulate an async operation like resizing
        return new Promise(resolve => {
            setTimeout(() => {
                // Simulate reduction in size
                const processedBuffer = imageBuffer.slice(0, imageBuffer.length * 0.75);
                console.log(`Processed size: ${processedBuffer.length}`);
                resolve(processedBuffer);
            }, 50);
        });
    }

    /**
     * Streams a file for download.
     * @param {http.ServerResponse} res - The HTTP response object.
     * @param {string} filePath - Path of the file to stream.
     * @param {string} clientFileName - The name for the downloaded file.
     */
    streamDownload(res, filePath, clientFileName) {
        const readStream = fs.createReadStream(filePath);
        readStream.on('error', (err) => {
            if (err.code === 'ENOENT') {
                res.writeHead(404, { 'Content-Type': 'text/plain' });
                res.end('File not found.');
            } else {
                res.writeHead(500, { 'Content-Type': 'text/plain' });
                res.end('Server error while reading file.');
            }
        });

        readStream.on('open', () => {
            const stats = fs.statSync(filePath);
            res.writeHead(200, {
                'Content-Disposition': `attachment; filename="${clientFileName}"`,
                'Content-Type': 'application/octet-stream',
                'Content-Length': stats.size
            });
            readStream.pipe(res);
        });
    }

    /**
     * Deletes a file, typically a temporary one.
     * @param {string} filePath - Path of the file to delete.
     */
    cleanupFile(filePath) {
        fs.unlink(filePath, (err) => {
            if (err) {
                console.error(`Failed to clean up file: ${filePath}`, err);
            } else {
                console.log(`Successfully cleaned up file: ${filePath}`);
            }
        });
    }
}

// --- HTTP Server using the Service Class ---
const fileService = new FileOperationService();

const server = http.createServer(async (req, res) => {
    try {
        if (req.url === '/upload/users' && req.method === 'POST') {
            const { files } = await fileService.parseMultipartRequest(req);
            if (!files.users_csv) throw new Error('File "users_csv" not provided.');

            const usersData = fileService.parseCsv(files.users_csv.content);
            usersData.forEach(user => {
                const id = randomUUID();
                Datastore.Users.set(id, { id, ...user, is_active: user.is_active === 'true', created_at: new Date() });
            });
            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: `Processed ${usersData.length} users.` }));

        } else if (req.url === '/upload/post-image' && req.method === 'POST') {
            const { fields, files } = await fileService.parseMultipartRequest(req);
            if (!files.image || !fields.user_id || !fields.title) {
                throw new Error('Missing required fields: image, user_id, title.');
            }

            const processedImage = await fileService.processImage(files.image.content);
            const tempPath = fileService._createTempFilePath(path.extname(files.image.filename));
            await fs.promises.writeFile(tempPath, processedImage);

            const postId = randomUUID();
            Datastore.Posts.set(postId, {
                id: postId,
                user_id: fields.user_id,
                title: fields.title,
                content: `Image located at ${tempPath}`,
                status: 'PUBLISHED'
            });

            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: 'Post created with image.', postId }));
            // Schedule cleanup
            setTimeout(() => fileService.cleanupFile(tempPath), 60000);

        } else if (req.url.startsWith('/download/post/') && req.method === 'GET') {
            const postId = req.url.split('/').pop();
            const post = Datastore.Posts.get(postId);
            if (!post) {
                res.writeHead(404).end('Post not found.');
                return;
            }
            const filePath = post.content.split(' located at ')[1];
            fileService.streamDownload(res, filePath, `post-${postId}-image.jpg`);

        } else {
            res.writeHead(404).end('Not Found');
        }
    } catch (error) {
        console.error('Request Error:', error.message);
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end(`Internal Server Error: ${error.message}`);
    }
});

const PORT = 3001;
// server.listen(PORT, () => {
//     console.log(`OOP Server running on http://localhost:${PORT}`);
// });

console.log('Variation 2: Class-based / OOP Style loaded.');
// To run: uncomment server.listen and run the script.
// Example CURL commands:
// curl -X POST -F "users_csv=@/path/to/your/users.csv" http://localhost:3001/upload/users
// curl -X POST -F "title=OOP Post" -F "user_id=some-uuid" -F "image=@/path/to/your/image.png" http://localhost:3001/upload/post-image
</script>