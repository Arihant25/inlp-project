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
                'db_operations_v2',
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
    
    app_config = apps.get_app_config('db_operations_v2')
    app_config.models_module = sys.modules[__name__]

    call_command('makemigrations', 'db_operations_v2', interactive=False, verbosity=0)
    call_command('migrate', interactive=False, verbosity=0)

# --- Django App Configuration ---
from django.apps import AppConfig

class DbOperationsV2AppConfig(AppConfig):
    name = 'db_operations_v2'

# --- Models (Schema Definition) ---
from django.db import models, transaction
from django.contrib.auth.hashers import make_password, check_password

# --- Variation 2: Fat Model / Active Record Pattern ---
# This developer believes logic related to a model should live with the model,
# either on the model class itself or its manager. This follows the classic
# Active Record pattern.

class Role(models.Model):
    name = models.CharField(max_length=50, unique=True)
    def __str__(self):
        return self.name

class UserManager(models.Manager):
    def create_user(self, email, password, role_names=None):
        if not email:
            raise ValueError('Users must have an email address')
        
        user = self.model(email=self.normalize_email(email))
        user.set_password(password)
        user.save(using=self._db)
        
        if role_names:
            roles = Role.objects.filter(name__in=role_names)
            user.roles.set(roles)
        return user

    def find_active_admins(self):
        return self.get_queryset().filter(is_active=True, roles__name='ADMIN')

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    roles = models.ManyToManyField(Role, related_name='users')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    objects = UserManager()

    def set_password(self, raw_password):
        self.password_hash = make_password(raw_password)

    def check_password(self, raw_password):
        return check_password(raw_password, self.password_hash)

    def add_role(self, role_name):
        role, _ = Role.objects.get_or_create(name=role_name)
        self.roles.add(role)

    def deactivate(self):
        self.is_active = False
        self.save(update_fields=['is_active'])

    @transaction.atomic
    def create_first_post(self, title, content):
        """Instance method demonstrating a transaction."""
        if self.posts.exists():
            raise Exception("User already has posts.")
        return Post.objects.create(
            user=self, 
            title=title, 
            content=content, 
            status=Post.Status.PUBLISHED
        )

    def __str__(self):
        return self.email

class PostManager(models.Manager):
    def get_published_posts(self):
        return self.get_queryset().filter(status=Post.Status.PUBLISHED)

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

    objects = PostManager()

    def publish(self):
        self.status = self.Status.PUBLISHED
        self.save(update_fields=['status'])

    def __str__(self):
        return self.title

# --- Main execution block to demonstrate the pattern ---
if __name__ == '__main__':
    setup_django_env()

    print("--- Variation 2: Active Record / Fat Model Demo ---")

    # Setup: Create Roles
    Role.objects.get_or_create(name='ADMIN')
    Role.objects.get_or_create(name='USER')
    print("Created roles: ADMIN, USER")

    # 1. CREATE (using the custom manager)
    admin_user = User.objects.create_user('admin.dev@example.com', 'pass123', ['ADMIN'])
    print(f"\n[CREATE] Created User via Manager: {admin_user.email}")
    
    # Create posts directly
    post1 = Post.objects.create(user=admin_user, title="Active Record in Django", content="Models can hold logic.")
    print(f"[CREATE] Created Post: '{post1.title}' with status {post1.status}")

    # 2. READ (using custom manager methods)
    print("\n[READ] Finding all published posts:")
    published_posts = Post.objects.get_published_posts()
    print(f"  - Found {published_posts.count()} published posts.")
    
    print("[READ] Finding active admins:")
    active_admins = User.objects.find_active_admins()
    for admin in active_admins:
        print(f"  - Found admin: {admin.email}")

    # 3. UPDATE (using instance methods)
    print(f"\n[UPDATE] Publishing post '{post1.title}' via instance method...")
    post1.publish()
    print(f"  - Post status is now: {post1.status}")
    
    print(f"[UPDATE] Deactivating user '{admin_user.email}' via instance method...")
    admin_user.deactivate()
    print(f"  - User is_active is now: {admin_user.is_active}")

    # 4. TRANSACTION & ROLLBACK (using an instance method)
    print("\n[TRANSACTION] Creating a user and their first post atomically...")
    new_user = User.objects.create_user('tx.user@example.com', 'txpass', ['USER'])
    try:
        # This will succeed
        new_user.create_first_post("My First Post", "This was created in a transaction.")
        print(f"  - Successfully created first post for {new_user.email}")
        # This will fail and roll back the second post creation
        new_user.create_first_post("My Second Post", "This should fail.")
    except Exception as e:
        print(f"  - Transaction failed as expected: {e}")
        post_count = new_user.posts.count()
        print(f"  - Number of posts for {new_user.email}: {post_count} (Should be 1)")

    # 5. DELETE
    print(f"\n[DELETE] Deleting user '{new_user.email}'...")
    user_id_to_delete = new_user.id
    new_user.delete()
    user_exists = User.objects.filter(id=user_id_to_delete).exists()
    print(f"  - User exists after delete? {user_exists}")
    posts_exist = Post.objects.filter(user_id=user_id_to_delete).exists()
    print(f"  - Posts for deleted user exist? {posts_exist} (Should be False due to CASCADE)")