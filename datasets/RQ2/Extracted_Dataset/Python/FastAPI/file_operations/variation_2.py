import uuid
import pandas as pd
import io
import tempfile
from datetime import datetime
from enum import Enum
from typing import List, Dict, Any, Generator

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

class UserSchema(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    email: str
    password_hash: str
    role: UserRole = UserRole.USER
    is_active: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)

class PostSchema(BaseModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    user_id: uuid.UUID
    title: str
    content: str
    status: PostStatus = PostStatus.DRAFT

# --- Mock Database & Authentication ---

MOCK_DB_USERS: Dict[uuid.UUID, UserSchema] = {}
MOCK_DB_POSTS: Dict[uuid.UUID, PostSchema] = {}

def get_current_admin_user() -> UserSchema:
    """A mock dependency to simulate getting an authenticated admin user."""
    user = UserSchema(email="admin@example.com", password_hash="hashed_password", role=UserRole.ADMIN)
    if user.id not in MOCK_DB_USERS:
        MOCK_DB_USERS[user.id] = user
    return user

# --- Service Layer: OOP Style ---

class FileProcessingService:
    """Encapsulates file-related business logic."""

    def processUsersSpreadsheet(self, file: UploadFile) -> List[uuid.UUID]:
        """Parses a CSV or Excel file to create users."""
        created_user_ids = []
        try:
            if file.filename.endswith('.csv'):
                df = pd.read_csv(file.file)
            elif file.filename.endswith(('.xls', '.xlsx')):
                df = pd.read_excel(file.file)
            else:
                raise ValueError("Unsupported file type")

            required_columns = {'email', 'password_hash'}
            if not required_columns.issubset(df.columns):
                raise ValueError(f"Missing required columns: {required_columns - set(df.columns)}")

            for index, row in df.iterrows():
                newUser = UserSchema(email=row['email'], password_hash=row['password_hash'])
                MOCK_DB_USERS[newUser.id] = newUser
                created_user_ids.append(newUser.id)
        finally:
            file.file.close()
        return created_user_ids

    def resizeAndStoreImage(self, file: UploadFile, target_width: int = 800) -> Dict[str, Any]:
        """Resizes an image and returns metadata."""
        try:
            contents = file.file.read()
            image = Image.open(io.BytesIO(contents))
            
            aspect_ratio = image.height / image.width
            target_height = int(target_width * aspect_ratio)
            
            resized_image = image.resize((target_width, target_height), Image.Resampling.LANCZOS)

            # In a real app, this would save to S3/local disk. We mock this.
            output_buffer = io.BytesIO()
            resized_image.save(output_buffer, format="PNG")
            
            return {
                "original_size": file.size,
                "resized_size": output_buffer.tell(),
                "new_dimensions": f"{resized_image.width}x{resized_image.height}"
            }
        finally:
            file.file.close()

    def generatePostsReportStream(self) -> Generator[bytes, Any, None]:
        """Generates a CSV report of posts as a byte stream."""
        # Add a mock post if none exist for the report
        if not MOCK_DB_POSTS:
            admin = get_current_admin_user()
            mock_post = PostSchema(user_id=admin.id, title="First Post", content="Content of the first post.")
            MOCK_DB_POSTS[mock_post.id] = mock_post
            
        posts_data = [post.model_dump(mode='json') for post in MOCK_DB_POSTS.values()]
        if not posts_data:
            yield b""
            return
            
        df = pd.DataFrame(posts_data)
        # Convert UUIDs and enums to strings for CSV
        df['id'] = df['id'].astype(str)
        df['user_id'] = df['user_id'].astype(str)
        df['status'] = df['status'].astype(str)
        
        stream = io.StringIO()
        df.to_csv(stream, index=False)
        yield stream.getvalue().encode('utf-8')


# --- API Router: Thin Controller using Service ---

file_router = APIRouter(tags=["File Operations (OOP)"])

@file_router.post("/v2/users/import", status_code=status.HTTP_201_CREATED)
def import_users_from_file(
    file: UploadFile = File(..., description="CSV or Excel file with user data"),
    service: FileProcessingService = Depends(FileProcessingService),
    current_user: UserSchema = Depends(get_current_admin_user)
):
    try:
        user_ids = service.processUsersSpreadsheet(file)
        return {"message": f"Successfully imported {len(user_ids)} users.", "user_ids": user_ids}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"An unexpected error occurred: {e}")

@file_router.put("/v2/posts/{post_id}/image")
def update_post_image(
    post_id: uuid.UUID,
    file: UploadFile = File(..., description="JPEG or PNG image"),
    service: FileProcessingService = Depends(FileProcessingService),
    current_user: UserSchema = Depends(get_current_admin_user)
):
    if file.content_type not in ["image/jpeg", "image/png"]:
        raise HTTPException(status_code=415, detail="Unsupported media type. Please upload a JPEG or PNG.")
    
    # Mock check if post exists
    if post_id not in MOCK_DB_POSTS:
        mock_post = PostSchema(id=post_id, user_id=current_user.id, title="Mock Post", content="...")
        MOCK_DB_POSTS[post_id] = mock_post

    result = service.resizeAndStoreImage(file)
    return {"post_id": post_id, "image_processing_result": result}

@file_router.get("/v2/posts/report/download")
def download_posts_report(service: FileProcessingService = Depends(FileProcessingService)):
    filename = f"posts_report_{datetime.utcnow().strftime('%Y%m%d')}.csv"
    return StreamingResponse(
        service.generatePostsReportStream(),
        media_type="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )

# --- Main Application ---
app = FastAPI(title="Variation 2: OOP/Class-Based Approach")
app.include_router(file_router)

if __name__ == "__main__":
    import uvicorn
    print("Running Variation 2: OOP/Class-Based Approach")
    print("Try endpoints at http://127.0.0.1:8000/docs")
    uvicorn.run(app, host="127.0.0.1", port=8000)