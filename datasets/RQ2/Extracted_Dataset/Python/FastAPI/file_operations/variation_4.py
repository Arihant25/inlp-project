import uuid
import csv
import io
import tempfile
from datetime import datetime
from enum import Enum
from typing import List, Dict, AsyncGenerator
from pathlib import Path

import aiofiles
from PIL import Image
from fastapi import FastAPI, APIRouter, UploadFile, File, Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# --- Domain Schema & Models ---

class UserRole(str, Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(str, Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class UserModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    password_hash: str
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)

class PostModel(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Mock Database & Auth ---

DB_USERS: Dict[uuid.UUID, UserModel] = {}
DB_POSTS: Dict[uuid.UUID, PostModel] = {}

async def get_current_admin() -> UserModel:
    """Async mock dependency for an authenticated admin."""
    user = UserModel(email="async.admin@example.com", password_hash="hashed", role=UserRole.ADMIN)
    if user.id not in DB_USERS:
        DB_USERS[user.id] = user
    return user

# --- Async Helper Functions ---

async def save_upload_to_temp(upload_file: UploadFile) -> Path:
    """Asynchronously saves an UploadFile to a temporary file."""
    temp_dir = Path(tempfile.gettempdir())
    temp_path = temp_dir / f"{uuid.uuid4()}_{upload_file.filename}"
    try:
        async with aiofiles.open(temp_path, 'wb') as f:
            while chunk := await upload_file.read(1024 * 1024):  # Read in 1MB chunks
                await f.write(chunk)
    finally:
        await upload_file.close()
    return temp_path

# --- API Routers: Async-First & Modular ---

users_router = APIRouter(prefix="/async/users", tags=["Async User Files"])
posts_router = APIRouter(prefix="/async/posts", tags=["Async Post Files"])

@users_router.post("/import_csv", status_code=status.HTTP_202_ACCEPTED)
async def import_users_csv(
    file: UploadFile = File(..., description="CSV file with 'email' and 'password_hash' columns"),
    admin: UserModel = Depends(get_current_admin)
):
    """
    Asynchronously handles CSV upload. Uses a thread pool for synchronous parsing
    to avoid blocking the event loop.
    """
    if not file.filename.endswith('.csv'):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Must be a CSV file.")

    temp_file_path = await save_upload_to_temp(file)

    def sync_parse_csv(path: Path) -> List[uuid.UUID]:
        created_ids = []
        with open(path, mode='r', encoding='utf-8') as f:
            reader = csv.DictReader(f)
            for row in reader:
                user = UserModel(email=row['email'], password_hash=row['password_hash'])
                DB_USERS[user.id] = user
                created_ids.append(user.id)
        path.unlink() # Clean up temp file
        return created_ids

    # Run the blocking file I/O and parsing in a thread pool
    created_user_ids = await run_in_threadpool(sync_parse_csv, temp_file_path)
    
    return {"message": "File accepted and is being processed.", "created_users_count": len(created_user_ids)}

@posts_router.post("/{post_id}/image", status_code=status.HTTP_200_OK)
async def upload_post_image(
    post_id: uuid.UUID,
    file: UploadFile = File(..., description="Image file to be resized"),
    admin: UserModel = Depends(get_current_admin)
):
    """
    Asynchronously processes an image upload, using a thread pool for the
    CPU-bound image resizing task.
    """
    if not file.content_type.startswith("image/"):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "File must be an image.")
    
    # Mock post creation
    if post_id not in DB_POSTS:
        DB_POSTS[post_id] = PostModel(id=post_id, user_id=admin.id, title="A Post", content="")

    image_bytes = await file.read()

    def sync_resize_image(img_bytes: bytes) -> bytes:
        with Image.open(io.BytesIO(img_bytes)) as img:
            img.thumbnail((1024, 1024))
            buffer = io.BytesIO()
            img.save(buffer, format='WEBP', quality=80)
            return buffer.getvalue()

    # Run blocking Pillow operations in a thread pool
    resized_image_bytes = await run_in_threadpool(sync_resize_image, image_bytes)
    
    # In a real app, you'd now save `resized_image_bytes` to storage asynchronously.
    return {
        "message": "Image resized successfully.",
        "post_id": post_id,
        "original_size": len(image_bytes),
        "new_size": len(resized_image_bytes),
        "new_format": "WEBP"
    }

@posts_router.get("/report/download")
async def download_posts_report(admin: UserModel = Depends(get_current_admin)):
    """
    Streams a large CSV report using an async generator.
    """
    async def report_generator() -> AsyncGenerator[bytes, None]:
        # Add mock data if empty
        if not DB_POSTS:
            DB_POSTS[uuid.uuid4()] = PostModel(user_id=admin.id, title="Async Post", content="...")

        header = ",".join(PostModel.model_fields.keys()) + "\n"
        yield header.encode('utf-8')
        
        for post in DB_POSTS.values():
            # In a real scenario with a large dataset, you might fetch posts in chunks.
            values = [str(v) for v in post.model_dump().values()]
            line = ",".join(values) + "\n"
            yield line.encode('utf-8')

    filename = f"async_report_{datetime.utcnow().isoformat()}.csv"
    headers = {"Content-Disposition": f"attachment; filename={filename}"}
    return StreamingResponse(report_generator(), media_type="text/csv", headers=headers)

# --- Main Application ---
app = FastAPI(title="Variation 4: Async-First & Modern Approach")
app.include_router(users_router)
app.include_router(posts_router)

if __name__ == "__main__":
    import uvicorn
    print("Running Variation 4: Async-First & Modern Approach")
    print("Try endpoints at http://127.0.0.1:8000/docs")
    uvicorn.run(app, host="127.0.0.1", port=8000)