<script>
// Variation 3: Module Pattern with Closures (IIFE)
// This classic JavaScript pattern uses an Immediately Invoked Function Expression (IIFE)
// to create a module with private state and public methods, avoiding global scope pollution.
// Naming convention: _privateVariable for private members, publicMethod for public ones.

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { randomUUID } = require('crypto');

const FileOpsModule = (() => {
    // --- Private State & Helpers ---
    const _TEMP_DIR = path.join(os.tmpdir(), 'app-v3-files');
    const _MOCK_DB = { users: new Map(), posts: new Map() };

    /**
     * Ensures the temporary directory exists.
     * @private
     */
    const _setup = () => {
        if (!fs.existsSync(_TEMP_DIR)) {
            fs.mkdirSync(_TEMP_DIR, { recursive: true });
            console.log(`Module temp directory created at: ${_TEMP_DIR}`);
        }
    };
    _setup();

    /**
     * A very basic manual multipart/form-data parser.
     * @param {http.IncomingMessage} req
     * @returns {Promise<{fields: {}, files: {}}>}
     * @private
     */
    const _parseMultipartBody = (req) => new Promise((resolve, reject) => {
        const boundary = req.headers['content-type'].split('boundary=')[1];
        if (!boundary) return reject(new Error('Missing form boundary'));

        const boundaryBuffer = Buffer.from(`--${boundary}`);
        const bodyChunks = [];

        req.on('data', chunk => bodyChunks.push(chunk));
        req.on('end', () => {
            const body = Buffer.concat(bodyChunks);
            const parts = [];
            let currentPos = 0;
            let boundaryIndex = body.indexOf(boundaryBuffer, currentPos);

            while (boundaryIndex !== -1) {
                parts.push(body.slice(currentPos, boundaryIndex));
                currentPos = boundaryIndex + boundaryBuffer.length;
                boundaryIndex = body.indexOf(boundaryBuffer, currentPos);
            }

            const parsed = { fields: {}, files: {} };
            // Skip first part (it's empty) and last part (it's the final boundary)
            for (const part of parts.slice(1)) {
                // The part starts with \r\n, headers, \r\n\r\n, content, and ends with \r\n
                const headerEnd = part.indexOf('\r\n\r\n');
                if (headerEnd === -1) continue;

                const headers = part.slice(2, headerEnd).toString(); // Slice off leading \r\n
                const content = part.slice(headerEnd + 4, -2); // Slice off \r\n\r\n and trailing \r\n

                const nameMatch = headers.match(/name="([^"]+)"/);
                if (!nameMatch) continue;
                const name = nameMatch[1];

                const filenameMatch = headers.match(/filename="([^"]+)"/);
                if (filenameMatch) {
                    parsed.files[name] = {
                        filename: filenameMatch[1],
                        content: content
                    };
                } else {
                    parsed.fields[name] = content.toString();
                }
            }
            resolve(parsed);
        });
        req.on('error', reject);
    });

    // --- Public API ---
    return {
        /**
         * Handles the upload and processing of a user CSV file.
         * @param {Buffer} fileContent - The content of the CSV file.
         * @returns {{count: number, user_ids: string[]}}
         */
        processUserCsvUpload: (fileContent) => {
            const lines = fileContent.toString().split('\n').filter(l => l.trim() !== '');
            const headers = lines.shift().split(',').map(h => h.trim());
            const newUsers = [];

            lines.forEach(line => {
                const values = line.split(',');
                const userRecord = headers.reduce((obj, header, i) => {
                    obj[header] = values[i].trim();
                    return obj;
                }, {});
                
                const id = randomUUID();
                const user = {
                    id,
                    email: userRecord.email,
                    password_hash: 'mock_hash_' + id,
                    role: userRecord.role || 'USER',
                    is_active: userRecord.is_active === 'true',
                    created_at: new Date()
                };
                _MOCK_DB.users.set(id, user);
                newUsers.push(id);
            });

            return { count: newUsers.length, user_ids: newUsers };
        },

        /**
         * Handles image upload, "resizing", and temporary storage.
         * @param {Buffer} imageContent - The raw image buffer.
         * @param {string} originalFilename - The original name of the file.
         * @returns {Promise<{tempPath: string, originalSize: number, newSize: number}>}
         */
        processPostImageUpload: async (imageContent, originalFilename) => {
            console.log('SIMULATING image resize (Module Pattern)...');
            // Placeholder for a real image processing library.
            const resizedContent = imageContent.slice(0, Math.floor(imageContent.length * 0.5));
            
            const tempPath = path.join(_TEMP_DIR, `img_${Date.now()}${path.extname(originalFilename)}`);
            await fs.promises.writeFile(tempPath, resizedContent);

            return {
                tempPath,
                originalSize: imageContent.length,
                newSize: resizedContent.length
            };
        },

        /**
         * Streams a file to the client for download.
         * @param {http.ServerResponse} res - The server response object.
         * @param {string} filePath - The path of the file to be streamed.
         */
        streamFileForDownload: (res, filePath) => {
            fs.stat(filePath, (err, stats) => {
                if (err) {
                    res.writeHead(404, { 'Content-Type': 'text/plain' });
                    res.end('File not found');
                    return;
                }
                res.writeHead(200, {
                    'Content-Type': 'application/octet-stream',
                    'Content-Length': stats.size,
                    'Content-Disposition': `attachment; filename="${path.basename(filePath)}"`
                });
                fs.createReadStream(filePath).pipe(res);
            });
        },

        /**
         * Schedules a temporary file for cleanup.
         * @param {string} filePath - The path of the file to delete.
         * @param {number} delayMs - Delay in milliseconds before deletion.
         */
        scheduleCleanup: (filePath, delayMs = 60000) => {
            setTimeout(() => {
                fs.unlink(filePath, (err) => {
                    if (err) console.error(`[Module] Cleanup failed for ${filePath}:`, err.message);
                    else console.log(`[Module] Cleaned up ${filePath}`);
                });
            }, delayMs);
        },

        // Expose the parser for the main request handler
        parseRequest: _parseMultipartBody,
        
        // Expose DB for inspection if needed
        getDatabase: () => _MOCK_DB
    };
})();


// --- HTTP Server using the Module ---
const server = http.createServer(async (req, res) => {
    try {
        if (req.url === '/module/users/import' && req.method === 'POST') {
            const { files } = await FileOpsModule.parseRequest(req);
            const result = FileOpsModule.processUserCsvUpload(files.csv_file.content);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: 'Users imported successfully.', ...result }));

        } else if (req.url === '/module/posts/add' && req.method === 'POST') {
            const { fields, files } = await FileOpsModule.parseRequest(req);
            const imageFile = files.post_attachment;
            const imageInfo = await FileOpsModule.processPostImageUpload(imageFile.content, imageFile.filename);
            
            const postId = randomUUID();
            const newPost = {
                id: postId,
                user_id: fields.user_id,
                title: fields.title,
                content: `Image stored at: ${imageInfo.tempPath}`,
                status: 'DRAFT'
            };
            FileOpsModule.getDatabase().posts.set(postId, newPost);
            FileOpsModule.scheduleCleanup(imageInfo.tempPath);

            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ message: 'Post created.', post: newPost, imageInfo }));

        } else if (req.url.startsWith('/module/download/') && req.method === 'GET') {
            const filename = path.basename(req.url);
            // WARNING: In production, never directly use user input for file paths.
            // This is a simplified example.
            const secureBasePath = path.join(os.tmpdir(), 'app-v3-files');
            const requestedPath = path.join(secureBasePath, filename);

            // Basic path traversal check
            if (requestedPath.indexOf(secureBasePath) !== 0) {
                 res.writeHead(400).end('Invalid path');
                 return;
            }
            FileOpsModule.streamFileForDownload(res, requestedPath);

        } else {
            res.writeHead(404).end('Not Found');
        }
    } catch (error) {
        console.error('Module Server Error:', error);
        res.writeHead(500).end('Internal Server Error');
    }
});

const PORT = 3002;
// server.listen(PORT, () => {
//     console.log(`Module Pattern Server running on http://localhost:${PORT}`);
// });

console.log('Variation 3: Module Pattern with Closures loaded.');
// To run: uncomment server.listen and run the script.
// Example CURL commands:
// curl -X POST -F "csv_file=@/path/to/your/users.csv" http://localhost:3002/module/users/import
// curl -X POST -F "title=Module Post" -F "user_id=some-uuid" -F "post_attachment=@/path/to/your/image.jpg" http://localhost:3002/module/posts/add
</script>