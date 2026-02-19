<raw_code>
// Variation 1: The "By-the-Book" Developer
// Style: Follows official NestJS documentation closely. Clear, explicit, and well-structured with separate files.
// Key Features: Global ValidationPipe, class-based custom validator, service-layer XML handling, basic serialization.

// --- MOCK package.json dependencies ---
// {
//   "dependencies": {
//     "@nestjs/common": "^10.0.0",
//     "@nestjs/core": "^10.0.0",
//     "@nestjs/platform-express": "^10.0.0",
//     "class-validator": "^0.14.0",
//     "class-transformer": "^0.5.1",
//     "reflect-metadata": "^0.1.13",
//     "rxjs": "^7.8.1",
//     "uuid": "^9.0.0",
//     "xml2js": "^0.6.2"
//   },
//   "devDependencies": {
//     "@types/xml2js": "^0.4.11",
//     "@types/uuid": "^9.0.2"
//   }
// }

import {
  Injectable,
  Controller,
  Post,
  Body,
  ValidationPipe,
  Module,
  INestApplication,
  HttpCode,
  HttpStatus,
  BadRequestException,
  Get,
  Param,
  ParseUUIDPipe,
  Headers,
} from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import {
  IsEmail,
  IsNotEmpty,
  IsString,
  MinLength,
  IsEnum,
  IsOptional,
  IsPhoneNumber,
  Validate,
  ValidatorConstraint,
  ValidatorConstraintInterface,
  ValidationArguments,
} from 'class-validator';
import { Exclude, Expose, plainToInstance } from 'class-transformer';
import { v4 as uuidv4 } from 'uuid';
import * as xml2js from 'xml2js';

// --- Enums (src/user/enums/user-role.enum.ts) ---
export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

// --- Custom Validator (src/shared/validators/is-valid-phone.validator.ts) ---
@ValidatorConstraint({ name: 'isValidPhone', async: false })
export class IsValidPhoneConstraint implements ValidatorConstraintInterface {
  validate(phone: string, args: ValidationArguments) {
    if (!phone) return true; // Optional fields should be handled by @IsOptional
    const phoneRegex = /^\+[1-9]\d{1,14}$/; // E.164 format
    return phoneRegex.test(phone);
  }

  defaultMessage(args: ValidationArguments) {
    return 'Phone number ($value) must be in E.164 format (e.g., +12125552368)';
  }
}

// --- DTOs (src/user/dto/create-user.dto.ts) ---
export class CreateUserDto {
  @IsEmail({}, { message: 'A valid email address is required.' })
  @IsNotEmpty()
  email: string;

  @IsString()
  @MinLength(8, { message: 'Password must be at least 8 characters long.' })
  @IsNotEmpty()
  password: string;

  @IsEnum(UserRole)
  @IsOptional()
  role: UserRole = UserRole.USER;

  @IsOptional()
  @Validate(IsValidPhoneConstraint)
  phone?: string;
}

// --- Response DTO / Entity (src/user/dto/user.response.dto.ts) ---
// This class is used for serialization to control what data is sent to the client.
export class UserResponseDto {
  @Expose()
  id: string;

  @Expose()
  email: string;

  @Exclude() // password_hash should never be sent out.
  password_hash: string;

  @Expose()
  role: UserRole;

  @Expose()
  is_active: boolean;

  @Expose()
  created_at: Date;

  constructor(partial: Partial<UserResponseDto>) {
    Object.assign(this, partial);
  }
}

// --- Service (src/user/user.service.ts) ---
@Injectable()
export class UserService {
  private readonly users: UserResponseDto[] = [];

  async createUser(dto: CreateUserDto): Promise<UserResponseDto> {
    const newUser = {
      id: uuidv4(),
      email: dto.email,
      password_hash: `hashed_${dto.password}`, // Mock hashing
      role: dto.role,
      is_active: true,
      created_at: new Date(),
    };
    this.users.push(newUser);
    return new UserResponseDto(newUser);
  }

  async findUserById(id: string): Promise<UserResponseDto | null> {
    return this.users.find(user => user.id === id) || null;
  }

  async createUserFromXml(xmlString: string): Promise<UserResponseDto> {
    try {
      const parser = new xml2js.Parser({ explicitArray: false });
      const result = await parser.parseStringPromise(xmlString);
      const userNode = result.user;

      // Manually create a DTO and validate it
      const userDto = new CreateUserDto();
      userDto.email = userNode.email;
      userDto.password = userNode.password;
      userDto.role = userNode.role || UserRole.USER;
      userDto.phone = userNode.phone;

      // We can't use the ValidationPipe here, so we'd need to import `validate` from class-validator
      // For simplicity, we'll assume the data is valid and proceed.
      // In a real app: const errors = await validate(userDto); if (errors.length > 0) throw...

      return this.createUser(userDto);
    } catch (error) {
      throw new BadRequestException('Invalid XML format or data.');
    }
  }

  async getUserAsXml(id: string): Promise<string> {
    const user = await this.findUserById(id);
    if (!user) {
        return '';
    }
    const builder = new xml2js.Builder({ rootName: 'user' });
    // We manually exclude the password hash for the XML response
    const userForXml = { ...user };
    delete userForXml.password_hash;
    return builder.buildObject(userForXml);
  }
}

// --- Controller (src/user/user.controller.ts) ---
@Controller('users')
export class UserController {
  constructor(private readonly userService: UserService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async createUser(@Body() createUserDto: CreateUserDto): Promise<UserResponseDto> {
    const user = await this.userService.createUser(createUserDto);
    // Use plainToInstance to ensure response respects Exclude/Expose decorators
    return plainToInstance(UserResponseDto, user);
  }

  @Post('from-xml')
  @HttpCode(HttpStatus.CREATED)
  async createUserFromXml(@Body() xmlData: string): Promise<UserResponseDto> {
    const user = await this.userService.createUserFromXml(xmlData);
    return plainToInstance(UserResponseDto, user);
  }

  @Get(':id')
  async getUserById(@Param('id', ParseUUIDPipe) id: string): Promise<UserResponseDto> {
    const user = await this.userService.findUserById(id);
    return plainToInstance(UserResponseDto, user);
  }

  @Get(':id/to-xml')
  async getUserAsXml(@Param('id', ParseUUIDPipe) id: string, @Headers() headers): Promise<string> {
    headers['content-type'] = 'application/xml';
    return this.userService.getUserAsXml(id);
  }
}

// --- Module (src/user/user.module.ts) ---
@Module({
  controllers: [UserController],
  providers: [UserService, IsValidPhoneConstraint], // Register custom validator
})
export class UserModule {}

// --- Main Application Entrypoint (src/main.ts) ---
async function bootstrap() {
  const app = await NestFactory.create(UserModule, { logger: false });

  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true, // Strip away properties that do not have any decorators
      forbidNonWhitelisted: true, // Throw an error if non-whitelisted values are provided
      transform: true, // Automatically transform payloads to be objects typed according to their DTO classes
      disableErrorMessages: false, // Show error messages
    }),
  );

  // This is a mock server startup for demonstration.
  // In a real app, you would have `await app.listen(3000);`
  console.log('Variation 1: "By-the-Book" Developer setup is complete.');
  // You can now imagine making HTTP requests to the controller endpoints.
}

bootstrap();
</raw_code>