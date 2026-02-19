import re
import json
import uuid
import datetime
import enum
from xml.etree import ElementTree as ET

# ==============================================================================
# 1. DOMAIN SCHEMA (Enums)
# ==============================================================================

class Role(enum.Enum):
    ADMIN = "ADMIN"
    USER = "USER"

class PostStatus(enum.Enum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"

# ==============================================================================
# 2. VALIDATION & SERIALIZATION LOGIC (Metaclass/Declarative Approach)
# ==============================================================================

class ValidationError(Exception):
    def __init__(self, message, errors=None):
        super().__init__(message)
        self.errors = errors or {}

class Field:
    def __init__(self, field_type, required=True, default=None, validator=None):
        self.field_type = field_type
        self.required = required
        self.default = default
        self.validator = validator

class ModelMeta(type):
    def __new__(cls, name, bases, attrs):
        schema = {}
        for base in bases:
            if hasattr(base, '_schema'):
                schema.update(base._schema)
        
        for key, value in attrs.items():
            if isinstance(value, Field):
                schema[key] = value
        
        attrs['_schema'] = schema
        # Remove Field objects from class attributes
        for key in schema:
            if key in attrs:
                del attrs[key]

        return super().__new__(cls, name, bases, attrs)

class BaseModel(metaclass=ModelMeta):
    _EMAIL_REGEX = re.compile(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")
    _PHONE_REGEX = re.compile(r"^\+?1?\d{9,15}$")

    def __init__(self, **kwargs):
        self._errors = {}
        cleaned_data = self._validate(kwargs)
        if self._errors:
            raise ValidationError("Validation failed", self._errors)
        
        for key, value in cleaned_data.items():
            setattr(self, key, value)

    def _add_error(self, field, message):
        if field not in self._errors:
            self._errors[field] = []
        self._errors[field].append(message)

    def _validate(self, data):
        cleaned_data = {}
        for field_name, field_obj in self._schema.items():
            value = data.get(field_name)

            if value is None:
                if field_obj.default is not None:
                    value = field_obj.default() if callable(field_obj.default) else field_obj.default
                elif field_obj.required:
                    self._add_error(field_name, "This field is required.")
                    continue
                else:
                    cleaned_data[field_name] = None
                    continue
            
            # Type Coercion
            try:
                if field_obj.field_type == uuid.UUID:
                    value = uuid.UUID(str(value))
                elif field_obj.field_type == bool:
                    if str(value).lower() in ('false', '0', 'no'): value = False
                    else: value = bool(value)
                elif field_obj.field_type == datetime.datetime:
                     if isinstance(value, str) and value.endswith('Z'): value = value[:-1] + '+00:00'
                     value = datetime.datetime.fromisoformat(value)
                elif issubclass(field_obj.field_type, enum.Enum):
                    value = field_obj.field_type(value)
                else: # str, int, etc.
                    value = field_obj.field_type(value)
            except (ValueError, TypeError) as e:
                self._add_error(field_name, f"Invalid type. Expected {field_obj.field_type.__name__}. Error: {e}")
                continue

            # Custom Validators
            if field_obj.validator:
                validator_func = getattr(self, f"_validate_{field_obj.validator}", None)
                if validator_func and not validator_func(value):
                    self._add_error(field_name, f"Invalid value for validator '{field_obj.validator}'.")

            cleaned_data[field_name] = value
        return cleaned_data

    def _validate_email(self, value):
        return self._EMAIL_REGEX.match(value)

    def _validate_phone(self, value):
        return self._PHONE_REGEX.match(value)

    def to_dict(self):
        data = {}
        for field_name in self._schema:
            value = getattr(self, field_name, None)
            if isinstance(value, (datetime.datetime, datetime.date)):
                data[field_name] = value.isoformat()
            elif isinstance(value, uuid.UUID):
                data[field_name] = str(value)
            elif isinstance(value, enum.Enum):
                data[field_name] = value.value
            else:
                data[field_name] = value
        return data

    def to_json(self):
        return json.dumps(self.to_dict())

    def to_xml(self):
        root = ET.Element(self.__class__.__name__)
        for key, val in self.to_dict().items():
            child = ET.SubElement(root, key)
            child.text = str(val) if val is not None else ""
        return ET.tostring(root, encoding='unicode')

    @classmethod
    def from_json(cls, json_str):
        return cls(**json.loads(json_str))

    @classmethod
    def from_xml(cls, xml_str):
        root = ET.fromstring(xml_str)
        data = {child.tag: child.text for child in root}
        return cls(**data)

class User(BaseModel):
    id = Field(uuid.UUID, default=uuid.uuid4)
    email = Field(str, validator='email')
    phone_number = Field(str, required=False, validator='phone')
    password_hash = Field(str)
    role = Field(Role)
    is_active = Field(bool, default=True)
    created_at = Field(datetime.datetime, default=lambda: datetime.datetime.now(datetime.timezone.utc))

# ==============================================================================
# 3. DEMONSTRATION
# ==============================================================================
if __name__ == '__main__':
    print("--- Variation 3: Metaclass/Declarative Approach ---")

    # --- 1. Successful Instantiation ---
    valid_data = {
        "id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
        "email": "correct@email.com",
        "phone_number": "+19876543210",
        "password_hash": "another_secure_hash",
        "role": "USER",
        "is_active": "true",
        "created_at": "2023-11-20T12:30:00Z"
    }
    print("\n[1] Testing valid data...")
    try:
        user = User(**valid_data)
        print(f"Successfully created User instance: email={user.email}, role={user.role}")
        assert user.is_active is True
    except ValidationError as e:
        print(f"Validation failed unexpectedly: {e.errors}")

    # --- 2. Failed Instantiation ---
    invalid_data = {
        "email": "bad-email",
        "phone_number": "123",
        "role": "SUPERVISOR",
    }
    print("\n[2] Testing invalid data...")
    try:
        User(**invalid_data)
        print("Validation succeeded unexpectedly.")
    except ValidationError as e:
        print(f"Validation failed as expected. Errors: {json.dumps(e.errors, indent=2)}")

    # --- 3. JSON Serialization/Deserialization ---
    print("\n[3] Testing JSON serialization...")
    json_output = user.to_json()
    print(f"Serialized JSON: {json_output}")
    rehydrated_user = User.from_json(json_output)
    print(f"Rehydrated from JSON: email={rehydrated_user.email}")
    assert rehydrated_user.id == user.id

    # --- 4. XML Serialization/Deserialization ---
    print("\n[4] Testing XML serialization...")
    xml_output = user.to_xml()
    print(f"Serialized XML: {xml_output}")
    rehydrated_user_xml = User.from_xml(xml_output)
    print(f"Rehydrated from XML: email={rehydrated_user_xml.email}")
    assert rehydrated_user_xml.email == user.email