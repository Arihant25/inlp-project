import http.server
import socketserver
import os
import sys
import uuid
import datetime
import enum
import csv
import tempfile
import io
from email.parser import BytesParser

# --- Domain Schema ---

class UserRole(enum.Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(enum.Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

# Using simple dictionaries as data classes for simplicity
def make_user(email, password_hash, role=UserRole.USER):
    return {
        'id': uuid.uuid4(),
        'email': email,
        'password_hash': password_hash,
        'role': role,
        'is_active': True,
        'created_at': datetime.datetime.now(datetime.timezone.utc)
    }

def make_post(user_id, title, content):
    return {
        'id': uuid.uuid4(),
        'user_id': user_id,
        'title': title,
        'content': content,
        'status': PostStatus.DRAFT
    }

# --- In-Memory Database ---
DB_USERS = {}
DB_POSTS = {}

def _populate_mock_data():
    """Create some initial data to work with."""
    admin = make_user("admin@example.com", "hashed_password_1", UserRole.ADMIN)
    DB_USERS[admin['id']] = admin
    post = make_post(admin['id'], "My First Post", "Content about file operations.")
    DB_POSTS[post['id']] = post
    print(f"Mock data created. Admin User ID: {admin['id']}, Post ID: {post['id']}")

# --- Image Processing Utility (Standard Library Only) ---

def _resize_ppm_image(in_path, out_path, scale_factor=0.5):
    """
    Simulates image resizing for a simple PPM P3 format.
    Reads a text-based PPM, subsamples pixels, and writes a new one.
    This is a stand-in for a real image library like Pillow.
    """
    try:
        with open(in_path, 'r') as f_in:
            magic_number = f_in.readline().strip()
            if magic_number != 'P3':
                raise ValueError("Not a valid P3 PPM file for this simple processor.")
            
            # Skip comments
            line = f_in.readline()
            while line.startswith('#'):
                line = f_in.readline()

            dims = line.split()
            width, height = int(dims[0]), int(dims[1])
            max_val = int(f_in.readline())
            
            pixels = [int(p) for p in f_in.read().split()]

        new_width = int(width * scale_factor)
        new_height = int(height * scale_factor)
        new_pixels = []

        for y in range(new_height):
            for x in range(new_width):
                orig_y = int(y / scale_factor)
                orig_x = int(x / scale_factor)
                idx = (orig_y * width + orig_x) * 3
                new_pixels.extend(pixels[idx:idx+3])

        with open(out_path, 'w') as f_out:
            f_out.write('P3\n')
            f_out.write(f'{new_width} {new_height}\n')
            f_out.write(f'{max_val}\n')
            f_out.write(' '.join(map(str, new_pixels)) + '\n')
        return True
    except Exception as e:
        print(f"Error processing PPM image: {e}", file=sys.stderr)
        return False

# --- HTTP Request Handler (Procedural Style) ---

class FileOperationsHandler(http.server.BaseHTTPRequestHandler):

    def _send_response(self, code, message, content_type='text/plain'):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.end_headers()
        self.wfile.write(message.encode('utf-8'))

    def do_GET(self):
        """Handles file download with streaming."""
        if self.path == '/download/sample.ppm':
            # Create a sample PPM image in memory for download
            ppm_content = "P3\n4 4\n255\n" \
                          "255 0 0  0 255 0  0 0 255  255 255 0\n" \
                          "255 0 255  0 255 255  0 0 0  255 255 255\n" \
                          "255 0 0  0 255 0  0 0 255  255 255 0\n" \
                          "255 0 255  0 255 255  0 0 0  255 255 255\n"
            
            self.send_response(200)
            self.send_header('Content-Type', 'image/x-portable-pixmap')
            self.send_header('Content-Disposition', 'attachment; filename="sample.ppm"')
            self.send_header('Content-Length', str(len(ppm_content)))
            self.end_headers()
            
            # Stream the content in chunks
            chunk_size = 32
            stream = io.BytesIO(ppm_content.encode('utf-8'))
            while chunk := stream.read(chunk_size):
                self.wfile.write(chunk)
        else:
            self._send_response(404, "Not Found")

    def do_POST(self):
        """Handles file uploads."""
        try:
            fields, files = self._parse_multipart_form_data()
            
            upload_type = fields.get('upload_type')
            if upload_type == 'user_csv':
                self._process_user_csv(files.get('user_data'))
            elif upload_type == 'post_image':
                post_id_str = fields.get('post_id')
                self._process_post_image(files.get('image_file'), post_id_str)
            else:
                self._send_response(400, "Bad Request: Missing or invalid 'upload_type' field.")

        except Exception as e:
            print(f"Error in do_POST: {e}", file=sys.stderr)
            self._send_response(500, f"Internal Server Error: {e}")

    def _parse_multipart_form_data(self):
        """Manually parses multipart/form-data from the request body."""
        content_type = self.headers['Content-Type']
        boundary = content_type.split("=")[1].encode('utf-8')
        
        content_length = int(self.headers['Content-Length'])
        body = self.rfile.read(content_length)
        
        parts = body.split(b'--' + boundary)
        
        fields = {}
        files = {}
        
        header_parser = BytesParser()

        for part in parts:
            if not part.strip() or part.strip() == b'--':
                continue
            
            headers_raw, content = part.split(b'\r\n\r\n', 1)
            # The content might end with \r\n, which should be stripped
            content = content.rstrip(b'\r\n')

            # The headers part starts with \r\n, which we strip before parsing
            headers = header_parser.parsebytes(headers_raw.strip())

            disposition = headers.get('Content-Disposition')
            if not disposition:
                continue
                
            disp_params = {k: v for k, v in [p.strip().split('=') for p in disposition.split(';')[1:]]}
            name = disp_params.get('name', '').strip('"')

            if 'filename' in disp_params:
                filename = disp_params['filename'].strip('"')
                # Save to a temporary file
                with tempfile.NamedTemporaryFile(delete=False, prefix='upload_') as tmp_file:
                    tmp_file.write(content)
                    files[name] = {'filename': filename, 'path': tmp_file.name, 'content_type': headers.get('Content-Type')}
            else:
                fields[name] = content.decode('utf-8')
                
        return fields, files

    def _process_user_csv(self, file_info):
        """Processes an uploaded CSV of users."""
        if not file_info:
            return self._send_response(400, "Bad Request: 'user_data' file part is missing.")
        
        file_path = file_info['path']
        count = 0
        try:
            with open(file_path, 'r', newline='', encoding='utf-8') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    if 'email' not in row or 'password_hash' not in row:
                        continue
                    new_user = make_user(row['email'], row['password_hash'])
                    DB_USERS[new_user['id']] = new_user
                    count += 1
            self._send_response(201, f"Successfully created {count} users.")
        finally:
            os.remove(file_path) # Clean up temporary file

    def _process_post_image(self, file_info, post_id_str):
        """Processes an uploaded image for a post."""
        if not file_info:
            return self._send_response(400, "Bad Request: 'image_file' part is missing.")
        if not post_id_str:
            return self._send_response(400, "Bad Request: 'post_id' field is missing.")
        
        try:
            post_id = uuid.UUID(post_id_str)
            if post_id not in DB_POSTS:
                return self._send_response(404, f"Post with ID {post_id} not found.")
        except ValueError:
            return self._send_response(400, "Invalid Post ID format.")

        in_path = file_info['path']
        try:
            with tempfile.NamedTemporaryFile(delete=False, suffix='.ppm', prefix='resized_') as tmp_out:
                out_path = tmp_out.name
            
            if _resize_ppm_image(in_path, out_path):
                # In a real app, you'd save the path `out_path` to the Post model
                DB_POSTS[post_id]['image_path'] = out_path
                self._send_response(200, f"Image processed and associated with post {post_id}. Resized image at {out_path}")
            else:
                os.remove(out_path) # Clean up failed output
                self._send_response(415, "Unsupported or invalid image format. Only PPM P3 is supported.")
        finally:
            os.remove(in_path) # Clean up original temporary file

def run(server_class=http.server.HTTPServer, handler_class=FileOperationsHandler, port=8000):
    _populate_mock_data()
    server_address = ('', port)
    httpd = server_class(server_address, handler_class)
    print(f"Starting httpd server on port {port}...")
    print("---")
    print("To test CSV upload:")
    print("echo 'email,password_hash' > users.csv && echo 'test1@example.com,hash1' >> users.csv")
    print("curl -X POST -F 'upload_type=user_csv' -F 'user_data=@users.csv' http://localhost:8000/")
    print("\nTo test Image upload (requires a valid P3 PPM file named 'test.ppm'):")
    print("POST_ID=$(curl -s http://localhost:8000/ | grep 'Post ID' | awk '{print $4}') # This is a placeholder, get a real ID")
    print("curl -X POST -F 'upload_type=post_image' -F 'post_id=<A_VALID_POST_ID>' -F 'image_file=@test.ppm' http://localhost:8000/")
    print("\nTo test file download:")
    print("curl -o downloaded_image.ppm http://localhost:8000/download/sample.ppm")
    print("---")
    httpd.serve_forever()

if __name__ == '__main__':
    run()