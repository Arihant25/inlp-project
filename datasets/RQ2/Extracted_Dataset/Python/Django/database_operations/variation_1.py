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
                'db_operations_v1',
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
    
    # This is a trick to make makemigrations work in a script
    app_config = apps.get_app_config('db_operations_v1')
    app_config.models_module = sys.modules[__name__]

    # Create migrations in memory
    call_command('makemigrations', 'db_operations_v1', interactive=False, verbosity=0)
    # Apply migrations
    call_command('migrate', interactive=False, verbosity=0)

# --- Django App Configuration ---
from django.apps import AppConfig

class DbOperationsV1AppConfig(AppConfig):
    name = 'db_operations_v1'

# --- Models (Schema Definition) ---
from django.db import models
from django.contrib.auth.hashers import make_password, check_password

class Role(models.Model):
    name = models.CharField(max_length=50, unique=True)
    def __str__(self):
        return self.name

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    roles = models.ManyToManyField(Role, related_name='users')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    def set_password(self, raw_password):
        self.password_hash = make_password(raw_password)

    def check_password(self, raw_password):
        return check_password(raw_password, self.password_hash)

    def __str__(self):
        return self.email

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

    def __str__(self):
        return self.title

# --- Variation 1: Service Layer Pattern ---
# This developer prefers to encapsulate business logic in dedicated service classes.
# It promotes separation of concerns and is easily testable.

from django.db import transaction
from django.db.models import Q

class UserService:
    @staticmethod
    def create_user(email, password, role_names):
        user = User(email=email)
        user.set_password(password)
        user.save()
        roles = Role.objects.filter(name__in=role_names)
        user.roles.set(roles)
        return user

    @staticmethod
    def get_user_by_email(email):
        try:
            return User.objects.get(email=email)
        except User.DoesNotExist:
            return None

    @staticmethod
    def update_user_status(user_id, is_active):
        user = User.objects.get(id=user_id)
        user.is_active = is_active
        user.save(update_fields=['is_active'])
        return user

    @staticmethod
    def delete_user(user_id):
        User.objects.filter(id=user_id).delete()

    @staticmethod
    def find_admins_with_posts():
        return User.objects.filter(
            Q(roles__name='ADMIN') & Q(is_active=True)
        ).prefetch_related('posts').distinct()

class PostService:
    @staticmethod
    def create_post(user, title, content, status=Post.Status.DRAFT):
        return Post.objects.create(user=user, title=title, content=content, status=status)

    @staticmethod
    def get_posts_for_user(user_id):
        return Post.objects.filter(user_id=user_id).order_by('-id')

    @staticmethod
    def publish_post(post_id):
        post = Post.objects.get(id=post_id)
        post.status = Post.Status.PUBLISHED
        post.save(update_fields=['status'])
        return post

class TransactionalService:
    @staticmethod
    @transaction.atomic
    def create_user_and_first_post(email, password, role_names, post_title, post_content):
        """
        Creates a user and their first post in a single transaction.
        If post creation fails, user creation is rolled back.
        """
        user = UserService.create_user(email, password, role_names)
        if not post_title:
            # Simulate a failure condition
            raise ValueError("Post title cannot be empty.")
        post = PostService.create_post(user, post_title, post_content, status=Post.Status.PUBLISHED)
        return user, post

# --- Main execution block to demonstrate the pattern ---
if __name__ == '__main__':
    setup_django_env()

    print("--- Variation 1: Service Layer Demo ---")

    # Setup: Create Roles
    admin_role, _ = Role.objects.get_or_create(name='ADMIN')
    user_role, _ = Role.objects.get_or_create(name='USER')
    print(f"Created roles: {admin_role.name}, {user_role.name}")

    # 1. CREATE
    user_service = UserService()
    post_service = PostService()
    
    admin_user = user_service.create_user('admin@example.com', 'securepass123', ['ADMIN', 'USER'])
    print(f"\n[CREATE] Created Admin User: {admin_user.email} with ID {admin_user.id}")
    
    post1 = post_service.create_post(admin_user, "Django Patterns", "Exploring service layers.")
    post2 = post_service.create_post(admin_user, "ORM Basics", "An intro to Django ORM.", status=Post.Status.PUBLISHED)
    print(f"[CREATE] Created Post: '{post1.title}' with status {post1.status}")
    print(f"[CREATE] Created Post: '{post2.title}' with status {post2.status}")

    # 2. READ (Query Building)
    print("\n[READ] Finding active admins with posts:")
    admins = user_service.find_admins_with_posts()
    for admin in admins:
        print(f"  - Admin: {admin.email}, Posts: {[p.title for p in admin.posts.all()]}")

    # 3. UPDATE
    print(f"\n[UPDATE] Publishing post '{post1.title}'...")
    post_service.publish_post(post1.id)
    updated_post = Post.objects.get(id=post1.id)
    print(f"  - Post status is now: {updated_post.status}")

    # 4. TRANSACTION & ROLLBACK
    print("\n[TRANSACTION] Attempting to create user and post atomically...")
    transactional_service = TransactionalService()
    try:
        transactional_service.create_user_and_first_post(
            'tx@example.com', 'txpass', ['USER'], '', 'This will fail'
        )
    except ValueError as e:
        print(f"  - Transaction failed as expected: {e}")
        user_exists = user_service.get_user_by_email('tx@example.com') is not None
        print(f"  - Was user 'tx@example.com' created? {user_exists} (Should be False due to rollback)")

    print("\n[TRANSACTION] Successful transaction:")
    new_user, new_post = transactional_service.create_user_and_first_post(
        'tx_success@example.com', 'txpass', ['USER'], 'Successful Post', 'Content here'
    )
    print(f"  - Successfully created user '{new_user.email}' and post '{new_post.title}'")

    # 5. DELETE
    print(f"\n[DELETE] Deleting user '{admin_user.email}'...")
    user_service.delete_user(admin_user.id)
    remaining_user = user_service.get_user_by_email(admin_user.email)
    print(f"  - User exists after delete? {remaining_user is not None}")
    posts_count = Post.objects.filter(user_id=admin_user.id).count()
    print(f"  - Posts for deleted user exist? {posts_count > 0} (Should be False due to CASCADE)")