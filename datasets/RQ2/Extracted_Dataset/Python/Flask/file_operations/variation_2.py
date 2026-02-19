import os
import uuid
import csv
import tempfile
from datetime import datetime
from enum import Enum
from io import StringIO

import pandas as pd
from flask import Flask, request, jsonify, Response, stream_with_context
from flask.views import MethodView
from werkzeug.utils import secure_filename
from PIL import Image

# --- Custom Exceptions ---
class FileOperationError(Exception):
    status_code = 500
    def __init__(self, message, status_code=None, payload=None):
        super().__init__()
        self.message = message
        if status_code is not None:
            self.status_code = status_code
        self.payload = payload
    def to_dict(self):
        rv = dict(self.payload or ())
        rv['error'] = self.message
        return rv

class InvalidFileTypeError(FileOperationError):
    def __init__(self, message="Invalid file type provided."):
        super().__init__(message, status_code=400)

class ParsingError(FileOperationError):
    def __init__(self, message="Error parsing the uploaded file."):
        super().__init__(message, status_code=422)

# --- App Setup ---
app = Flask(__name__)
app.config.update({
    "UPLOAD_DIR": "data_uploads",
    "THUMBNAIL_SIZE": (128, 128),
    "ALLOWED_DATA_EXTENSIONS": {"csv", "xlsx"},
    "ALLOWED_IMAGE_EXTENSIONS": {"png", "jpg", "jpeg"}
})
if not os.path.exists(app.config["UPLOAD_DIR"]):
    os.makedirs(app.config["UPLOAD_DIR"])

# --- Domain Models & Mock DB ---
class UserRole(Enum):
    ADMIN = 'ADMIN'
    USER = 'USER'

class PostStatus(Enum):
    DRAFT = 'DRAFT'
    PUBLISHED = 'PUBLISHED'

DB = {
    "users": {
        "a1b2c3d4": {"id": "a1b2c3d4", "email": "admin@example.com", "role": UserRole.ADMIN}
    },
    "posts": {
        "p1o2s3t4": {"id": "p1o2s3t4", "user_id": "a1b2c3d4", "title": "Hello World", "status": PostStatus.PUBLISHED}
    }
}

# --- Service Layer ---
class FileProcessingService:
    def __init__(self, config):
        self.config = config

    def _parse_csv(self, file_stream):
        stream = StringIO(file_stream.read().decode("UTF8"), newline=None)
        csv_reader = csv.DictReader(stream)
        for row in csv_reader:
            user_id = str(uuid.uuid4())
            DB["users"][user_id] = {"id": user_id, "email": row['email'], "role": UserRole.USER}
        return len(DB["users"])

    def _parse_excel(self, file_path):
        df = pd.read_excel(file_path)
        for _, row in df.iterrows():
            user_id = str(uuid.uuid4())
            DB["users"][user_id] = {"id": user_id, "email": row['email'], "role": UserRole.USER}
        return len(DB["users"])

    def import_users(self, file):
        filename = secure_filename(file.filename)
        ext = filename.rsplit('.', 1)[1].lower()
        if ext not in self.config["ALLOWED_DATA_EXTENSIONS"]:
            raise InvalidFileTypeError()

        with tempfile.NamedTemporaryFile(delete=False, suffix=f".{ext}") as tmp:
            file.save(tmp.name)
            try:
                if ext == 'csv':
                    self._parse_csv(open(tmp.name, 'rb'))
                elif ext == 'xlsx':
                    self._parse_excel(tmp.name)
            except Exception as e:
                raise ParsingError(f"Could not process file: {e}")
            finally:
                os.unlink(tmp.name)
        return len(DB["users"])

class ImageService:
    def __init__(self, config):
        self.upload_dir = config["UPLOAD_DIR"]
        self.thumb_size = config["THUMBNAIL_SIZE"]
        self.allowed_extensions = config["ALLOWED_IMAGE_EXTENSIONS"]

    def process_avatar(self, user_id, image_file):
        if not ('.' in image_file.filename and image_file.filename.rsplit('.', 1)[1].lower() in self.allowed_extensions):
            raise InvalidFileTypeError("Image format not allowed.")
        
        if user_id not in DB["users"]:
            raise FileOperationError("User not found", status_code=404)

        filename = secure_filename(f"{user_id}_avatar.png")
        thumb_filename = secure_filename(f"{user_id}_avatar_thumb.png")
        
        original_path = os.path.join(self.upload_dir, filename)
        thumb_path = os.path.join(self.upload_dir, thumb_filename)

        try:
            with Image.open(image_file.stream) as img:
                img.save(original_path, "PNG")
                img.thumbnail(self.thumb_size)
                img.save(thumb_path, "PNG")
        except Exception as e:
            raise FileOperationError(f"Image processing failed: {e}")
        
        return {"avatar": original_path, "thumbnail": thumb_path}

# --- API Layer (Method-Based Views) ---
class UserImportAPI(MethodView):
    def post(self):
        if 'data_file' not in request.files:
            raise FileOperationError("Missing 'data_file' in request", status_code=400)
        
        file_service = FileProcessingService(app.config)
        total_users = file_service.import_users(request.files['data_file'])
        
        return jsonify({"message": "Import successful", "total_users": total_users}), 201

class UserAvatarAPI(MethodView):
    def post(self, user_id):
        if 'avatar_img' not in request.files:
            raise FileOperationError("Missing 'avatar_img' in request", status_code=400)
        
        image_service = ImageService(app.config)
        paths = image_service.process_avatar(user_id, request.files['avatar_img'])
        
        return jsonify({"message": "Avatar updated", "paths": paths}), 200

class PostExportAPI(MethodView):
    def get(self):
        def generate():
            yield "id,user_id,title,status\n"
            for post in DB["posts"].values():
                yield f"{post['id']},{post['user_id']},{post['title']},{post['status'].value}\n"
        
        headers = {"Content-Disposition": "attachment; filename=posts.csv"}
        return Response(stream_with_context(generate()), mimetype="text/csv", headers=headers)

# --- Error Handling & URL Rules ---
@app.errorhandler(FileOperationError)
def handle_file_operation_error(error):
    response = jsonify(error.to_dict())
    response.status_code = error.status_code
    return response

app.add_url_rule('/import/users', view_func=UserImportAPI.as_view('user_import_api'))
app.add_url_rule('/users/<user_id>/avatar', view_func=UserAvatarAPI.as_view('user_avatar_api'))
app.add_url_rule('/export/posts', view_func=PostExportAPI.as_view('post_export_api'))

if __name__ == '__main__':
    app.run(debug=True)