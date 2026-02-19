import os
import uuid
import csv
import tempfile
from datetime import datetime
from enum import Enum

import pandas as pd
from flask import Flask, request, jsonify, send_file, Response
from werkzeug.utils import secure_filename
from PIL import Image

# --- Configuration and Setup ---
UPLOAD_FOLDER = 'uploads'
ALLOWED_EXTENSIONS_DATA = {'csv', 'xlsx'}
ALLOWED_EXTENSIONS_IMG = {'png', 'jpg', 'jpeg', 'gif'}
THUMBNAIL_SIZE = (128, 128)

app = Flask(__name__)
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16 MB max upload size

if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)

# --- Domain Models & Mock Data ---
class UserRole(Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

# In-memory "database"
MOCK_USERS = {
    str(uuid.uuid4()): {
        "id": str(uuid.uuid4()), "email": "admin@example.com", "password_hash": "...",
        "role": UserRole.ADMIN, "is_active": True, "created_at": datetime.utcnow()
    }
}
MOCK_POSTS = {
    str(uuid.uuid4()): {
        "id": str(uuid.uuid4()), "user_id": list(MOCK_USERS.keys())[0], "title": "First Post",
        "content": "This is a test post.", "status": PostStatus.PUBLISHED
    }
}

# --- Helper Functions ---
def is_allowed_file(filename, allowed_extensions):
    """Checks if a file's extension is in the allowed set."""
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in allowed_extensions

# --- Routes ---
@app.route('/users/import', methods=['POST'])
def upload_and_process_user_data_file():
    """
    Handles uploading a CSV or Excel file to bulk-create users.
    """
    if 'file' not in request.files:
        return jsonify({"error": "No file part in the request"}), 400
    
    file = request.files['file']
    if file.filename == '':
        return jsonify({"error": "No file selected"}), 400

    if file and is_allowed_file(file.filename, ALLOWED_EXTENSIONS_DATA):
        filename = secure_filename(file.filename)
        temp_path = os.path.join(tempfile.gettempdir(), filename)
        file.save(temp_path)

        try:
            new_users_count = 0
            if filename.endswith('.csv'):
                with open(temp_path, mode='r', encoding='utf-8') as csvfile:
                    reader = csv.DictReader(csvfile)
                    for row in reader:
                        user_id = str(uuid.uuid4())
                        MOCK_USERS[user_id] = {
                            "id": user_id, "email": row['email'], "password_hash": "default_hash",
                            "role": UserRole.USER, "is_active": True, "created_at": datetime.utcnow()
                        }
                        new_users_count += 1
            elif filename.endswith('.xlsx'):
                df = pd.read_excel(temp_path)
                for index, row in df.iterrows():
                    user_id = str(uuid.uuid4())
                    MOCK_USERS[user_id] = {
                        "id": user_id, "email": row['email'], "password_hash": "default_hash",
                        "role": UserRole.USER, "is_active": True, "created_at": datetime.utcnow()
                    }
                    new_users_count += 1
            
            return jsonify({
                "message": f"Successfully imported {new_users_count} users.",
                "total_users": len(MOCK_USERS)
            }), 201
        except Exception as e:
            return jsonify({"error": f"Failed to process file: {str(e)}"}), 500
        finally:
            if os.path.exists(temp_path):
                os.remove(temp_path) # Cleanup temporary file
    else:
        return jsonify({"error": "File type not allowed"}), 400

@app.route('/users/<user_id>/avatar', methods=['POST'])
def upload_and_resize_user_avatar(user_id):
    """
    Handles uploading and processing an image for a user's avatar.
    """
    if user_id not in MOCK_USERS:
        return jsonify({"error": "User not found"}), 404
        
    if 'avatar' not in request.files:
        return jsonify({"error": "No 'avatar' file part in the request"}), 400
    
    file = request.files['avatar']
    if file.filename == '':
        return jsonify({"error": "No file selected"}), 400

    if file and is_allowed_file(file.filename, ALLOWED_EXTENSIONS_IMG):
        filename = secure_filename(file.filename)
        # Save original and thumbnail with user-specific names
        avatar_filename = f"{user_id}_{filename}"
        thumb_filename = f"{user_id}_thumb_{filename}"
        
        original_path = os.path.join(app.config['UPLOAD_FOLDER'], avatar_filename)
        thumb_path = os.path.join(app.config['UPLOAD_FOLDER'], thumb_filename)

        try:
            file.save(original_path)
            with Image.open(original_path) as img:
                img.thumbnail(THUMBNAIL_SIZE)
                img.save(thumb_path)
            
            return jsonify({
                "message": "Avatar uploaded and thumbnail created successfully.",
                "avatar_url": f"/uploads/{avatar_filename}",
                "thumbnail_url": f"/uploads/{thumb_filename}"
            }), 200
        except Exception as e:
            return jsonify({"error": f"Failed to process image: {str(e)}"}), 500
    else:
        return jsonify({"error": "Image file type not allowed"}), 400

@app.route('/posts/export', methods=['GET'])
def download_posts_as_csv_with_streaming():
    """
    Generates and streams a CSV file of all posts.
    """
    def generate_csv_data():
        # Use a temporary in-memory text buffer
        string_io = csv.writer(tempfile.SpooledTemporaryFile(mode='w+', newline=''))
        
        # Header
        header = ['id', 'user_id', 'title', 'content', 'status']
        string_io.writerow(header)
        yield string_io._buffer.getvalue()
        string_io._buffer.seek(0)
        string_io._buffer.truncate(0)

        # Data rows
        for post in MOCK_POSTS.values():
            row = [post['id'], post['user_id'], post['title'], post['content'], post['status'].value]
            string_io.writerow(row)
            yield string_io._buffer.getvalue()
            string_io._buffer.seek(0)
            string_io._buffer.truncate(0)

    response = Response(generate_csv_data(), mimetype='text/csv')
    response.headers.set("Content-Disposition", "attachment", filename="posts_export.csv")
    return response

if __name__ == '__main__':
    app.run(debug=True)