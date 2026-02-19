import json, time, uuid, sys
from datetime import datetime, timezone
from enum import Enum
from http.server import BaseHTTPRequestHandler, HTTPServer
from collections import defaultdict

# --- Domain & Data (Concise Style) ---
class Role(Enum): ADMIN, USER = "ADMIN", "USER"
class Status(Enum): DRAFT, PUBLISHED = "DRAFT", "PUBLISHED"

MOCK_DATA = {"users": {}, "posts": {}}

def setup_data():
    uid1, uid2 = uuid.uuid4(), uuid.uuid4()
    MOCK_DATA["users"][str(uid1)] = {"id":uid1, "email":"a@a.com", "role":Role.ADMIN, "created_at":datetime.now(timezone.utc)}
    MOCK_DATA["users"][str(uid2)] = {"id":uid2, "email":"u@u.com", "role":Role.USER, "created_at":datetime.now(timezone.utc)}
    pid1 = uuid.uuid4()
    MOCK_DATA["posts"][str(pid1)] = {"id":pid1, "user_id":uid2, "title":"Hi", "status":Status.PUBLISHED}

# --- Context Passing Style ---
class Ctx:
    """A context object passed through the middleware chain."""
    def __init__(self, handler):
        self.req = {
            "handler": handler,
            "method": handler.command,
            "path": handler.path,
            "headers": handler.headers,
            "body": None,
            "params": {}
        }
        self.res = {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "body": ""
        }
        self.error = None

# --- Middleware Chain ---
def apply_middleware(handler_func, *middlewares):
    """Applies a list of middlewares to a handler function."""
    def final_handler(ctx):
        handler_func(ctx)
        return ctx

    wrapped = final_handler
    for mw in reversed(middlewares):
        wrapped = mw(wrapped)
    return wrapped

# Middleware Implementations
def mw_error_handler(next_func):
    def wrapper(ctx: Ctx):
        try:
            return next_func(ctx)
        except Exception as e:
            sys.stderr.write(f"Unhandled Exception: {e}\n")
            ctx.res["status"] = 500
            ctx.res["body"] = json.dumps({"error": "Internal Error"})
            return ctx
    return wrapper

def mw_transform(next_func):
    def wrapper(ctx: Ctx):
        # Req transform
        content_len = int(ctx.req["headers"].get('Content-Length', 0))
        if content_len > 0:
            raw_body = ctx.req["handler"].rfile.read(content_len)
            ctx.req["body"] = json.loads(raw_body)
        
        ctx = next_func(ctx)
        
        # Res transform
        if 200 <= ctx.res["status"] < 300 and ctx.res["body"]:
            data = json.loads(ctx.res["body"])
            ctx.res["body"] = json.dumps({"ok": True, "result": data})
        return ctx
    return wrapper

class RateLimiter:
    def __init__(self, limit=10, period=60):
        self.limit = limit
        self.period = period
        self.tracker = defaultdict(list)

    def __call__(self, next_func):
        def wrapper(ctx: Ctx):
            ip = ctx.req["handler"].client_address[0]
            now = time.time()
            self.tracker[ip] = [t for t in self.tracker[ip] if now - t < self.period]
            if len(self.tracker[ip]) >= self.limit:
                ctx.res["status"] = 429
                ctx.res["body"] = json.dumps({"error": "Rate limited"})
                return ctx
            self.tracker[ip].append(now)
            return next_func(ctx)
        return wrapper

def mw_cors(next_func):
    def wrapper(ctx: Ctx):
        cors_hdrs = {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        }
        if ctx.req["method"] == 'OPTIONS':
            ctx.res["status"] = 204
            ctx.res["headers"].update(cors_hdrs)
            return ctx
        
        ctx = next_func(ctx)
        ctx.res["headers"].update(cors_hdrs)
        return ctx
    return wrapper

def mw_logger(next_func):
    def wrapper(ctx: Ctx):
        print(f"-> {ctx.req['method']} {ctx.req['path']}")
        ctx = next_func(ctx)
        print(f"<- {ctx.res['status']}")
        return ctx
    return wrapper

# --- Handlers ---
def get_user(ctx: Ctx):
    user_id = ctx.req["params"].get("user_id")
    user = MOCK_DATA["users"].get(user_id)
    if not user:
        ctx.res["status"] = 404
        ctx.res["body"] = json.dumps({"error": "user not found"})
        return
    
    def json_enc(o):
        if isinstance(o, (datetime, uuid.UUID)): return str(o)
        if isinstance(o, Enum): return o.value
        raise TypeError
    ctx.res["body"] = json.dumps(user, default=json_enc)

def create_post(ctx: Ctx):
    body = ctx.req["body"]
    if not body or not body.get("user_id"):
        ctx.res["status"] = 400
        ctx.res["body"] = json.dumps({"error": "user_id is required"})
        return
    
    post_id = uuid.uuid4()
    MOCK_DATA["posts"][str(post_id)] = {
        "id": post_id, "user_id": uuid.UUID(body["user_id"]),
        "title": body.get("title", "No Title"), "status": Status.DRAFT
    }
    ctx.res["status"] = 201
    ctx.res["body"] = json.dumps({"created_id": str(post_id)})

# --- Router & Server ---
class ServiceHandler(BaseHTTPRequestHandler):
    
    def route(self):
        path = self.path.strip("/").split("/")
        
        # Define middleware stack
        rate_limiter = RateLimiter(limit=30, period=60)
        stack = [mw_error_handler, mw_transform, rate_limiter, mw_cors, mw_logger]
        
        # Routing
        if self.command == 'GET' and len(path) == 2 and path[0] == 'users':
            handler = apply_middleware(get_user, *stack)
            params = {"user_id": path[1]}
        elif self.command == 'POST' and self.path == '/posts':
            handler = apply_middleware(create_post, *stack)
            params = {}
        else:
            return None, None

        return handler, params

    def process(self):
        handler, params = self.route()
        if not handler:
            self.send_error(404, "Not Found")
            return
        
        ctx = Ctx(self)
        ctx.req["params"] = params
        
        final_ctx = handler(ctx)
        
        self.send_response(final_ctx.res["status"])
        for k, v in final_ctx.res["headers"].items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(final_ctx.res["body"].encode('utf-8'))

    def do_GET(self): self.process()
    def do_POST(self): self.process()
    def do_OPTIONS(self): self.process()

def launch_service(port=8003):
    setup_data()
    server_address = ('', port)
    httpd = HTTPServer(server_address, ServiceHandler)
    print(f"Starting context-passing server on port {port}...")
    print("Example UUID from mock DB:", next(iter(MOCK_DATA["users"])))
    httpd.serve_forever()

if __name__ == '__main__':
    # launch_service()
    pass