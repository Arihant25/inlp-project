import http.server, uuid, datetime, enum, csv, tempfile, os, sys, io

# --- Schema & Data Store ---
class R(enum.Enum): ADMIN, USER
class S(enum.Enum): DRAFT, PUBLISHED
USERS, POSTS = {}, {}

def setup_data():
    uid = uuid.uuid4()
    USERS[uid] = {'id': uid, 'email': 'a@b.c', 'role': R.ADMIN}
    pid = uuid.uuid4()
    POSTS[pid] = {'id': pid, 'user_id': uid, 'title': 'T'}
    print(f"Data ready. User: {uid}, Post: {pid}")

# --- Core Logic ---
def process_csv(fpath):
    """Parses user CSV, returns count of new users."""
    c = 0
    with open(fpath, 'r') as f:
        for r in csv.DictReader(f):
            uid = uuid.uuid4()
            USERS[uid] = {'id': uid, 'email': r['email'], 'p_hash': r['password_hash'], 'role': R.USER}
            c += 1
    return c

def process_img(fpath, pid):
    """'Resizes' a PPM image, updates post with new path."""
    post = POSTS.get(uuid.UUID(pid))
    if not post: raise KeyError("Post not found")
    
    with open(fpath, 'r') as f_in, tempfile.NamedTemporaryFile('w', delete=False, suffix='.ppm') as f_out:
        lines = f_in.readlines()
        if lines[0].strip() != 'P3': raise TypeError("P3 PPM only")
        w, h = map(int, lines[1].split())
        f_out.write(f"P3\n{w//2} {h//2}\n{lines[2]}") # Write header with new dims
        pixels = ' '.join(lines[3:]).split()
        # Subsample by taking every other row and column
        new_pixels = [pixels[i*3:i*3+3] for i in range(len(pixels)//3)]
        for y in range(0, h, 2):
            for x in range(0, w, 2):
                f_out.write(' '.join(new_pixels[y*w + x]) + ' ')
        post['img_path'] = f_out.name
        return f_out.name

# --- HTTP Handler (Minimalist Style) ---
class CompactHandler(http.server.BaseHTTPRequestHandler):

    def do_GET(self):
        """Serves a downloadable file."""
        if self.path == '/dl':
            self.send_response(200)
            self.send_header('Content-Disposition', 'attachment; filename="img.ppm"')
            self.end_headers()
            self.wfile.write(b"P3\n2 2\n255\n255 0 0 0 255 0\n0 0 255 255 255 255\n")
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        """Handles uploads via a generator-based multipart parser."""
        try:
            ctype = self.headers['Content-Type']
            boundary = ctype.split('boundary=')[1].encode('utf-8')
            clen = int(self.headers['Content-Length'])
            
            # Parse form data into a dictionary
            form = {}
            for part in self.gen_parts(self.rfile, boundary, clen):
                if part['path']: # It's a file
                    form[part['name']] = part
                else: # It's a field
                    form[part['name']] = part['content'].decode()

            # Route to correct processor
            res_msg = ""
            if form.get('type') == 'csv':
                f_info = form.get('data')
                count = process_csv(f_info['path'])
                res_msg = f"Processed {count} users."
                os.remove(f_info['path'])
            elif form.get('type') == 'img':
                f_info = form.get('img')
                new_path = process_img(f_info['path'], form.get('pid'))
                res_msg = f"Image resized to {new_path}"
                os.remove(f_info['path'])
            else:
                raise ValueError("Missing or invalid 'type' field.")

            self.wfile.write(f"200 OK\n{res_msg}".encode())
        except Exception as e:
            print(f"ERR: {e}", file=sys.stderr)
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f"Error: {e}".encode())

    def gen_parts(self, rfile, boundary, clen):
        """A concise generator to parse multipart/form-data."""
        body = rfile.read(clen)
        for part in body.split(b'--' + boundary):
            if b'Content-Disposition' not in part: continue
            
            headers_raw, content = part.split(b'\r\n\r\n', 1)
            content = content.rstrip(b'\r\n--\r\n')
            
            # Quick and dirty header parsing
            get_val = lambda h, k: h.split(k)[1].split(b'"')[1].decode() if k in h else None
            name = get_val(headers_raw, b'name="')
            fname = get_val(headers_raw, b'filename="')
            
            tmp_path = None
            if fname:
                fd, tmp_path = tempfile.mkstemp()
                with os.fdopen(fd, 'wb') as f:
                    f.write(content)

            yield {'name': name, 'filename': fname, 'content': content, 'path': tmp_path}

if __name__ == '__main__':
    setup_data()
    port = 8003
    httpd = http.server.HTTPServer(('', port), CompactHandler)
    print(f"Starting minimalist server on port {port}...")
    print("Test CSV: curl -F 'type=csv' -F 'data=@users.csv' http://localhost:8003/")
    print("Test IMG: curl -F 'type=img' -F 'pid=<ID>' -F 'img=@test.ppm' http://localhost:8003/")
    httpd.serve_forever()