import re
import json
import uuid
import datetime
import enum
from xml.etree import ElementTree as ET

# ==============================================================================
# 1. DOMAIN SCHEMA (Enums only, data represented as dicts)
# ==============================================================================

class UserRole(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# ==============================================================================
# 2. VALIDATION & SERIALIZATION LOGIC (Functional Approach)
# ==============================================================================

# --- Custom Validator Functions ---

def validate_required(value, field_name):
    if value is None or (isinstance(value, str) and not value.strip()):
        return False, f"Field '{field_name}' is required."
    return True, None

def validate_email(value, field_name):
    email_regex = re.compile(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")
    if not email_regex.match(str(value)):
        return False, f"Field '{field_name}' is not a valid email address."
    return True, None

def validate_phone(value, field_name):
    phone_regex = re.compile(r"^\+?1?\d{9,15}$")
    if value and not phone_regex.match(str(value)):
        return False, f"Field '{field_name}' is not a valid phone number."
    return True, None

def make_enum_validator(enum_class):
    def validator(value, field_name):
        try:
            enum_class(value)
            return True, None
        except ValueError:
            allowed = ", ".join([item.value for item in enum_class])
            return False, f"Field '{field_name}' must be one of: {allowed}."
    return validator

# --- Type Coercion Functions ---

def to_uuid(value, field_name):
    if isinstance(value, uuid.UUID):
        return value, None
    try:
        return uuid.UUID(str(value)), None
    except (ValueError, TypeError):
        return None, f"Field '{field_name}' must be a valid UUID."

def to_bool(value, field_name):
    if isinstance(value, bool):
        return value, None
    if str(value).lower() in ('true', '1', 'yes'):
        return True, None
    if str(value).lower() in ('false', '0', 'no'):
        return False, None
    return None, f"Field '{field_name}' must be a valid boolean."

def to_datetime(value, field_name):
    if isinstance(value, datetime.datetime):
        return value, None
    try:
        # Handle Z for UTC
        if isinstance(value, str) and value.endswith('Z'):
            value = value[:-1] + '+00:00'
        return datetime.datetime.fromisoformat(value), None
    except (ValueError, TypeError):
        return None, f"Field '{field_name}' must be a valid ISO 8601 timestamp."

def to_enum(enum_class):
    def coercer(value, field_name):
        try:
            return enum_class(value), None
        except ValueError:
            return None, f"Could not convert '{value}' to {enum_class.__name__}"
    return coercer

# --- Schema Definition ---

USER_SCHEMA = {
    'id': {'coerce': to_uuid, 'validators': [validate_required], 'default': uuid.uuid4},
    'email': {'coerce': str, 'validators': [validate_required, validate_email]},
    'phone_number': {'coerce': str, 'validators': [validate_phone], 'required': False},
    'password_hash': {'coerce': str, 'validators': [validate_required]},
    'role': {'coerce': to_enum(UserRole), 'validators': [validate_required, make_enum_validator(UserRole)]},
    'is_active': {'coerce': to_bool, 'validators': [], 'default': True},
    'created_at': {'coerce': to_datetime, 'validators': [], 'default': lambda: datetime.datetime.now(datetime.timezone.utc)},
}

# --- Core Validation Engine ---

def process_and_validate(data, schema):
    errors = {}
    cleaned_data = {}
    
    for field, rules in schema.items():
        value = data.get(field)
        
        # Handle defaults and required fields
        if value is None:
            if 'default' in rules:
                value = rules['default']() if callable(rules['default']) else rules['default']
            elif rules.get('required', True):
                errors.setdefault(field, []).append("This field is required.")
                continue
            else:
                cleaned_data[field] = None
                continue

        # Coercion
        coerced_value = value
        if 'coerce' in rules:
            coerced_value, err = rules['coerce'](value, field)
            if err:
                errors.setdefault(field, []).append(err)
                continue
        
        # Validation
        for validator in rules.get('validators', []):
            is_valid, err_msg = validator(coerced_value, field)
            if not is_valid:
                errors.setdefault(field, []).append(err_msg)
        
        if field not in errors:
            cleaned_data[field] = coerced_value
            
    return cleaned_data, errors

# --- Serialization / Deserialization Functions ---

def _json_encoder_helper(obj):
    if isinstance(obj, (datetime.datetime, datetime.date)):
        return obj.isoformat()
    if isinstance(obj, uuid.UUID):
        return str(obj)
    if isinstance(obj, enum.Enum):
        return obj.value
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")

def serialize_to_json(data_dict):
    return json.dumps(data_dict, default=_json_encoder_helper)

def serialize_to_xml(data_dict, root_name):
    root = ET.Element(root_name)
    for key, val in data_dict.items():
        child = ET.SubElement(root, key)
        child.text = str(_json_encoder_helper(val)) if val is not None else ""
    return ET.tostring(root, encoding='unicode')

def deserialize_from_xml(xml_string):
    root = ET.fromstring(xml_string)
    return {child.tag: child.text for child in root}

# ==============================================================================
# 3. DEMONSTRATION
# ==============================================================================
if __name__ == '__main__':
    print("--- Variation 2: Functional Approach ---")

    # --- 1. Successful Validation ---
    valid_user_payload = {
        "email": "test@example.com",
        "phone_number": "+12125551234",
        "password_hash": "a_very_secure_hash_string",
        "role": "ADMIN",
        "is_active": "true",
    }
    print("\n[1] Testing valid data...")
    cleaned_user, validation_errors = process_and_validate(valid_user_payload, USER_SCHEMA)
    if not validation_errors:
        print(f"Validation successful. Cleaned data: {cleaned_user}")
        assert cleaned_user['is_active'] is True
        assert cleaned_user['role'] == UserRole.ADMIN
    else:
        print(f"Validation failed unexpectedly: {validation_errors}")

    # --- 2. Failed Validation ---
    invalid_user_payload = {
        "email": "invalid-email",
        "phone_number": "555-1234",
        "role": "GUEST",
        "is_active": "maybe",
    }
    print("\n[2] Testing invalid data...")
    _, validation_errors = process_and_validate(invalid_user_payload, USER_SCHEMA)
    if validation_errors:
        print(f"Validation failed as expected. Errors: {json.dumps(validation_errors, indent=2)}")
    else:
        print("Validation succeeded unexpectedly.")

    # --- 3. JSON Serialization/Deserialization ---
    print("\n[3] Testing JSON serialization...")
    json_output = serialize_to_json(cleaned_user)
    print(f"Serialized JSON: {json_output}")
    deserialized_data = json.loads(json_output)
    revalidated_data, errors = process_and_validate(deserialized_data, USER_SCHEMA)
    print(f"Re-validated from JSON: {revalidated_data['email']}")
    assert not errors

    # --- 4. XML Serialization/Deserialization ---
    print("\n[4] Testing XML serialization...")
    xml_output = serialize_to_xml(cleaned_user, "User")
    print(f"Serialized XML: {xml_output}")
    deserialized_xml_data = deserialize_from_xml(xml_output)
    revalidated_xml_data, errors = process_and_validate(deserialized_xml_data, USER_SCHEMA)
    print(f"Re-validated from XML: {revalidated_xml_data['email']}")
    assert not errors