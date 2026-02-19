import uuid
import datetime
import enum
import re
from xml.etree import ElementTree as ET

from flask import Flask, request, jsonify, Blueprint, Response
from flask.views import MethodView
from marshmallow import Schema, fields, validate, ValidationError

# --- Domain Enums ---
class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# --- Mock Data Store ---
DB_MOCK = {
    "users": {},
    "posts": {}
}

# --- Schemas (DTOs) ---
# This would typically be in a separate 'schemas.py' file
class Schemas:
    @staticmethod
    def validate_phone_number(phone):
        """Custom validator for a simple NA phone format."""
        if not re.match(r'^\(?([0-9]{3})\)?[-. ]?([0-9]{3})[-. ]?([0-9]{4})$', phone):
            raise ValidationError("Invalid phone number format.")

    class User(Schema):
        id = fields.UUID(dump_only=True)
        email = fields.Email(required=True)
        password_hash = fields.String(dump_only=True)
        role = fields.Enum(UserRole, by_value=True, load_default=UserRole.USER)
        is_active = fields.Boolean(load_default=True)
        created_at = fields.DateTime(dump_only=True)
        phone = fields.Str(required=False, validate=Schemas.validate_phone_number)

    class Post(Schema):
        id = fields.UUID(dump_only=True)
        user_id = fields.UUID(required=True)
        title = fields.String(required=True, validate=validate.Length(min=5))
        content = fields.String(required=True)
        status = fields.Enum(PostStatus, by_value=True, load_default=PostStatus.DRAFT)

# --- Blueprint for User Resource ---
user_bp = Blueprint('user_api', __name__, url_prefix='/users')

class UserAPI(MethodView):
    """Class-based view for User operations."""
    
    def post(self):
        """Create a new user."""
        try:
            # Deserialization and Validation
            user_data = Schemas.User().load(request.get_json())
            
            user_id = uuid.uuid4()
            user_record = {
                "id": user_id,
                "email": user_data["email"],
                "password_hash": f"hashed_for_{user_data['email']}",
                "role": user_data["role"],
                "is_active": user_data["is_active"],
                "created_at": datetime.datetime.utcnow(),
                "phone": user_data.get("phone")
            }
            DB_MOCK["users"][user_id] = user_record
            
            # Serialization for response
            return jsonify(Schemas.User().dump(user_record)), 201
        except ValidationError as err:
            return jsonify({"status": "error", "messages": err.messages}), 400

    def get(self, user_id):
        """Get a single user."""
        user_record = DB_MOCK["users"].get(user_id)
        if not user_record:
            return jsonify({"status": "error", "message": "User not found"}), 404
        
        # Serialization for response
        return jsonify(Schemas.User().dump(user_record)), 200

# Register the class-based view with the blueprint
user_view = UserAPI.as_view('user_api')
user_bp.add_url_rule('', view_func=user_view, methods=['POST'])
user_bp.add_url_rule('/<uuid:user_id>', view_func=user_view, methods=['GET'])

# --- Blueprint for Post Resource ---
post_bp = Blueprint('post_api', __name__, url_prefix='/posts')

class PostXMLAPI(MethodView):
    """Class-based view for Post operations using XML."""

    def post(self):
        """Create a post from XML."""
        if 'application/xml' not in request.content_type:
            return Response("<error>Content-Type must be application/xml</error>", 415, mimetype='application/xml')
        
        try:
            xml_root = ET.fromstring(request.data)
            # Type coercion from XML text to dict
            data_dict = {child.tag: child.text for child in xml_root}
            
            # Validation using Marshmallow schema
            validated_data = Schemas.Post().load(data_dict)
            
            post_id = uuid.uuid4()
            post_record = {
                "id": post_id,
                **validated_data
            }
            DB_MOCK["posts"][post_id] = post_record
            
            # XML Generation for response
            resp_root = ET.Element("post")
            ET.SubElement(resp_root, "id").text = str(post_id)
            ET.SubElement(resp_root, "status").text = "created"
            return Response(ET.tostring(resp_root), status=201, mimetype='application/xml')

        except ET.ParseError:
            return Response("<error>Malformed XML</error>", 400, mimetype='application/xml')
        except ValidationError as err:
            # XML Error Formatting
            err_root = ET.Element("errors")
            for field, messages in err.messages.items():
                field_node = ET.SubElement(err_root, "field", name=field)
                for msg in messages:
                    ET.SubElement(field_node, "message").text = msg
            return Response(ET.tostring(err_root), status=400, mimetype='application/xml')

post_xml_view = PostXMLAPI.as_view('post_xml_api')
post_bp.add_url_rule('/xml', view_func=post_xml_view, methods=['POST'])

# --- Main Application Factory ---
def create_app():
    app = Flask(__name__)
    app.register_blueprint(user_bp)
    app.register_blueprint(post_bp)
    return app

app = create_app()

if __name__ == '__main__':
    mock_user_id = uuid.uuid4()
    DB_MOCK["users"][mock_user_id] = {
        "id": mock_user_id,
        "email": "test@example.com",
        "password_hash": "hashed_password",
        "role": UserRole.USER,
        "is_active": True,
        "created_at": datetime.datetime.utcnow()
    }
    print(f"Mock user created with ID: {mock_user_id}")
    app.run(debug=True, port=5003)