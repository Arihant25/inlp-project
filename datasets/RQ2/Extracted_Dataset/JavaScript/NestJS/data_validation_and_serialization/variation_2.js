<raw_code>
// Variation 2: The "Functional & Concise" Developer
// Style: Prefers functional patterns, less boilerplate, and local configuration.
// Key Features: Per-controller ValidationPipe, custom exception factory, utility functions for XML, functional custom validator.

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
  UsePipes,
  Get,
  Param,
  Query,
  DefaultValuePipe,
  ParseBoolPipe,
} from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import {
  IsEmail,
  IsNotEmpty,
  IsString,
  MinLength,
  IsEnum,
  IsOptional,
  registerDecorator,
  ValidationOptions,
  ValidationArguments,
} from 'class-validator';
import { Exclude, Type, instanceToPlain } from 'class-transformer';
import { v4 as uuidv4 } from 'uuid';
import { XMLParser, XMLBuilder } from 'fast-xml-parser';

// --- Enums & DTOs (can be co-located with the controller for small modules) ---
enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

// Custom validator as a simple function
function IsE164PhoneNumber(validationOptions?: ValidationOptions) {
  return function (object: Object, propertyName: string) {
    registerDecorator({
      name: 'isE164PhoneNumber',
      target: object.constructor,
      propertyName: propertyName,
      options: {
        message: 'Phone number must be a valid E.164 formatted string (e.g., +14155552671)',
        ...validationOptions,
      },
      validator: {
        validate(value: any, args: ValidationArguments) {
          if (typeof value !== 'string') return false;
          const phoneRegex = /^\+[1-9]\d{1,14}$/;
          return phoneRegex.test(value);
        },
      },
    });
  };
}

class CreateUserDto {
  @IsEmail()
  email: string;

  @IsString()
  @MinLength(8)
  password: string;

  @IsEnum(UserRole)
  @IsOptional()
  role?: UserRole = UserRole.USER;

  @IsE164PhoneNumber()
  @IsOptional()
  phone?: string;
}

class UserEntity {
  id: string;
  email: string;
  @Exclude() password_hash: string;
  role: UserRole;
  is_active: boolean;
  @Type(() => Date) created_at: Date;
}

// --- XML Utilities (src/utils/xml.util.ts) ---
const xmlParser = new XMLParser();
const xmlBuilder = new XMLBuilder({ format: true });

const parseXml = <T>(xml: string): T => {
  try {
    return xmlParser.parse(xml);
  } catch (e) {
    throw new BadRequestException('Invalid XML format.');
  }
};

const buildXml = (obj: object): string => {
  return xmlBuilder.build(obj);
};

// --- Service (src/user/user.service.ts) ---
@Injectable()
class UserService {
  private readonly users: Map<string, UserEntity> = new Map();

  create = (dto: CreateUserDto): UserEntity => {
    const user = new UserEntity();
    user.id = uuidv4();
    user.email = dto.email;
    user.password_hash = `hashed_${dto.password}`;
    user.role = dto.role;
    user.is_active = false; // e.g., require email verification
    user.created_at = new Date();
    this.users.set(user.id, user);
    return user;
  };

  findById = (id: string, activeOnly: boolean): UserEntity | undefined => {
    const user = this.users.get(id);
    if (activeOnly && user && !user.is_active) {
        return undefined;
    }
    return user;
  };
}

// --- Controller (src/user/user.controller.ts) ---
@Controller('users')
@UsePipes(new ValidationPipe({
    transform: true,
    whitelist: true,
    exceptionFactory: (errors) => {
      const formattedErrors = errors.map(err => ({
        field: err.property,
        message: Object.values(err.constraints).join(', '),
      }));
      return new BadRequestException({
        statusCode: HttpStatus.BAD_REQUEST,
        error: "Validation Failed",
        messages: formattedErrors,
      });
    },
  })
)
class UserController {
  constructor(private readonly userSvc: UserService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  createUserJson(@Body() body: CreateUserDto): object {
    const user = this.userSvc.create(body);
    return instanceToPlain(user); // Use instanceToPlain for serialization
  }

  @Post('from-xml')
  @HttpCode(HttpStatus.CREATED)
  createUserXml(@Body() xmlBody: string): object {
    const { user: userData } = parseXml<{ user: CreateUserDto }>(xmlBody);
    // Note: Manual validation would be needed here if not using a pipe.
    // This example assumes the XML data maps directly to the DTO.
    const user = this.userSvc.create(userData);
    return instanceToPlain(user);
  }

  @Get(':id')
  getUser(
    @Param('id') id: string,
    @Query('activeOnly', new DefaultValuePipe(false), ParseBoolPipe) activeOnly: boolean,
  ): object {
    // Demonstrates type coercion for query parameters
    const user = this.userSvc.findById(id, activeOnly);
    return instanceToPlain(user);
  }

  @Get(':id/to-xml')
  getUserAsXml(@Param('id') id: string, @Query('activeOnly', new DefaultValuePipe(true), ParseBoolPipe) activeOnly: boolean): string {
    const user = this.userSvc.findById(id, activeOnly);
    const plainUser = instanceToPlain(user);
    return buildXml({ user: plainUser });
  }
}

// --- Module & Main ---
@Module({
  controllers: [UserController],
  providers: [UserService],
})
class UserModule {}

async function bootstrap() {
  const app = await NestFactory.create(UserModule, { logger: false });
  console.log('Variation 2: "Functional & Concise" Developer setup is complete.');
  // In a real app: await app.listen(3000);
}

bootstrap();
</raw_code>