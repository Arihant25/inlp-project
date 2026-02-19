<script>
// Variation 4: Service-Oriented / Layered Style
// This approach separates concerns into distinct "services," mimicking a microservice
// or layered application architecture. The main request handler acts as a controller,
// delegating tasks to the appropriate service.
// Naming convention: PascalCase for service objects, snake_case for variables/functions.

const http = require('http');
const fs = require('fs/promises');
const fs_sync = require('fs');
const path = require('path');
const os = require('os');
const { randomUUID } = require('crypto');

// --- Mock Data Layer ---
const DataLayer = {
    _users: new Map(),
    _posts: new Map(),
    save_user: async (user_data) => {
        const id = randomUUID();
        const user = { id, ...user_data, created_at: new Date() };
        DataLayer._users.set(id, user);
        return user;
    },
    save_post: async (post_data) => {
        const id = randomUUID();
        const post = { id, ...post_data };
        DataLayer._posts.set(id, post);
        return post;
    },
    find_post_by_id: async (id) => DataLayer._posts.get(id),
};

// --- Temporary File Management Service ---
const TempFileService = {
    _temp_dir: path.join(os.tmpdir(), 'app-v4-storage'),
    initialize: async () => {
        try {
            await fs.mkdir(TempFileService._temp_dir, { recursive: true });
        } catch (e) {
            console.error("Could not create temp dir", e);
        }
    },
    create_temp_path: (extension) => {
        return path.join(TempFileService._temp_dir, `${randomUUID()}${extension}`);
    },
    write_temp_file: async (buffer, extension) => {
        const file_path = TempFileService.create_temp_path(extension);
        await fs.writeFile(file_path, buffer);
        return file_path;
    },
    cleanup_file: async (file_path) => {
        try {
            await fs.unlink(file_path);
            console.log(`[TempFileService] Cleaned up: ${file_path}`);
        } catch (err) {
            console.error(`[TempFileService] Cleanup failed for ${file_path}:`, err.message);
        }
    }
};
TempFileService.initialize();

// --- Parsing Service ---
const ParsingService = {
    parse_csv_buffer: (buffer) => {
        const records = [];
        const [header_line, ...data_lines] = buffer.toString('utf-8').trim().split('\n');
        const headers = header_line.split(',');
        for (const line of data_lines) {
            const values = line.split(',');
            const record = headers.reduce((obj, header, i) => ({ ...obj, [header.trim()]: values[i].trim() }), {});
            records.push(record);
        }
        return records;
    },
    parse_multipart_form: (req) => new Promise((resolve, reject) => {
        const boundary = `--${req.headers['content-type'].split('=')[1]}`;
        const chunks = [];
        req.on('data', c => chunks.push(c)).on('end', () => {
            const body = Buffer.concat(chunks);
            const parts = body.toString().split(boundary).slice(1, -1);
            const result = { fields: {}, files: {} };
            for (const part of parts) {
                const [raw_headers, raw_content] = part.split('\r\n\r\n', 2);
                const content = raw_content.slice(0, -2); // remove trailing \r\n
                const name_match = raw_headers.match(/name="([^"]+)"/);
                if (!name_match) continue;
                const name = name_match[1];
                const filename_match = raw_headers.match(/filename="([^"]+)"/);
                if (filename_match) {
                    const file_content_start = body.indexOf(raw_content, body.indexOf(raw_headers))
                    const file_content_end = file_content_start + content.length;
                    result.files[name] = {
                        filename: filename_match[1],
                        data: body.slice(file_content_start, file_content_end)
                    };
                } else {
                    result.fields[name] = content;
                }
            }
            resolve(result);
        }).on('error', reject);
    })
};

// --- Image Processing Service ---
const ImageService = {
    // SIMULATION: In a real app, this service would wrap a library like 'sharp'.
    resize_for_thumbnail: async (image_buffer) => {
        console.log(`[ImageService] Simulating thumbnail generation...`);
        const new_size = Math.floor(image_buffer.length / 4);
        return image_buffer.slice(0, new_size);
    }
};

// --- Download Service ---
const DownloadService = {
    stream_file_to_response: (res, file_path) => {
        if (!fs_sync.existsSync(file_path)) {
            res.writeHead(404).end('Resource not found.');
            return;
        }
        const file_stats = fs_sync.statSync(file_path);
        res.writeHead(200, {
            'Content-Type': 'application/octet-stream',
            'Content-Length': file_stats.size,
            'Content-Disposition': `attachment; filename="${path.basename(file_path)}"`
        });
        fs_sync.createReadStream(file_path).pipe(res);
    }
};

// --- Controller / Request Handler ---
const http_controller = async (req, res) => {
    const url_parts = req.url.split('/');
    try {
        if (req.url === '/api/v1/users/batch-create' && req.method === 'POST') {
            const { files } = await ParsingService.parse_multipart_form(req);
            const user_records = ParsingService.parse_csv_buffer(files.user_batch.data);
            for (const record of user_records) {
                await DataLayer.save_user({
                    email: record.email,
                    password_hash: `hashed_${record.email}`,
                    role: record.role,
                    is_active: record.is_active === 'true'
                });
            }
            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'success', created: user_records.length }));

        } else if (req.url === '/api/v1/posts' && req.method === 'POST') {
            const { fields, files } = await ParsingService.parse_multipart_form(req);
            const thumbnail_buffer = await ImageService.resize_for_thumbnail(files.post_image.data);
            const temp_path = await TempFileService.write_temp_file(thumbnail_buffer, path.extname(files.post_image.filename));
            
            const new_post = await DataLayer.save_post({
                user_id: fields.user_id,
                title: fields.title,
                content: `Thumbnail at: ${temp_path}`,
                status: 'PUBLISHED'
            });

            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ status: 'success', post: new_post }));
            // Schedule cleanup after a delay
            setTimeout(() => TempFileService.cleanup_file(temp_path), 120000);

        } else if (url_parts[1] === 'api' && url_parts[2] === 'v1' && url_parts[3] === 'posts' && url_parts[5] === 'thumbnail') {
            const post_id = url_parts[4];
            const post = await DataLayer.find_post_by_id(post_id);
            if (!post) {
                res.writeHead(404).end('Post not found.');
                return;
            }
            const file_path = post.content.split('Thumbnail at: ')[1];
            DownloadService.stream_file_to_response(res, file_path);

        } else {
            res.writeHead(404).end('Endpoint not found.');
        }
    } catch (error) {
        console.error(`[Controller] Error processing ${req.method} ${req.url}:`, error);
        res.writeHead(500).end('An internal error occurred.');
    }
};

const server = http.createServer(http_controller);
const PORT = 3003;
// server.listen(PORT, () => {
//     console.log(`Service-Oriented Server running on http://localhost:${PORT}`);
// });

console.log('Variation 4: Service-Oriented / Layered Style loaded.');
// To run: uncomment server.listen and run the script.
// Example CURL commands:
// curl -X POST -F "user_batch=@/path/to/your/users.csv" http://localhost:3003/api/v1/users/batch-create
// curl -X POST -F "title=Service Post" -F "user_id=some-uuid" -F "post_image=@/path/to/your/image.png" http://localhost:3003/api/v1/posts
// curl -X GET http://localhost:3003/api/v1/posts/{POST_ID}/thumbnail > thumb.png
</script>