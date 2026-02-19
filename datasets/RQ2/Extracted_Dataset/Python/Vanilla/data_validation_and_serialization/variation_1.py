import re
import json
import uuid
import datetime
import enum
from xml.etree import ElementTree as ET

# ==============================================================================
# 1. DOMAIN SCHEMA
# ==============================================================================

class Role(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

class User:
    def __init__(self, id, email, phone_number, password_hash, role, is_active, created_at):
        self.id = id
        self.email = email
        self.phone_number = phone_number
        self.password_hash = password_hash
        self.role = role
        self.is_active = is_active
        self.created_at = created_at

    def __repr__(self):
        return f"<User id={self.id} email='{self.email}' role={self.role.name}>"

class Post:
    def __init__(self, id, user_id, title, content, status):
        self.id = id
        self.user_id = user_id
        self.title = title
        self.content = content
        self.status = status

    def __repr__(self):
        return f"<Post id={self.id} title='{self.title}' status={self.status.name}>"

# ==============================================================================
# 2. VALIDATION & SERIALIZATION LOGIC (OOP APPROACH)
# ==============================================================================

class ValidationError(Exception):
    def __init__(self, errors):
        self.errors = errors
        super().__init__(f"Validation failed: {errors}")

class BaseValidator:
    _EMAIL_REGEX = re.compile(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")
    _PHONE_REGEX = re.compile(r"^\+?1?\d{9,15}$") # E.164 format-ish

    def __init__(self, data):
        self.data = data
        self.errors = {}
        self._cleaned_data = {}

    def _add_error(self, field, message):
        if field not in self.errors:
            self.errors[field] = []
        self.errors[field].append(message)

    def is_valid(self):
        self.errors = {}
        self._cleaned_data = {}
        self.validate()
        return not self.errors

    def validate(self):
        raise NotImplementedError("Subclasses must implement this method")

    def _validate_required(self, field):
        value = self.data.get(field)
        if value is None or (isinstance(value, str) and not value.strip()):
            self._add_error(field, "This field is required.")
            return None
        return value

    def _validate_email(self, field, value):
        if not self._EMAIL_REGEX.match(value):
            self._add_error(field, "Enter a valid email address.")
            return None
        return value

    def _validate_phone(self, field, value):
        if value and not self._PHONE_REGEX.match(value):
            self._add_error(field, "Enter a valid phone number.")
            return None
        return value

class UserValidator(BaseValidator):
    def validate(self):
        # ID (UUID)
        id_val = self.data.get('id', uuid.uuid4())
        if isinstance(id_val, str):
            try:
                id_val = uuid.UUID(id_val)
            except ValueError:
                self._add_error('id', 'Must be a valid UUID.')
        self._cleaned_data['id'] = id_val

        # Email
        email = self._validate_required('email')
        if email:
            self._validate_email('email', email)
            self._cleaned_data['email'] = email

        # Phone Number (optional)
        phone = self.data.get('phone_number')
        if phone:
            self._validate_phone('phone_number', phone)
        self._cleaned_data['phone_number'] = phone

        # Password Hash
        password = self._validate_required('password_hash')
        if password:
            self._cleaned_data['password_hash'] = password

        # Role (Enum)
        role_val = self._validate_required('role')
        if role_val:
            try:
                self._cleaned_data['role'] = Role(role_val)
            except ValueError:
                self._add_error('role', f"Invalid role. Must be one of {', '.join([r.value for r in Role])}.")

        # is_active (Boolean)
        is_active_val = self.data.get('is_active', True)
        if not isinstance(is_active_val, bool):
            if str(is_active_val).lower() in ['true', '1']:
                is_active_val = True
            elif str(is_active_val).lower() in ['false', '0']:
                is_active_val = False
            else:
                self._add_error('is_active', 'Must be a boolean value.')
        self._cleaned_data['is_active'] = is_active_val

        # created_at (Timestamp)
        created_at_val = self.data.get('created_at', datetime.datetime.now(datetime.timezone.utc))
        if isinstance(created_at_val, str):
            try:
                created_at_val = datetime.datetime.fromisoformat(created_at_val.replace('Z', '+00:00'))
            except ValueError:
                self._add_error('created_at', 'Invalid ISO 8601 timestamp format.')
        self._cleaned_data['created_at'] = created_at_val

        return self._cleaned_data

class BaseSerializer:
    @staticmethod
    def _default_encoder(obj):
        if isinstance(obj, (datetime.datetime, datetime.date)):
            return obj.isoformat()
        if isinstance(obj, uuid.UUID):
            return str(obj)
        if isinstance(obj, enum.Enum):
            return obj.value
        raise TypeError(f"Type {type(obj)} not serializable")

    def to_json(self, instance):
        return json.dumps(instance.__dict__, default=self._default_encoder)

    def from_json(self, json_string):
        data = json.loads(json_string)
        validator = self.validator_class(data)
        if validator.is_valid():
            return self.model_class(**validator._cleaned_data)
        else:
            raise ValidationError(validator.errors)

    def to_xml(self, instance):
        root = ET.Element(instance.__class__.__name__)
        for key, val in instance.__dict__.items():
            child = ET.SubElement(root, key)
            child.text = self._default_encoder(val) if val is not None else ""
        return ET.tostring(root, encoding='unicode')

    def from_xml(self, xml_string):
        root = ET.fromstring(xml_string)
        data = {child.tag: child.text for child in root}
        validator = self.validator_class(data)
        if validator.is_valid():
            return self.model_class(**validator._cleaned_data)
        else:
            raise ValidationError(validator.errors)

class UserSerializer(BaseSerializer):
    model_class = User
    validator_class = UserValidator

# ==============================================================================
# 3. DEMONSTRATION
# ==============================================================================
if __name__ == '__main__':
    print("--- Variation 1: OOP Approach ---")
    user_serializer = UserSerializer()

    # --- 1. Successful Validation & Instantiation ---
    valid_user_data = {
        "id": "123e4567-e89b-12d3-a456-426614174000",
        "email": "test@example.com",
        "phone_number": "+12125551234",
        "password_hash": "a_very_secure_hash_string",
        "role": "ADMIN",
        "is_active": "true",
        "created_at": "2023-10-27T10:00:00Z"
    }
    print("\n[1] Testing valid data...")
    try:
        user_instance = user_serializer.from_json(json.dumps(valid_user_data))
        print(f"Successfully created User instance: {user_instance}")
        assert user_instance.is_active is True
        assert user_instance.role == Role.ADMIN
    except ValidationError as e:
        print(f"Validation failed unexpectedly: {e.errors}")

    # --- 2. Failed Validation ---
    invalid_user_data = {
        "id": "not-a-uuid",
        "email": "invalid-email",
        "phone_number": "555-1234",
        "password_hash": "",
        "role": "GUEST",
        "is_active": "maybe",
    }
    print("\n[2] Testing invalid data...")
    validator = UserValidator(invalid_user_data)
    if not validator.is_valid():
        print(f"Validation failed as expected. Errors: {json.dumps(validator.errors, indent=2)}")
    else:
        print("Validation succeeded unexpectedly.")

    # --- 3. JSON Serialization/Deserialization ---
    print("\n[3] Testing JSON serialization...")
    json_output = user_serializer.to_json(user_instance)
    print(f"Serialized JSON: {json_output}")
    rehydrated_user = user_serializer.from_json(json_output)
    print(f"Rehydrated from JSON: {rehydrated_user}")
    assert rehydrated_user.id == user_instance.id

    # --- 4. XML Serialization/Deserialization ---
    print("\n[4] Testing XML serialization...")
    xml_output = user_serializer.to_xml(user_instance)
    print(f"Serialized XML: {xml_output}")
    rehydrated_user_xml = user_serializer.from_xml(xml_output)
    print(f"Rehydrated from XML: {rehydrated_user_xml}")
    assert rehydrated_user_xml.email == user_instance.email