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
                'db_operations_v4',
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
    
    app_config = apps.get_app_config('db_operations_v4')
    app_config.models_module = sys.modules[__name__]

    call_command('makemigrations', 'db_operations_v4', interactive=False, verbosity=0)
    call_command('migrate', interactive=False, verbosity=0)

# --- Django App Configuration ---
from django.apps import AppConfig

class DbOperationsV4AppConfig(AppConfig):
    name = 'db_operations_v4'

# --- Models (Schema Definition) ---
from django.db import models, transaction
from django.db.models import Q, Count
from django.contrib.auth.hashers import make_password

# --- Variation 4: QuerySet-Centric Power User ---
# This developer leverages custom QuerySet and Manager classes to create a fluent,
# chainable API for database operations. It's a very powerful and idiomatic Django pattern.

class Role(models.Model):
    name = models.CharField(max_length=50, unique=True)

class UserQuerySet(models.QuerySet):
    def active(self):
        return self.filter(is_active=True)

    def with_role(self, role_name):
        return self.filter(roles__name=role_name)

    def with_published_posts(self):
        return self.filter(posts__status=Post.Status.PUBLISHED).distinct()

    def annotate_post_count(self):
        return self.annotate(num_posts=Count('posts'))

class UserManager(models.Manager):
    def get_queryset(self):
        return UserQuerySet(self.model, using=self._db)

    def create_user_with_roles(self, email, password, role_names):
        user = self.model(email=email, password_hash=make_password(password))
        user.save()
        roles = Role.objects.filter(name__in=role_names)
        user.roles.set(roles)
        return user

    def active_admins(self):
        return self.get_queryset().active().with_role('ADMIN')

class User(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    password_hash = models.CharField(max_length=128)
    roles = models.ManyToManyField(Role, related_name='users')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    objects = UserManager()

class PostQuerySet(models.QuerySet):
    def published(self):
        return self.filter(status=Post.Status.PUBLISHED)

    def drafts(self):
        return self.filter(status=Post.Status.DRAFT)

    def by_author(self, user):
        return self.filter(user=user)

    def search(self, text):
        return self.filter(Q(title__icontains=text) | Q(content__icontains=text))

class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = 'DRAFT', 'Draft'
        PUBLISHED = 'PUBLISHED', 'Published'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='posts')
    title = models.CharField(max_length=255)
    content = models.TextField()
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.DRAFT)

    objects = PostQuerySet.as_manager()

# --- Main execution block to demonstrate the pattern ---
if __name__ == '__main__':
    setup_django_env()

    print("--- Variation 4: QuerySet-Centric Demo ---")

    # Setup: Create Roles
    Role.objects.get_or_create(name='ADMIN')
    Role.objects.get_or_create(name='USER')
    Role.objects.get_or_create(name='EDITOR')
    print("Created roles: ADMIN, USER, EDITOR")

    # 1. CREATE (using custom manager)
    admin = User.objects.create_user_with_roles('admin@corp.com', 'pass1', ['ADMIN', 'EDITOR'])
    editor = User.objects.create_user_with_roles('editor@corp.com', 'pass2', ['EDITOR'])
    print(f"\n[CREATE] Created Admin: {admin.email}")
    print(f"[CREATE] Created Editor: {editor.email}")

    Post.objects.create(user=admin, title="Django QuerySets", content="A deep dive into QuerySets.", status=Post.Status.PUBLISHED)
    Post.objects.create(user=admin, title="Advanced Django", content="More patterns.", status=Post.Status.DRAFT)
    Post.objects.create(user=editor, title="Editing Guide", content="How to edit posts.", status=Post.Status.PUBLISHED)
    print("[CREATE] Created 3 posts.")

    # 2. READ (Chaining custom QuerySet methods)
    print("\n[READ] Finding all published posts by the admin:")
    admin_posts = Post.objects.published().by_author(admin)
    for p in admin_posts:
        print(f"  - '{p.title}' by {p.user.email}")

    print("\n[READ] Finding active admins who have published posts:")
    power_users = User.objects.active_admins().with_published_posts()
    for u in power_users:
        print(f"  - Found active admin with posts: {u.email}")

    print("\n[READ] Find all editors and annotate their post count:")
    editors_with_counts = User.objects.with_role('EDITOR').annotate_post_count()
    for e in editors_with_counts:
        print(f"  - Editor {e.email} has {e.num_posts} post(s).")

    # 3. UPDATE (using QuerySet `update` for bulk operations)
    print(f"\n[UPDATE] Publishing all of {admin.email}'s drafts in bulk...")
    updated_count = Post.objects.drafts().by_author(admin).update(status=Post.Status.PUBLISHED)
    print(f"  - Published {updated_count} post(s).")
    draft_count = Post.objects.drafts().by_author(admin).count()
    print(f"  - Remaining drafts for admin: {draft_count}")

    # 4. TRANSACTION & ROLLBACK
    print("\n[TRANSACTION] Atomically creating a user and deleting another...")
    try:
        with transaction.atomic():
            print("  - Deleting editor...")
            User.objects.filter(email=editor.email).delete()
            print("  - Creating new user...")
            User.objects.create_user_with_roles('newbie@corp.com', 'newpass', ['USER'])
            # Simulate a failure
            raise InterruptedError("Simulated system failure")
    except InterruptedError as e:
        print(f"  - Transaction failed: {e}")
        editor_exists = User.objects.filter(email=editor.email).exists()
        newbie_exists = User.objects.filter(email='newbie@corp.com').exists()
        print(f"  - Editor '{editor.email}' exists? {editor_exists} (Should be True due to rollback)")
        print(f"  - Newbie 'newbie@corp.com' exists? {newbie_exists} (Should be False due to rollback)")

    # 5. DELETE (using a QuerySet filter)
    print(f"\n[DELETE] Deleting all posts containing 'Guide'...")
    deleted_count, _ = Post.objects.search('Guide').delete()
    print(f"  - Deleted {deleted_count} post(s).")
    remaining_posts = Post.objects.all().count()
    print(f"  - Total posts remaining: {remaining_posts}")