import os
import uuid
import csv
import tempfile
from datetime import datetime
from enum import Enum
from io import StringIO

import pandas as pd
from flask import (Flask, request, jsonify, Blueprint, current_app, Response,
                   stream_with_context)
from werkzeug.utils import secure_filename
from PIL import Image

# --- Domain Models & Mock Data ---
class UserRole(Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

# This would typically be in a database, but we mock it here for simplicity.
MOCK_DB = {
    "users": {},
    "posts": {
        str(uuid.uuid4()): {
            "id": str(uuid.uuid4()), "user_id": str(uuid.uuid4()), "title": "Getting Started with Flask",
            "content": "...", "status": PostStatus.PUBLISHED
        }
    }
}

# --- Utility/Service Functions (could be in a separate 'utils.py') ---
def allowed_file(filename, key):
    """Checks file extension against configuration."""
    allowed = current_app.config[key]
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in allowed

def parse_user_data(file_path, extension):
    """Parses a CSV or Excel file and returns a list of user dicts."""
    users_to_add = []
    if extension == 'csv':
        df = pd.read_csv(file_path)
    elif extension == 'xlsx':
        df = pd.read_excel(file_path)
    else:
        return []

    for _, row in df.iterrows():
        new_user = {
            "id": str(uuid.uuid4()),
            "email": row.get('email'),
            "password_hash": "not-set",
            "role": UserRole.USER,
            "is_active": True,
            "created_at": datetime.utcnow()
        }
        users_to_add.append(new_user)
    return users_to_add

def resize_image(image_stream, output_path):
    """Resizes an image and saves it to the output path."""
    size = current_app.config['THUMBNAIL_DIMENSIONS']
    with Image.open(image_stream) as img:
        img.thumbnail(size)
        img.save(output_path)

# --- Blueprint Definition ---
files_bp = Blueprint('files', __name__, url_prefix='/files')

@files_bp.route('/upload/users', methods=['POST'])
def upload_users():
    if 'file' not in request.files:
        return jsonify(error="No file part"), 400
    file = request.files['file']
    if file.filename == '':
        return jsonify(error="No selected file"), 400

    if file and allowed_file(file.filename, 'ALLOWED_DATA_EXTENSIONS'):
        filename = secure_filename(file.filename)
        ext = filename.rsplit('.', 1)[1].lower()
        
        # Use a temporary directory for safe handling
        with tempfile.TemporaryDirectory() as tmpdir:
            temp_path = os.path.join(tmpdir, filename)
            file.save(temp_path)
            
            try:
                new_users = parse_user_data(temp_path, ext)
                for user in new_users:
                    MOCK_DB['users'][user['id']] = user
                return jsonify(
                    message=f"Successfully added {len(new_users)} users.",
                    total_users=len(MOCK_DB['users'])
                ), 201
            except Exception as e:
                current_app.logger.error(f"File parsing failed: {e}")
                return jsonify(error="Failed to process file"), 500
    
    return jsonify(error="File type not allowed"), 400

@files_bp.route('/upload/avatar/<user_id>', methods=['POST'])
def upload_avatar(user_id):
    if 'avatar' not in request.files:
        return jsonify(error="No avatar part"), 400
    file = request.files['avatar']
    if not (file and allowed_file(file.filename, 'ALLOWED_IMAGE_EXTENSIONS')):
        return jsonify(error="Invalid image format"), 400

    upload_folder = current_app.config['UPLOAD_FOLDER']
    filename = secure_filename(f"{user_id}.jpg")
    thumb_filename = secure_filename(f"{user_id}_thumb.jpg")
    
    original_path = os.path.join(upload_folder, filename)
    thumb_path = os.path.join(upload_folder, thumb_filename)

    file.save(original_path)
    # Reset stream pointer to read for resizing
    file.stream.seek(0)
    resize_image(file.stream, thumb_path)

    return jsonify(message="Avatar uploaded", avatar_url=f"/{original_path}", thumb_url=f"/{thumb_path}"), 200

@files_bp.route('/download/posts', methods=['GET'])
def download_posts():
    def generate():
        data = StringIO()
        writer = csv.writer(data)
        
        writer.writerow(('id', 'user_id', 'title', 'status'))
        yield data.getvalue()
        data.seek(0)
        data.truncate(0)

        for post in MOCK_DB['posts'].values():
            writer.writerow([post['id'], post['user_id'], post['title'], post['status'].value])
            yield data.getvalue()
            data.seek(0)
            data.truncate(0)

    headers = {"Content-Disposition": "attachment; filename=posts_report.csv"}
    return Response(stream_with_context(generate()), mimetype='text/csv', headers=headers)

# --- Application Factory ---
def create_app():
    app = Flask(__name__)
    
    # Configuration
    app.config['UPLOAD_FOLDER'] = 'uploads_bp'
    app.config['ALLOWED_DATA_EXTENSIONS'] = {'csv', 'xlsx'}
    app.config['ALLOWED_IMAGE_EXTENSIONS'] = {'png', 'jpg', 'jpeg'}
    app.config['THUMBNAIL_DIMENSIONS'] = (128, 128)

    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])
        
    # Register Blueprints
    app.register_blueprint(files_bp)

    return app

if __name__ == '__main__':
    application = create_app()
    application.run(debug=True, port=5001)