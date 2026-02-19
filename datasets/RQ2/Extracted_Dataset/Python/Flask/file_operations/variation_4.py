import os
import uuid
import csv
import tempfile
from datetime import datetime
from enum import Enum
from functools import wraps
from typing import List, Dict, Any, Generator, Callable

import pandas as pd
from flask import Flask, request, jsonify, Response, stream_with_context
from werkzeug.utils import secure_filename
from PIL import Image

# --- App Configuration & Setup ---
class Config:
    UPLOAD_PATH = "uploads_minimal"
    ALLOWED_DATA_TYPES = {'csv', 'xlsx'}
    ALLOWED_IMAGE_TYPES = {'png', 'jpg', 'jpeg'}
    THUMB_SIZE = (128, 128)

app = Flask(__name__)
app.config.from_object(Config)
os.makedirs(app.config["UPLOAD_PATH"], exist_ok=True)

# --- Mock Data Structures ---
# Using simple dicts for our in-memory store
db: Dict[str, List[Dict[str, Any]]] = {
    "users": [],
    "posts": [
        {"id": uuid.uuid4(), "user_id": uuid.uuid4(), "title": "Zen of Python", "content": "...", "status": "PUBLISHED"}
    ]
}

# --- Decorators for Validation ---
def require_file(part_name: str, allowed_types: set) -> Callable:
    def decorator(f: Callable) -> Callable:
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if part_name not in request.files:
                return jsonify(err=f"Missing '{part_name}' file part"), 400
            
            file = request.files[part_name]
            if file.filename == '':
                return jsonify(err="No file selected"), 400
            
            ext = file.filename.rsplit('.', 1)[-1].lower()
            if ext not in allowed_types:
                return jsonify(err=f"File type '{ext}' is not allowed"), 400
            
            return f(file, *args, **kwargs)
        return decorated_function
    return decorator

# --- Core Logic Helpers ---
def process_user_file(file_path: str) -> int:
    """Parses user data and adds to our mock DB."""
    df = pd.read_csv(file_path) if file_path.endswith('.csv') else pd.read_excel(file_path)
    for _, row in df.iterrows():
        db["users"].append({
            "id": uuid.uuid4(),
            "email": row['email'],
            "created_at": datetime.utcnow()
        })
    return len(df)

def create_thumbnail(image_path: str, thumb_path: str):
    """Creates a thumbnail for a given image."""
    with Image.open(image_path) as img:
        img.thumbnail(app.config["THUMB_SIZE"])
        img.save(thumb_path)

def generate_posts_csv() -> Generator[str, None, None]:
    """A generator to stream CSV data row by row."""
    yield "id,user_id,title,status\n"
    for post in db["posts"]:
        yield f"{post['id']},{post['user_id']},{post['title']},{post['status']}\n"

# --- API Endpoints ---
@app.route("/users/batch-create", methods=["POST"])
@require_file('user_data', app.config["ALLOWED_DATA_TYPES"])
def batch_create_users(file):
    """Endpoint to upload and process a file of new users."""
    filename = secure_filename(file.filename)
    with tempfile.TemporaryDirectory() as tmpdir:
        path = os.path.join(tmpdir, filename)
        file.save(path)
        try:
            count = process_user_file(path)
            return jsonify(msg=f"Processed {count} new users."), 201
        except Exception as e:
            return jsonify(err=f"Processing failed: {str(e)}"), 500

@app.route("/users/<uuid:user_id>/avatar", methods=["POST"])
@require_file('avatar', app.config["ALLOWED_IMAGE_TYPES"])
def upload_avatar(file, user_id: uuid.UUID):
    """Endpoint to upload and resize a user avatar."""
    ext = file.filename.rsplit('.', 1)[-1].lower()
    filename = f"{user_id}.{ext}"
    thumb_filename = f"{user_id}_thumb.{ext}"
    
    upload_dir = app.config["UPLOAD_PATH"]
    original_path = os.path.join(upload_dir, filename)
    thumb_path = os.path.join(upload_dir, thumb_filename)
    
    file.save(original_path)
    create_thumbnail(original_path, thumb_path)
    
    return jsonify(
        msg="Avatar processed",
        urls={
            "original": f"/{original_path}",
            "thumbnail": f"/{thumb_path}"
        }
    )

@app.route("/posts/download", methods=["GET"])
def download_posts():
    """Streams a CSV of all posts."""
    return Response(
        stream_with_context(generate_posts_csv()),
        mimetype="text/csv",
        headers={"Content-Disposition": "attachment;filename=posts.csv"}
    )

if __name__ == "__main__":
    app.run(debug=True, port=5002)