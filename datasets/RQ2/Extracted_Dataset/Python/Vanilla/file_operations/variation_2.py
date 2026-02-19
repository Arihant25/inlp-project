import http.server
import socketserver
import uuid
import datetime
import enum
import csv
import tempfile
import os
import sys
from email.parser import BytesParser
from dataclasses import dataclass, field

# --- Domain Schema (OOP Style with Dataclasses) ---

class UserRole(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

@dataclass
class User:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    email: str = ""
    password_hash: str = ""
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime.datetime = field(default_factory=lambda: datetime.datetime.now(datetime.timezone.utc))

@dataclass
class Post:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    user_id: uuid.UUID = None
    title: str = ""
    content: str = ""
    status: PostStatus = PostStatus.DRAFT
    image_path: str = None

# --- In-Memory Storage ---
class Storage:
    USERS: dict[uuid.UUID, User] = {}
    POSTS: dict[uuid.UUID, Post] = {}

    @classmethod
    def populate_mock_data(cls):
        admin = User(email="admin@example.com", password_hash="hash1", role=UserRole.ADMIN)
        cls.USERS[admin.id] = admin
        post = Post(user_id=admin.id, title="First Post", content="...")
        cls.POSTS[post.id] = post
        print(f"Mock data created. Admin User ID: {admin.id}, Post ID: {post.id}")

# --- Service Layer: File Processing Logic ---

class FileProcessorService:
    def __init__(self, storage):
        self.storage = storage

    def import_users_from_csv(self, file_path: str) -> int:
        """Reads a CSV file and creates User objects."""
        users_created = 0
        with open(file_path, mode='r', encoding='utf-8') as infile:
            reader = csv.DictReader(infile)
            for row in reader:
                user = User(email=row['email'], password_hash=row['password_hash'])
                self.storage.USERS[user.id] = user
                users_created += 1
        return users_created

    def process_and_attach_image(self, file_path: str, post_id: uuid.UUID) -> str:
        """Processes an image and attaches its path to a Post."""
        if post_id not in self.storage.POSTS:
            raise ValueError(f"Post with ID {post_id} not found.")
        
        with tempfile.NamedTemporaryFile(delete=False, suffix='.ppm') as tmp_out:
            resized_path = tmp_out.name
        
        try:
            ImageUtil.resize_ppm(file_path, resized_path)
            post = self.storage.POSTS[post_id]
            post.image_path = resized_path
            return resized_path
        except Exception as e:
            os.remove(resized_path) # Clean up failed output
            raise IOError(f"Failed to process image: {e}")

class ImageUtil:
    @staticmethod
    def resize_ppm(in_path: str, out_path: str, scale: float = 0.5):
        """A static utility method for basic PPM image resizing."""
        with open(in_path, 'r') as f_in:
            if f_in.readline().strip() != 'P3': raise ValueError("Requires P3 PPM format")
            line = f_in.readline()
            while line.startswith('#'): line = f_in.readline()
            w, h = map(int, line.split())
            max_val = int(f_in.readline())
            pixels = list(map(int, f_in.read().split()))
        
        new_w, new_h = int(w * scale), int(h * scale)
        new_pixels = []
        for y in range(new_h):
            for x in range(new_w):
                orig_idx = (int(y / scale) * w + int(x / scale)) * 3
                new_pixels.extend(pixels[orig_idx:orig_idx+3])

        with open(out_path, 'w') as f_out:
            f_out.write(f"P3\n{new_w} {new_h}\n{max_val}\n{' '.join(map(str, new_pixels))}\n")

# --- Multipart Form Parser (OOP Style) ---

@dataclass
class FormPart:
    name: str
    filename: str
    content_type: str
    content: bytes
    temp_path: str = None

class MultipartParser:
    def __init__(self, rfile, headers):
        self.rfile = rfile
        self.headers = headers
        self.boundary = self._get_boundary()
        self.content_length = int(self.headers['Content-Length'])

    def _get_boundary(self):
        ct = self.headers['Content-Type']
        if 'boundary=' not in ct:
            raise ValueError("Missing boundary in Content-Type header")
        return ct.split('boundary=')[1].encode('utf-8')

    def parse(self) -> (dict, dict):
        """Parses the request body and returns fields and files."""
        body = self.rfile.read(self.content_length)
        parts = body.split(b'--' + self.boundary)
        
        fields, files = {}, {}
        header_parser = BytesParser()

        for part_data in parts:
            if not part_data.strip() or part_data.strip() == b'--':
                continue
            
            headers_raw, content = part_data.split(b'\r\n\r\n', 1)
            content = content.rstrip(b'\r\n')
            headers = header_parser.parsebytes(headers_raw.strip())
            
            disposition = headers.get('Content-Disposition')
            if not disposition: continue
            
            params = {k: v.strip('"') for k, v in (p.strip().split('=') for p in disposition.split(';')[1:])}
            name = params.get('name')
            if not name: continue

            if 'filename' in params:
                with tempfile.NamedTemporaryFile(delete=False) as tmp:
                    tmp.write(content)
                    files[name] = FormPart(name, params['filename'], headers.get('Content-Type'), None, tmp.name)
            else:
                fields[name] = content.decode('utf-8')
        
        return fields, files

# --- HTTP Handler ---

class OopRequestHandler(http.server.BaseHTTPRequestHandler):
    
    def do_POST(self):
        try:
            parser = MultipartParser(self.rfile, self.headers)
            fields, files = parser.parse()
            
            service = FileProcessorService(Storage)
            
            upload_type = fields.get('upload_type')
            if upload_type == 'user_csv':
                file_part = files.get('user_data')
                if not file_part: raise ValueError("'user_data' file is required.")
                try:
                    count = service.import_users_from_csv(file_part.temp_path)
                    self._send_response(201, f"OK. Imported {count} users.")
                finally:
                    os.remove(file_part.temp_path)
            
            elif upload_type == 'post_image':
                file_part = files.get('image_file')
                post_id_str = fields.get('post_id')
                if not file_part or not post_id_str: raise ValueError("'image_file' and 'post_id' are required.")
                try:
                    post_id = uuid.UUID(post_id_str)
                    new_path = service.process_and_attach_image(file_part.temp_path, post_id)
                    self._send_response(200, f"OK. Image processed and saved to {new_path}")
                finally:
                    os.remove(file_part.temp_path)
            
            else:
                self._send_response(400, "Invalid 'upload_type'. Must be 'user_csv' or 'post_image'.")

        except Exception as e:
            print(f"ERROR: {e}", file=sys.stderr)
            self._send_response(500, f"Server Error: {e}")

    def do_GET(self):
        if self.path == '/download/sample.ppm':
            self.send_response(200)
            self.send_header('Content-Type', 'image/x-portable-pixmap')
            self.send_header('Content-Disposition', 'attachment; filename="sample.ppm"')
            self.end_headers()
            # Stream response
            content = "P3\n2 2\n255\n255 0 0 0 255 0\n0 0 255 255 255 255\n"
            self.wfile.write(content.encode('utf-8'))
        else:
            self._send_response(404, "Not Found.")

    def _send_response(self, code, message):
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.end_headers()
        self.wfile.write(message.encode('utf-8'))

def main():
    Storage.populate_mock_data()
    port = 8001
    server_address = ('', port)
    httpd = http.server.HTTPServer(server_address, OopRequestHandler)
    print(f"Starting OOP-style server on port {port}...")
    print("Test with: curl -X POST -F 'upload_type=user_csv' -F 'user_data=@users.csv' http://localhost:8001/")
    httpd.serve_forever()

if __name__ == '__main__':
    main()