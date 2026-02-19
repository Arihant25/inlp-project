import json
import re
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone
from enum import Enum

# --- Global Data Store & Model ---
class Role(Enum): ADMIN, USER = "ADMIN", "USER"
class Status(Enum): DRAFT, PUBLISHED = "DRAFT", "PUBLISHED"

g_db = {"users": {}, "posts": {}}

def init_db():
    uid1, uid2 = str(uuid.uuid4()), str(uuid.uuid4())
    g_db["users"] = {
        uid1: {"id": uid1, "email": "min.admin@example.com", "password_hash": "ph1", "role": Role.ADMIN.value, "is_active": True, "created_at": datetime.now(timezone.utc).isoformat()},
        uid2: {"id": uid2, "email": "min.user@example.com", "password_hash": "ph2", "role": Role.USER.value, "is_active": True, "created_at": datetime.now(timezone.utc).isoformat()}
    }
    pid1 = str(uuid.uuid4())
    g_db["posts"][pid1] = {"id": pid1, "user_id": uid1, "title": "Minimal Post", "content": "...", "status": Status.DRAFT.value}

# --- Utility Functions ---
def send_res(h, code, data):
    h.send_response(code)
    h.send_header('Content-Type', 'application/json')
    h.end_headers()
    if data is not None:
        h.wfile.write(json.dumps(data).encode())

def get_body(h):
    cl = int(h.headers.get('Content-Length', 0))
    return json.loads(h.rfile.read(cl)) if cl > 0 else {}

# --- Endpoint Handlers ---
def list_users(h, match, qs):
    users = list(g_db["users"].values())
    if 'role' in qs: users = [u for u in users if u['role'] == qs['role'][0].upper()]
    if 'is_active' in qs: users = [u for u in users if u['is_active'] == (qs['is_active'][0].lower() == 'true')]
    
    page, limit = int(qs.get('page', [1])[0]), int(qs.get('limit', [10])[0])
    start = (page - 1) * limit
    
    res = {"page": page, "limit": limit, "total": len(users), "data": users[start:start+limit]}
    send_res(h, 200, res)

def get_user(h, match, qs):
    uid = match.group(1)
    user = g_db["users"].get(uid)
    send_res(h, 200, user) if user else send_res(h, 404, {"err": "not found"})

def create_user(h, match, qs):
    body = get_body(h)
    if not all(k in body for k in ["email", "password_hash"]):
        return send_res(h, 400, {"err": "missing fields"})
    
    uid = str(uuid.uuid4())
    user = {
        "id": uid, "email": body["email"], "password_hash": body["password_hash"],
        "role": body.get("role", Role.USER.value), "is_active": body.get("is_active", True),
        "created_at": datetime.now(timezone.utc).isoformat()
    }
    g_db["users"][uid] = user
    send_res(h, 201, user)

def update_user(h, match, qs):
    uid = match.group(1)
    if uid not in g_db["users"]: return send_res(h, 404, {"err": "not found"})
    
    body = get_body(h)
    g_db["users"][uid].update(body)
    send_res(h, 200, g_db["users"][uid])

def delete_user(h, match, qs):
    uid = match.group(1)
    if uid in g_db["users"]:
        del g_db["users"][uid]
        send_res(h, 204, None)
    else:
        send_res(h, 404, {"err": "not found"})

# --- Route Dispatcher ---
ROUTES = [
    ('GET', re.compile(r'^/users/?$'), list_users),
    ('GET', re.compile(r'^/users/([a-f0-9-]+)/?$'), get_user),
    ('POST', re.compile(r'^/users/?$'), create_user),
    ('PUT', re.compile(r'^/users/([a-f0-9-]+)/?$'), update_user),
    ('PATCH', re.compile(r'^/users/([a-f0-9-]+)/?$'), update_user),
    ('DELETE', re.compile(r'^/users/([a-f0-9-]+)/?$'), delete_user),
]

# --- Main Request Handler ---
class MinimalApiHandler(BaseHTTPRequestHandler):
    def handle_request(self):
        url = urlparse(self.path)
        qs = parse_qs(url.query)
        
        for method, pattern, handler_func in ROUTES:
            if method == self.command:
                match = pattern.match(url.path)
                if match:
                    return handler_func(self, match, qs)
        
        send_res(self, 404, {"err": "endpoint not found"})

    def do_GET(self): self.handle_request()
    def do_POST(self): self.handle_request()
    def do_PUT(self): self.handle_request()
    def do_PATCH(self): self.handle_request()
    def do_DELETE(self): self.handle_request()

# --- Server Execution ---
if __name__ == '__main__':
    init_db()
    server_addr = ('', 8003)
    httpd = HTTPServer(server_addr, MinimalApiHandler)
    print("Starting minimalist regex-based server on port 8003...")
    httpd.serve_forever()