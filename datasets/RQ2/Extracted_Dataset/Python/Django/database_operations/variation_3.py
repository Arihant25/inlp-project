import os
import sys
import uuid
from datetime import datetime

# --- Boilerplate for running Django models without a full project ---
def setup_django_env():
    if 'DJANGO_SETTINGS_MODULE' in os.environ:
        return

    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'minimal_settings')
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

    from django.conf import settings
    if not settings.configured:
        settings.configure(
            INSTALLED_APPS=[
                'db_operations_v3',
            ],
            DATABASES={
                'default': {
                    'ENGINE': 'django.db.backends.sqlite3',
                    'NAME': ':memory:',
                }
            }
        )

    import django
    django.setup()

    from django.core.management import call_command
    from django.apps import apps
    
    app_config = apps.get_app_config('db_operations_v3')
    app_config.models_module = sys.modules[__name__]

    call_command('makemigrations', 'db_operations_v3', interactive=False, verbosity=0)
    call_command('migrate', interactive=False, verbosity=0)

# --- Django App Configuration ---
from django.apps import AppConfig

class DbOperationsV3AppConfig(AppConfig):
    name = 'db_operations_v3'

# --- Models (Schema Definition) ---
from django.db import models
from django.contrib.auth.hashers import make_password

class Role(models.Model):
    name = models.CharField(max_length=50, unique=True)

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    roles = models.ManyToManyField(Role, related_name='users')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

# --- Variation 3: Functional/Procedural Pattern ---
# This developer prefers simple, composable functions over classes for business logic.
# Logic is grouped by domain in a "utils" or "queries" style.

from django.db import transaction
from django.db.models import Count

# User-related functions
def create_user_account(email, password, role_names):
    user = User(email=email, password_hash=make_password(password))
    user.save()
    roles = Role.objects.filter(name__in=role_names)
    user.roles.set(roles)
    return user

def fetch_user_by_id(user_id):
    return User.objects.filter(id=user_id).first()

def remove_user_account(user_id):
    user = fetch_user_by_id(user_id)
    if user:
        user.delete()
        return True
    return False

# Post-related functions
def create_new_post(user_id, title, content, is_published=False):
    status = Post.Status.PUBLISHED if is_published else Post.Status.DRAFT
    return Post.objects.create(user_id=user_id, title=title, content=content, status=status)

def update_post_content(post_id, new_title, new_content):
    Post.objects.filter(id=post_id).update(title=new_title, content=new_content)
    return Post.objects.get(id=post_id)

# Query functions
def find_users_with_post_count():
    return User.objects.annotate(post_count=Count('posts')).filter(post_count__gt=0)

def find_published_posts_by_keyword(keyword):
    return Post.objects.filter(status=Post.Status.PUBLISHED, content__icontains=keyword)

# Transactional function
@transaction.atomic
def register_user_with_welcome_post(email, password, role_names):
    """A transactional function that composes other functions."""
    print(f"  - Starting transaction for {email}...")
    new_user = create_user_account(email, password, role_names)
    
    # Simulate a potential error
    if '@fail.com' in email:
        raise RuntimeError("Simulated failure during registration.")
    
    create_new_post(new_user.id, "Welcome!", "Thanks for joining.", is_published=True)
    print(f"  - Transaction for {email} completed.")
    return new_user

# --- Main execution block to demonstrate the pattern ---
if __name__ == '__main__':
    setup_django_env()

    print("--- Variation 3: Functional/Procedural Demo ---")

    # Setup: Create Roles
    Role.objects.get_or_create(name='ADMIN')
    Role.objects.get_or_create(name='USER')
    print("Created roles: ADMIN, USER")

    # 1. CREATE
    user1 = create_user_account('user1@example.com', 'pass1', ['USER'])
    print(f"\n[CREATE] Created User: {user1.email}")
    post1 = create_new_post(user1.id, "Functional Style", "About procedural code.", is_published=True)
    post2 = create_new_post(user1.id, "Draft Post", "This is not yet published.")
    print(f"[CREATE] Created Post: '{post1.title}'")

    # 2. READ (Query Building)
    print("\n[READ] Finding users with more than 0 posts:")
    users_with_posts = find_users_with_post_count()
    for u in users_with_posts:
        print(f"  - User: {u.email}, Post Count: {u.post_count}")

    print("\n[READ] Finding published posts with keyword 'procedural':")
    keyword_posts = find_published_posts_by_keyword('procedural')
    for p in keyword_posts:
        print(f"  - Found post: '{p.title}' by {p.user.email}")

    # 3. UPDATE
    print(f"\n[UPDATE] Updating content for post '{post2.title}'...")
    updated_post = update_post_content(post2.id, "Updated Draft", "New content here.")
    print(f"  - Post title is now: '{updated_post.title}'")

    # 4. TRANSACTION & ROLLBACK
    print("\n[TRANSACTION] Attempting a failing transaction...")
    try:
        register_user_with_welcome_post('user@fail.com', 'badpass', ['USER'])
    except RuntimeError as e:
        print(f"  - Caught expected error: {e}")
        user_exists = User.objects.filter(email='user@fail.com').exists()
        print(f"  - User 'user@fail.com' exists? {user_exists} (Should be False due to rollback)")

    print("\n[TRANSACTION] Attempting a successful transaction...")
    new_user = register_user_with_welcome_post('user2@example.com', 'pass2', ['USER'])
    print(f"  - Successfully created user '{new_user.email}' and their welcome post.")
    print(f"  - New user has {new_user.posts.count()} post(s).")

    # 5. DELETE
    print(f"\n[DELETE] Deleting user '{user1.email}'...")
    was_deleted = remove_user_account(user1.id)
    print(f"  - User deletion successful: {was_deleted}")
    user_exists = fetch_user_by_id(user1.id) is not None
    print(f"  - User exists after delete? {user_exists}")