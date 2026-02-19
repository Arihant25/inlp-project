<raw_code>
// Variation 3: The "Enterprise/OOP Purist" Developer
// Style: Heavily object-oriented, uses base classes, inheritance, and dedicated injectable services for concerns like XML.
// Key Features: Mapped types for DTOs, injectable class-based validator, dedicated XML service, custom extended ValidationPipe.

// --- MOCK package.json dependencies ---
// {
//   "dependencies": {
//     "@nestjs/common": "^10.0.0",
//     "@nestjs/core": "^10.0.0",
//     "@nestjs/platform-express": "^10.0.0",
//     "@nestjs/mapped-types": "^2.0.2",
//     "class-validator": "^0.14.0",
//     "class-transformer": "^0.5.1",
//     "reflect-metadata": "^0.1.13",
//     "rxjs": "^7.8.1",
//     "uuid": "^9.0.0",
//     "fast-xml-parser": "^4.2.5"
//   },
//   "devDependencies": {
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
  HttpCode,
  HttpStatus,
  BadRequestException,
  ArgumentMetadata,
  PipeTransform,
  Get,
  Param,
  ParseUUIDPipe,
  Header,
} from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import {
  IsEmail,
  IsNotEmpty,
  IsString,
  MinLength,
  IsEnum,
  IsOptional,
  Validate,
  ValidatorConstraint,
  ValidatorConstraintInterface,
  ValidationArguments,
  IsBoolean,
  IsDate,
} from 'class-validator';
import { Exclude, Transform } from 'class-transformer';
import { PartialType } from '@nestjs/mapped-types';
import { v4 as uuidv4 } from 'uuid';
import { XMLParser, XMLBuilder } from 'fast-xml-parser';

// --- Domain (src/domain/user.entity.ts) ---
export enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
export class User {
  id: string;
  email: string;
  @Exclude() password_hash: string;
  role: UserRole;
  is_active: boolean;
  @Transform(({ value }) => value.toISOString())
  created_at: Date;
}

// --- DTOs (src/user/dto/user.dto.ts) ---
class BaseUserDto {
  @IsEmail()
  @IsNotEmpty()
  email: string;

  @IsString()
  @MinLength(8)
  @IsNotEmpty()
  password: string;

  @IsEnum(UserRole)
  @IsOptional()
  role: UserRole = UserRole.USER;
}

export class CreateUserDto extends BaseUserDto {}
export class UpdateUserDto extends PartialType(BaseUserDto) {}

// --- Shared Services (src/shared/xml.service.ts) ---
@Injectable()
export class XmlSerializationService {
  private readonly parser = new XMLParser();
  private readonly builder = new XMLBuilder({ format: true });

  public parse<T>(xmlString: string, rootNode: string): T {
    try {
      const parsed = this.parser.parse(xmlString);
      return parsed[rootNode];
    } catch (error) {
      throw new BadRequestException('Failed to parse XML');
    }
  }

  public build(rootNode: string, data: object): string {
    return this.builder.build({ [rootNode]: data });
  }
}

// --- Custom Validator (src/shared/validators/phone.validator.ts) ---
@ValidatorConstraint({ name: 'phoneValidator', async: false })
@Injectable() // Can now inject dependencies if needed
export class PhoneValidator implements ValidatorConstraintInterface {
  validate(value: any, args: ValidationArguments) {
    if (!value) return true;
    return typeof value === 'string' && /^\+[1-9]\d{1,14}$/.test(value);
  }
  defaultMessage(args: ValidationArguments) {
    return `Phone number must be a valid E.164 string.`;
  }
}

// --- Custom Pipe (src/shared/pipes/dto-validation.pipe.ts) ---
@Injectable()
export class DtoValidationPipe extends ValidationPipe implements PipeTransform {
  constructor() {
    super({
      transform: true,
      whitelist: true,
      forbidNonWhitelisted: true,
      exceptionFactory: (errors) => new BadRequestException({
        message: 'Input data validation failed',
        errors: errors.map(e => ({
            property: e.property,
            constraints: e.constraints,
        })),
      }),
    });
  }
  // Could override transform method for more complex logic
}

// --- User Service (src/user/user.service.ts) ---
@Injectable()
export class UserService {
  private db: Map<string, User> = new Map();
  public create(dto: CreateUserDto): User {
    const user = new User();
    user.id = uuidv4();
    user.email = dto.email;
    user.password_hash = `hashed::${dto.password}`;
    user.role = dto.role;
    user.is_active = true;
    user.created_at = new Date();
    this.db.set(user.id, user);
    return user;
  }
  public findOne(id: string): User {
    return this.db.get(id);
  }
}

// --- User Controller (src/user/user.controller.ts) ---
@Controller('users')
export class UserController {
  constructor(
    private readonly userService: UserService,
    private readonly xmlService: XmlSerializationService,
  ) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  public createUser(@Body(DtoValidationPipe) dto: CreateUserDto): User {
    return this.userService.create(dto);
  }

  @Post('from-xml')
  @HttpCode(HttpStatus.CREATED)
  public createUserFromXml(@Body() xmlString: string): User {
    const dto = this.xmlService.parse<CreateUserDto>(xmlString, 'user');
    // Here we would manually validate the DTO created from XML
    return this.userService.create(dto);
  }

  @Get(':id')
  public findUser(@Param('id', ParseUUIDPipe) id: string): User {
    return this.userService.findOne(id);
  }

  @Get(':id/to-xml')
  @Header('Content-Type', 'application/xml')
  public findUserAsXml(@Param('id', ParseUUIDPipe) id: string): string {
    const user = this.userService.findOne(id);
    return this.xmlService.build('user', user);
  }
}

// --- Modules & Main ---
@Module({
  providers: [XmlSerializationService, PhoneValidator],
  exports: [XmlSerializationService, PhoneValidator],
})
class SharedModule {}

@Module({
  imports: [SharedModule],
  controllers: [UserController],
  providers: [UserService],
})
class UserModule {}

async function bootstrap() {
  const app = await NestFactory.create(UserModule, { logger: false });
  // No global pipe, as we apply it at the method level for more control.
  console.log('Variation 3: "Enterprise/OOP Purist" Developer setup is complete.');
  // In a real app: await app.listen(3000);
}

bootstrap();
</raw_code>