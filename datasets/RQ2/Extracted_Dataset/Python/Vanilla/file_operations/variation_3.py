import http.server
import socketserver
import uuid
import datetime
import enum
import csv
import tempfile
import os
import sys
import io
from email.parser import BytesParser

# --- Domain Model ---
class UserRole(enum.Enum): ADMIN = 'ADMIN'; USER = 'USER'
class PostStatus(enum.Enum): DRAFT = 'DRAFT'; PUBLISHED = 'PUBLISHED'

class User:
    def __init__(self, email, password_hash, role=UserRole.USER):
        self.id = uuid.uuid4()
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = True
        self.created_at = datetime.datetime.now(datetime.timezone.utc)

class Post:
    def __init__(self, user_id, title, content):
        self.id = uuid.uuid4()
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = PostStatus.DRAFT
        self.processed_image_location = None

# --- Data Access Layer (Repositories) ---
class BaseRepository:
    def __init__(self):
        self._data = {}
    def find_by_id(self, id): return self._data.get(id)
    def save(self, entity): self._data[entity.id] = entity; return entity

class UserRepository(BaseRepository): pass
class PostRepository(BaseRepository): pass

# --- Utility Layer ---
class ImageProcessingUtility:
    @staticmethod
    def resize_basic_ppm(input_stream, output_path):
        """Reads from a stream, resizes, and writes to a path."""
        lines = [line.decode('utf-8') for line in input_stream.readlines()]
        if lines[0].strip() != 'P3': raise ValueError("Unsupported format")
        
        dims_line_idx = 1
        while lines[dims_line_idx].startswith('#'): dims_line_idx += 1
        
        w, h = map(int, lines[dims_line_idx].split())
        max_val = int(lines[dims_line_idx+1])
        pixels = list(map(int, ' '.join(lines[dims_line_idx+2:]).split()))
        
        new_w, new_h = w // 2, h // 2
        new_pixels = []
        for y in range(new_h):
            for x in range(new_w):
                orig_idx = (y * 2 * w + x * 2) * 3
                new_pixels.extend(pixels[orig_idx:orig_idx+3])
        
        with open(output_path, 'w') as f:
            f.write(f"P3\n{new_w} {new_h}\n{max_val}\n{' '.join(map(str, new_pixels))}\n")
        return output_path

# --- Service Layer (Business Logic) ---
class FileUploadService:
    def __init__(self, user_repo: UserRepository, post_repo: PostRepository):
        self.user_repo = user_repo
        self.post_repo = post_repo

    def handle_user_bulk_import(self, file_stream: io.BytesIO) -> int:
        """Business logic for importing users from a CSV stream."""
        text_stream = io.TextIOWrapper(file_stream, encoding='utf-8')
        reader = csv.DictReader(text_stream)
        count = 0
        for row in reader:
            user = User(email=row['email'], password_hash=row['password_hash'])
            self.user_repo.save(user)
            count += 1
        return count

    def handle_post_image_upload(self, file_stream: io.BytesIO, post_id: uuid.UUID) -> str:
        """Business logic for processing and attaching an image to a post."""
        post = self.post_repo.find_by_id(post_id)
        if not post:
            raise LookupError(f"Post {post_id} not found.")
        
        with tempfile.NamedTemporaryFile(delete=False, suffix=".ppm") as tmp:
            output_path = tmp.name
        
        try:
            processed_path = ImageProcessingUtility.resize_basic_ppm(file_stream, output_path)
            post.processed_image_location = processed_path
            self.post_repo.save(post)
            return processed_path
        except Exception as e:
            os.remove(output_path)
            raise RuntimeError(f"Image processing failed: {e}")

# --- Presentation Layer (HTTP Controller) ---
class ServiceOrientedRequestHandler(http.server.BaseHTTPRequestHandler):
    # In a real app, these would be injected (Dependency Injection)
    user_repository = UserRepository()
    post_repository = PostRepository()
    file_service = FileUploadService(user_repository, post_repository)

    def _parse_request(self):
        """A helper to parse multipart form data from the request."""
        ctype = self.headers['Content-Type']
        boundary = b'--' + ctype.split('boundary=')[1].encode('utf-8')
        content_len = int(self.headers['Content-Length'])
        body = self.rfile.read(content_len)
        
        fields, files = {}, {}
        for part in body.split(boundary):
            if b'Content-Disposition' not in part: continue
            
            header_raw, content = part.split(b'\r\n\r\n', 1)
            content = content.rstrip(b'\r\n--\r\n')
            
            # Simple header parsing for this example
            name_match = b'name="'
            name_start = header_raw.find(name_match) + len(name_match)
            name_end = header_raw.find(b'"', name_start)
            name = header_raw[name_start:name_end].decode('utf-8')

            if b'filename="' in header_raw:
                files[name] = io.BytesIO(content)
            else:
                fields[name] = content.decode('utf-8')
        return fields, files

    def do_POST(self):
        try:
            fields, files = self._parse_request()
            upload_type = fields.get('upload_type')

            if upload_type == 'user_csv':
                file_stream = files.get('user_data')
                if not file_stream: return self._send_json_response(400, {"error": "user_data file missing"})
                count = self.file_service.handle_user_bulk_import(file_stream)
                self._send_json_response(201, {"message": f"Imported {count} users."})

            elif upload_type == 'post_image':
                file_stream = files.get('image_file')
                post_id_str = fields.get('post_id')
                if not file_stream or not post_id_str: return self._send_json_response(400, {"error": "image_file and post_id required"})
                
                post_id = uuid.UUID(post_id_str)
                path = self.file_service.handle_post_image_upload(file_stream, post_id)
                self._send_json_response(200, {"message": "Image processed", "path": path})
            else:
                self._send_json_response(400, {"error": "Invalid upload_type"})
        except Exception as e:
            self._send_json_response(500, {"error": str(e)})

    def do_GET(self):
        if self.path == '/download/sample.ppm':
            self.send_response(200)
            self.send_header('Content-Type', 'image/x-portable-pixmap')
            self.end_headers()
            self.wfile.write(b"P3\n2 2\n255\n255 0 0 0 255 0\n0 0 255 255 255 255\n")
        else:
            self._send_json_response(404, {"error": "Not Found"})

    def _send_json_response(self, code, payload):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        # A simple JSON-like string response
        response_str = str(payload).replace("'", '"')
        self.wfile.write(response_str.encode('utf-8'))

def bootstrap():
    """Initialize repositories with mock data."""
    user_repo = ServiceOrientedRequestHandler.user_repository
    post_repo = ServiceOrientedRequestHandler.post_repository
    admin = User("admin@example.com", "hash1", UserRole.ADMIN)
    user_repo.save(admin)
    post = Post(admin.id, "A Post Title", "Some content")
    post_repo.save(post)
    print(f"Bootstrap complete. Admin User ID: {admin.id}, Post ID: {post.id}")

def main():
    bootstrap()
    port = 8002
    server_address = ('', port)
    httpd = http.server.HTTPServer(server_address, ServiceOrientedRequestHandler)
    print(f"Starting Service-Oriented server on port {port}...")
    print("Test with: curl -X POST -F 'upload_type=post_image' -F 'post_id=<ID>' -F 'image_file=@test.ppm' http://localhost:8002/")
    httpd.serve_forever()

if __name__ == '__main__':
    main()