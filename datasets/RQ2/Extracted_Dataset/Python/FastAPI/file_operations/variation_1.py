import uuid
import csv
import io
import tempfile
import shutil
from datetime import datetime
from enum import Enum
from typing import List, Iterator, Generator, Dict, Any

import openpyxl
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
    is_active: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)

class Post(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Mock Database & Authentication ---

MOCK_DB_USERS: Dict[uuid.UUID, User] = {}
MOCK_DB_POSTS: Dict[uuid.UUID, Post] = {}

def get_current_admin_user() -> User:
    """A mock dependency to simulate getting an authenticated admin user."""
    user = User(email="admin@example.com", password_hash="hashed_password", role=UserRole.ADMIN)
    if user.id not in MOCK_DB_USERS:
        MOCK_DB_USERS[user.id] = user
    return user

# --- API Router: Functional/Procedural Style ---

router = APIRouter(prefix="/files", tags=["File Operations"])

@router.post("/upload/users/csv", status_code=status.HTTP_201_CREATED)
def upload_users_from_csv(
    file: UploadFile = File(...),
    current_user: User = Depends(get_current_admin_user)
):
    """
    Handles CSV file upload to bulk-create users.
    Uses a temporary file to store and process the upload.
    """
    if not file.filename.endswith('.csv'):
        raise HTTPException(status_code=400, detail="Invalid file type. Only CSV is accepted.")

    created_users = []
    try:
        # Use a temporary file to handle potentially large uploads
        with tempfile.NamedTemporaryFile(delete=False, mode='w+', suffix=".csv", encoding='utf-8') as temp_f:
            shutil.copyfileobj(file.file, temp_f)
            temp_f.seek(0)
            reader = csv.DictReader(temp_f)
            for row in reader:
                if 'email' not in row or 'password_hash' not in row:
                    continue
                new_user = User(email=row['email'], password_hash=row['password_hash'])
                MOCK_DB_USERS[new_user.id] = new_user
                created_users.append(new_user.id)
    finally:
        file.file.close()

    return {"message": f"Successfully created {len(created_users)} users.", "user_ids": created_users}

@router.post("/upload/posts/{post_id}/image", status_code=status.HTTP_200_OK)
def upload_post_image(
    post_id: uuid.UUID,
    file: UploadFile = File(...),
    current_user: User = Depends(get_current_admin_user)
):
    """
    Handles image upload for a post, resizes it, and saves it (mocked).
    Processes the image in memory.
    """
    if file.content_type not in ["image/jpeg", "image/png"]:
        raise HTTPException(status_code=400, detail="Invalid image type. Use JPEG or PNG.")

    # Mock check if post exists
    if post_id not in MOCK_DB_POSTS:
        # Create a mock post for demonstration
        mock_post = Post(id=post_id, user_id=current_user.id, title="Mock Post", content="...")
        MOCK_DB_POSTS[post_id] = mock_post

    try:
        image_bytes = file.file.read()
        image = Image.open(io.BytesIO(image_bytes))
        
        # Resize image to a standard width, maintaining aspect ratio
        base_width = 800
        w_percent = (base_width / float(image.size[0]))
        h_size = int((float(image.size[1]) * float(w_percent)))
        resized_image = image.resize((base_width, h_size), Image.Resampling.LANCZOS)

        # In a real app, you'd save this to a file system or cloud storage
        # For this example, we'll just confirm it worked.
        output_buffer = io.BytesIO()
        resized_image.save(output_buffer, format="JPEG")
        resized_size = output_buffer.tell()

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Image processing failed: {e}")
    finally:
        file.file.close()

    return {
        "message": f"Image for post {post_id} processed successfully.",
        "original_size": f"{file.size} bytes",
        "resized_size": f"{resized_size} bytes",
        "new_dimensions": f"{resized_image.width}x{resized_image.height}"
    }

@router.get("/download/posts/report/excel")
def download_posts_report_excel(current_user: User = Depends(get_current_admin_user)):
    """
    Generates an Excel report of all posts and provides it for download.
    Uses StreamingResponse for efficient memory usage.
    """
    def generate_excel_stream() -> Generator[bytes, Any, None]:
        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "Posts Report"
        
        headers = ["id", "user_id", "title", "status", "content"]
        sheet.append(headers)

        for post in MOCK_DB_POSTS.values():
            sheet.append([str(post.id), str(post.user_id), post.title, post.status.value, post.content])

        # Stream the workbook to a memory buffer
        with tempfile.NamedTemporaryFile() as tmp:
            workbook.save(tmp.name)
            tmp.seek(0)
            stream = tmp.read()
            yield stream

    # Add a mock post if none exist for the report
    if not MOCK_DB_POSTS:
        mock_post = Post(user_id=current_user.id, title="First Post", content="Content of the first post.")
        MOCK_DB_POSTS[mock_post.id] = mock_post

    response_headers = {
        "Content-Disposition": f"attachment; filename=posts_report_{datetime.utcnow().isoformat()}.xlsx"
    }
    return StreamingResponse(
        generate_excel_stream(),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers=response_headers
    )

# --- Main Application ---
app = FastAPI(title="Variation 1: Functional Approach")
app.include_router(router)

if __name__ == "__main__":
    import uvicorn
    print("Running Variation 1: Functional Approach")
    print("Try endpoints at http://127.0.0.1:8000/docs")
    uvicorn.run(app, host="127.0.0.1", port=8000)