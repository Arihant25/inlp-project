import uuid
import csv
import io
import tempfile
import shutil
from contextlib import contextmanager
from datetime import datetime
from enum import Enum
from typing import List, Iterator, Dict, Generator, Any

from PIL import Image
from fastapi import FastAPI, APIRouter, UploadFile, File, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# --- Domain Schema & Models ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    password_hash: str
    role: UserRole = UserRole.USER

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Mock Data Store ---
MOCK_USERS_DB: Dict[uuid.UUID, User] = {}
MOCK_POSTS_DB: Dict[uuid.UUID, Post] = {}

# --- Dependencies for Logic and Resource Management ---

def get_current_admin() -> User:
    """Dependency to simulate an authenticated admin user."""
    user = User(email="dependency.admin@example.com", password_hash="hashed", role=UserRole.ADMIN)
    if user.id not in MOCK_USERS_DB:
        MOCK_USERS_DB[user.id] = user
    return user

@contextmanager
def get_temporary_file_path(suffix: str = ".tmp") -> Iterator[str]:
    """Provides a temporary file path and ensures cleanup."""
    try:
        tf = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        tf.close()
        yield tf.name
    finally:
        shutil.rmtree(tf.name, ignore_errors=True)

def parse_csv_upload(file: UploadFile = File(...)) -> List[Dict[str, str]]:
    """Dependency to parse a CSV file, ensuring it's closed."""
    if not file.filename.endswith('.csv'):
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Only CSV files are supported.")
    try:
        content = file.file.read().decode('utf-8')
        reader = csv.DictReader(io.StringIO(content))
        return list(reader)
    except Exception as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, f"Failed to parse CSV: {e}")
    finally:
        file.file.close()

def process_image_in_memory(file: UploadFile = File(...)) -> io.BytesIO:
    """Dependency to resize an image entirely in memory."""
    if not file.content_type.startswith("image/"):
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Only image files are supported.")
    try:
        image = Image.open(file.file)
        image.thumbnail((400, 400)) # Resize in-place to a max 400x400
        
        output_buffer = io.BytesIO()
        image.save(output_buffer, format="PNG")
        output_buffer.seek(0)
        return output_buffer
    finally:
        file.file.close()

def get_posts_report_generator(admin: User = Depends(get_current_admin)) -> Generator[str, Any, None]:
    """Dependency that yields lines of a CSV report."""
    # Add mock data if DB is empty
    if not MOCK_POSTS_DB:
        post = Post(user_id=admin.id, title="Sample Post", content="Hello World")
        MOCK_POSTS_DB[post.id] = post

    output = io.StringIO()
    writer = csv.writer(output)
    
    headers = Post.model_fields.keys()
    writer.writerow(headers)
    yield output.getvalue() # Yield headers first
    output.seek(0); output.truncate(0) # Reset buffer

    for post in MOCK_POSTS_DB.values():
        writer.writerow([getattr(post, h) for h in headers])
        yield output.getvalue()
        output.seek(0); output.truncate(0)

# --- API Router: Dependency-Injected Style ---

router = APIRouter(prefix="/di", tags=["File Operations (DI)"])

@router.post("/users/batch-create", status_code=status.HTTP_201_CREATED)
def create_users_from_csv(
    parsed_data: List[Dict[str, str]] = Depends(parse_csv_upload),
    admin: User = Depends(get_current_admin)
):
    """Endpoint logic is minimal; dependencies do the work."""
    new_user_ids = []
    for row in parsed_data:
        if "email" not in row or "password_hash" not in row:
            continue
        user = User(email=row['email'], password_hash=row['password_hash'])
        MOCK_USERS_DB[user.id] = user
        new_user_ids.append(user.id)
    return {"detail": f"Processed {len(parsed_data)} rows. Created {len(new_user_ids)} users.", "ids": new_user_ids}

@router.post("/posts/{post_id}/thumbnail")
def upload_post_thumbnail(
    post_id: uuid.UUID,
    resized_image_stream: io.BytesIO = Depends(process_image_in_memory),
    admin: User = Depends(get_current_admin)
):
    """Endpoint receives an already-processed image stream from a dependency."""
    # In a real app, you would save this stream to storage.
    # e.g., s3_client.upload_fileobj(resized_image_stream, 'my-bucket', f'thumbnails/{post_id}.png')
    image_size = resized_image_stream.getbuffer().nbytes
    return {"detail": f"Thumbnail for post {post_id} created.", "size_bytes": image_size}

@router.get("/posts/report/stream")
def download_report_via_dependencies(
    report_generator: Generator = Depends(get_posts_report_generator)
):
    """The endpoint just serves the generator provided by the dependency."""
    return StreamingResponse(
        report_generator,
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=posts_report.csv"}
    )

# --- Main Application ---
app = FastAPI(title="Variation 3: Dependency Injection Approach")
app.include_router(router)

if __name__ == "__main__":
    import uvicorn
    print("Running Variation 3: Dependency Injection Approach")
    print("Try endpoints at http://127.0.0.1:8000/docs")
    uvicorn.run(app, host="127.0.0.1", port=8000)