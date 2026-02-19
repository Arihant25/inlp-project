import uuid
import datetime
import enum
from xml.etree import ElementTree as ET

from flask import Flask, request, jsonify, Response
from flask_wtf import FlaskForm
from wtforms import StringField, BooleanField, SelectField, PasswordField, TextAreaField
from wtforms.validators import DataRequired, Email, Length, Regexp, ValidationError

# --- App Setup ---
app = Flask(__name__)
# WTForms requires a secret key for CSRF protection, even if we disable it for the API
app.config['SECRET_KEY'] = 'a-super-secret-key-for-demo'
app.config['WTF_CSRF_ENABLED'] = False # Disable CSRF for our stateless API

# --- Mock Database ---
db_storage = {
    "users": {},
    "posts": {}
}

# --- Domain Enums ---
class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Custom WTForms Validators ---
def password_check(form, field):
    """Custom inline validator for password complexity."""
    password = field.data
    if not (any(c.isupper() for c in password) and
            any(c.islower() for c in password) and
            any(c.isdigit() for c in password)):
        raise ValidationError('Password must contain an uppercase, a lowercase, and a digit.')

# --- WTForms for Validation ---
class UserForm(FlaskForm):
    email = StringField('Email', validators=[DataRequired(), Email()])
    password = PasswordField('Password', validators=[DataRequired(), Length(min=8), password_check])
    role = SelectField('Role', choices=[(role.name, role.value) for role in UserRole], default=UserRole.USER.name)
    is_active = BooleanField('Is Active', default=True)

class PostForm(FlaskForm):
    user_id = StringField('UserID', validators=[DataRequired(), Regexp(r'^[0-9a-fA-F-]{36}$', message="Invalid UUID format")])
    title = StringField('Title', validators=[DataRequired(), Length(min=5, max=100)])
    content = TextAreaField('Content', validators=[DataRequired(), Length(min=10)])
    status = SelectField('Status', choices=[(s.name, s.value) for s in PostStatus], default=PostStatus.DRAFT.name)

# --- API Routes ---
@app.route("/users", methods=["POST"])
def create_user_with_wtforms():
    """Create a user using WTForms for validation."""
    form = UserForm(data=request.get_json())

    if form.validate():
        # Data is valid and coerced
        user_id = uuid.uuid4()
        new_user = {
            "id": user_id,
            "email": form.email.data,
            "password_hash": f"hashed_{form.password.data}",
            "role": UserRole[form.role.data], # Type conversion from string name to Enum
            "is_active": form.is_active.data,
            "created_at": datetime.datetime.utcnow()
        }
        db_storage["users"][user_id] = new_user

        # Manual serialization for the response
        response_data = {k: v for k, v in new_user.items() if k != 'password_hash'}
        response_data['role'] = response_data['role'].value # Serialize enum to its value
        response_data['id'] = str(response_data['id'])
        
        return jsonify(response_data), 201
    else:
        # Custom error message formatting
        return jsonify({"errors": form.errors}), 400

@app.route("/users/<uuid:user_id>", methods=["GET"])
def get_user_by_id(user_id):
    """Get a user and manually serialize the response."""
    user = db_storage["users"].get(user_id)
    if not user:
        return jsonify({"error": "User not found"}), 404

    # Manual serialization
    response_data = {
        "id": str(user["id"]),
        "email": user["email"],
        "role": user["role"].value,
        "is_active": user["is_active"],
        "created_at": user["created_at"].isoformat()
    }
    return jsonify(response_data)

@app.route("/posts/xml", methods=["POST"])
def create_post_with_wtforms_xml():
    """Parse XML, validate with WTForms, and generate XML response."""
    if 'application/xml' not in request.content_type:
        return Response("<error>Content-Type must be application/xml</error>", 415, mimetype='application/xml')

    try:
        root = ET.fromstring(request.data)
        # Manually parse and coerce types from XML
        data_from_xml = {child.tag: child.text for child in root}
        
        # WTForms can take a dictionary-like object
        form = PostForm(data=data_from_xml)

        if form.validate():
            post_id = uuid.uuid4()
            new_post = {
                "id": post_id,
                "user_id": uuid.UUID(form.user_id.data),
                "title": form.title.data,
                "content": form.content.data,
                "status": PostStatus[form.status.data]
            }
            db_storage["posts"][post_id] = new_post

            # Generate XML success response
            resp_root = ET.Element("post")
            ET.SubElement(resp_root, "id").text = str(post_id)
            ET.SubElement(resp_root, "message").text = "Post created successfully"
            return Response(ET.tostring(resp_root), status=201, mimetype='application/xml')
        else:
            # Generate XML error response
            err_root = ET.Element("errors")
            for field, messages in form.errors.items():
                field_node = ET.SubElement(err_root, "field", name=field)
                for msg in messages:
                    ET.SubElement(field_node, "message").text = msg
            return Response(ET.tostring(err_root), status=400, mimetype='application/xml')

    except ET.ParseError:
        return Response("<error>Malformed XML</error>", 400, mimetype='application/xml')

if __name__ == "__main__":
    mock_user_id = uuid.uuid4()
    db_storage["users"][mock_user_id] = {
        "id": mock_user_id, "email": "test@example.com", "password_hash": "...",
        "role": UserRole.USER, "is_active": True, "created_at": datetime.datetime.utcnow()
    }
    print(f"Mock user created with ID: {mock_user_id}")
    app.run(debug=True, port=5004)