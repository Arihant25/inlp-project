import uuid
import datetime
import enum
import re
from functools import wraps
from xml.etree import ElementTree as ET

from flask import Flask, request, jsonify, Response
from marshmallow import Schema, fields, validate, ValidationError, post_load

# --- Configuration & App Setup ---
app = Flask(__name__)
app.config["JSON_SORT_KEYS"] = False

# --- Mock Database ---
MOCK_DB = {
    "users": {},
    "posts": {}
}

# --- Domain Models & Enums ---
class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# A simple data class to hold our domain objects
class User:
    def __init__(self, email, password_hash, role=UserRole.USER, is_active=True, id=None, created_at=None):
        self.id = id or uuid.uuid4()
        self.email = email
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at or datetime.datetime.utcnow()

# --- Custom Validators ---
def validate_password_strength(password):
    """Password must be at least 8 chars, 1 uppercase, 1 lowercase, 1 digit."""
    if len(password) < 8:
        raise ValidationError("Password must be at least 8 characters long.")
    if not re.search(r"[A-Z]", password):
        raise ValidationError("Password must contain an uppercase letter.")
    if not re.search(r"[a-z]", password):
        raise ValidationError("Password must contain a lowercase letter.")
    if not re.search(r"\d", password):
        raise ValidationError("Password must contain a digit.")

# --- Marshmallow Schemas for Validation & Serialization ---
class UserSchema(Schema):
    id = fields.UUID(dump_only=True)
    email = fields.Email(required=True)
    # In a real app, we'd validate the raw password and hash it before saving.
    # The schema validates the input password, not the hash.
    password = fields.String(required=True, load_only=True, validate=validate_password_strength)
    password_hash = fields.String(dump_only=True)
    role = fields.Enum(UserRole, by_value=True, missing=UserRole.USER)
    is_active = fields.Boolean(missing=True)
    created_at = fields.DateTime(dump_only=True)

    @post_load
    def make_user(self, data, **kwargs):
        # This demonstrates creating a domain object after deserialization
        # In a real app, you'd hash the password here.
        password = data.pop("password")
        data["password_hash"] = f"hashed_{password}"
        return User(**data)

class PostSchema(Schema):
    id = fields.UUID(dump_only=True)
    user_id = fields.UUID(required=True)
    title = fields.String(required=True, validate=validate.Length(min=5, max=100))
    content = fields.String(required=True, validate=validate.Length(min=10))
    status = fields.Enum(PostStatus, by_value=True, missing=PostStatus.DRAFT)

# --- Error Handling ---
@app.errorhandler(ValidationError)
def handle_marshmallow_validation(err):
    """Custom error handler for Marshmallow's ValidationError."""
    return jsonify({"errors": err.messages}), 400

# --- XML Helper Decorator ---
def expects_xml(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'application/xml' not in request.content_type:
            return Response(
                "<error><message>Content-Type must be application/xml</message></error>",
                status=415,
                mimetype='application/xml'
            )
        try:
            request.xml_root = ET.fromstring(request.data)
        except ET.ParseError:
            return Response(
                "<error><message>Malformed XML</message></error>",
                status=400,
                mimetype='application/xml'
            )
        return f(*args, **kwargs)
    return decorated_function

# --- API Routes ---
@app.route("/users", methods=["POST"])
def create_user():
    """Create a user from JSON data."""
    json_data = request.get_json()
    if not json_data:
        return jsonify({"errors": "No input data provided"}), 400

    # Deserialize and validate input data
    user_schema = UserSchema()
    user_object = user_schema.load(json_data)

    # "Save" to mock DB
    MOCK_DB["users"][user_object.id] = user_object

    # Serialize the created object for the response
    return jsonify(user_schema.dump(user_object)), 201

@app.route("/users/<uuid:user_id>", methods=["GET"])
def get_user(user_id):
    """Get a user and serialize to JSON."""
    user = MOCK_DB["users"].get(user_id)
    if not user:
        return jsonify({"errors": "User not found"}), 404

    user_schema = UserSchema()
    return jsonify(user_schema.dump(user)), 200

@app.route("/posts/xml", methods=["POST"])
@expects_xml
def create_post_from_xml():
    """Create a post from XML data and return XML."""
    xml_root = request.xml_root
    
    # Convert XML to a dictionary for Marshmallow
    post_data = {child.tag: child.text for child in xml_root}

    # Validate using the same schema
    post_schema = PostSchema()
    try:
        validated_data = post_schema.load(post_data)
    except ValidationError as err:
        # Generate XML error response
        root = ET.Element("error")
        for field, messages in err.messages.items():
            field_error = ET.SubElement(root, "field", name=field)
            for msg in messages:
                msg_elem = ET.SubElement(field_error, "message")
                msg_elem.text = msg
        xml_response = ET.tostring(root, encoding='unicode')
        return Response(xml_response, status=400, mimetype='application/xml')

    # "Save" to mock DB
    post_id = uuid.uuid4()
    validated_data['id'] = post_id
    MOCK_DB["posts"][post_id] = validated_data

    # Generate XML success response
    root = ET.Element("post")
    ET.SubElement(root, "id").text = str(post_id)
    ET.SubElement(root, "status").text = "created"
    ET.SubElement(root, "title").text = validated_data['title']
    xml_response = ET.tostring(root, encoding='unicode')
    
    return Response(xml_response, status=201, mimetype='application/xml')

if __name__ == "__main__":
    # Add a mock user for post creation
    mock_user = User(email="test@example.com", password_hash="hashed_password")
    MOCK_DB["users"][mock_user.id] = mock_user
    print(f"Mock user created with ID: {mock_user.id}")
    app.run(debug=True, port=5001)