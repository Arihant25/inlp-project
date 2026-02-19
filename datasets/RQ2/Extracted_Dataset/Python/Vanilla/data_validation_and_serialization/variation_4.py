import re
import json
import uuid
import datetime
import enum
from xml.etree import ElementTree as ET
from collections import namedtuple

# ==============================================================================
# 1. DOMAIN SCHEMA (Simple Data Transfer Objects)
# ==============================================================================

class Role(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

UserDTO = namedtuple('User', ['id', 'email', 'phone_number', 'password_hash', 'role', 'is_active', 'created_at'])
PostDTO = namedtuple('Post', ['id', 'user_id', 'title', 'content', 'status'])

# ==============================================================================
# 2. VALIDATION & SERIALIZATION LOGIC (Service/Manager Pattern)
# ==============================================================================

class ValidationService:
    _EMAIL_REGEX = re.compile(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")
    _PHONE_REGEX = re.compile(r"^\+?1?\d{9,15}$")

    def _format_errors(self, errors):
        formatted = {}
        for field, messages in errors.items():
            formatted[field] = list(set(messages)) # Remove duplicates
        return formatted

    def validate_user_payload(self, payload):
        errors = {}
        clean_data = {}

        # Required fields
        for field in ['email', 'password_hash', 'role']:
            if not payload.get(field):
                errors.setdefault(field, []).append("This field is required.")

        # Email
        email = payload.get('email')
        if email and not self._EMAIL_REGEX.match(email):
            errors.setdefault('email', []).append("Enter a valid email address.")
        
        # Phone (optional)
        phone = payload.get('phone_number')
        if phone and not self._PHONE_REGEX.match(phone):
            errors.setdefault('phone_number', []).append("Enter a valid phone number.")

        # Role
        role = payload.get('role')
        if role:
            try:
                clean_data['role'] = Role(role)
            except ValueError:
                errors.setdefault('role', []).append(f"Invalid role. Must be one of {', '.join([r.value for r in Role])}.")

        # Type Coercion & Defaults
        try:
            clean_data['id'] = uuid.UUID(payload.get('id')) if payload.get('id') else uuid.uuid4()
        except ValueError:
            errors.setdefault('id', []).append("Must be a valid UUID.")
        
        is_active_raw = payload.get('is_active', True)
        if isinstance(is_active_raw, bool):
            clean_data['is_active'] = is_active_raw
        elif str(is_active_raw).lower() in ['true', '1']:
            clean_data['is_active'] = True
        elif str(is_active_raw).lower() in ['false', '0']:
            clean_data['is_active'] = False
        else:
            errors.setdefault('is_active', []).append("Must be a boolean.")

        created_at_raw = payload.get('created_at', datetime.datetime.now(datetime.timezone.utc))
        if isinstance(created_at_raw, datetime.datetime):
            clean_data['created_at'] = created_at_raw
        else:

            try:
                if isinstance(created_at_raw, str) and created_at_raw.endswith('Z'):
                    created_at_raw = created_at_raw[:-1] + '+00:00'
                clean_data['created_at'] = datetime.datetime.fromisoformat(created_at_raw)
            except (ValueError, TypeError):
                errors.setdefault('created_at', []).append("Invalid ISO 8601 timestamp.")

        if errors:
            return None, self._format_errors(errors)

        # Populate remaining fields
        clean_data['email'] = email
        clean_data['phone_number'] = phone
        clean_data['password_hash'] = payload.get('password_hash')

        return UserDTO(**clean_data), None

class SerializationService:
    def _default_encoder(self, obj):
        if isinstance(obj, (datetime.datetime, datetime.date)):
            return obj.isoformat()
        if isinstance(obj, uuid.UUID):
            return str(obj)
        if isinstance(obj, enum.Enum):
            return obj.value
        raise TypeError(f"Type {type(obj)} not serializable")

    def to_json(self, dto_instance):
        return json.dumps(dto_instance._asdict(), default=self._default_encoder)

    def from_json(self, json_string):
        return json.loads(json_string)

    def to_xml(self, dto_instance, root_name):
        root = ET.Element(root_name)
        for key, val in dto_instance._asdict().items():
            child = ET.SubElement(root, key)
            child.text = self._default_encoder(val) if val is not None else ""
        return ET.tostring(root, encoding='unicode')

    def from_xml(self, xml_string):
        root = ET.fromstring(xml_string)
        return {child.tag: child.text for child in root}

# ==============================================================================
# 3. DEMONSTRATION
# ==============================================================================
if __name__ == '__main__':
    print("--- Variation 4: Service/Manager Pattern ---")
    
    # Instantiate services
    validator = ValidationService()
    serializer = SerializationService()

    # --- 1. Successful Validation ---
    valid_payload = {
        "email": "test.user@service.com",
        "phone_number": "+442079460000",
        "password_hash": "hashed_and_salted",
        "role": "USER",
        "is_active": "1"
    }
    print("\n[1] Testing valid data...")
    user_dto, errors = validator.validate_user_payload(valid_payload)
    if user_dto:
        print(f"Validation successful. Created DTO: {user_dto}")
        assert user_dto.is_active is True
    else:
        print(f"Validation failed unexpectedly: {errors}")

    # --- 2. Failed Validation ---
    invalid_payload = {
        "email": "not-an-email",
        "phone_number": "invalid phone",
        "password_hash": None,
        "role": "INVALID_ROLE",
        "is_active": "unknown"
    }
    print("\n[2] Testing invalid data...")
    _, errors = validator.validate_user_payload(invalid_payload)
    if errors:
        print(f"Validation failed as expected. Errors: {json.dumps(errors, indent=2)}")
    else:
        print("Validation succeeded unexpectedly.")

    # --- 3. JSON Serialization/Deserialization ---
    print("\n[3] Testing JSON serialization...")
    json_output = serializer.to_json(user_dto)
    print(f"Serialized JSON: {json_output}")
    
    # Deserialization and re-validation
    deserialized_payload = serializer.from_json(json_output)
    rehydrated_dto, rehydration_errors = validator.validate_user_payload(deserialized_payload)
    print(f"Rehydrated from JSON: {rehydrated_dto.email}")
    assert not rehydration_errors
    assert rehydrated_dto.id == user_dto.id

    # --- 4. XML Serialization/Deserialization ---
    print("\n[4] Testing XML serialization...")
    xml_output = serializer.to_xml(user_dto, "User")
    print(f"Serialized XML: {xml_output}")
    
    # Deserialization and re-validation
    deserialized_xml_payload = serializer.from_xml(xml_output)
    rehydrated_xml_dto, rehydration_xml_errors = validator.validate_user_payload(deserialized_xml_payload)
    print(f"Rehydrated from XML: {rehydrated_xml_dto.email}")
    assert not rehydration_xml_errors
    assert rehydrated_xml_dto.email == user_dto.email