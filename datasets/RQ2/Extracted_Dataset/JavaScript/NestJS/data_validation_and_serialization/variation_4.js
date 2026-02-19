<raw_code>
// Variation 4: The "Modern & Modular" Developer
// Style: Focuses on clean module boundaries, modern TS, and leveraging NestJS's DI and interceptors.
// Key Features: ClassSerializerInterceptor for serialization, custom composite decorators, XML handling via an interceptor.

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
  UseInterceptors,
  ClassSerializerInterceptor,
  INestApplication,
  applyDecorators,
  ExecutionContext,
  CallHandler,
  NestInterceptor,
  Get,
  Param,
  ParseUUIDPipe,
  Query,
} from '@nestjs/common';
import { NestFactory, Reflector } from '@nestjs/core';
import {
  IsEmail,
  IsNotEmpty,
  IsString,
  MinLength,
  IsEnum,
  IsOptional,
  IsPhoneNumber,
  Matches,
} from 'class-validator';
import { Exclude, Expose, Type } from 'class-transformer';
import { v4 as uuidv4 } from 'uuid';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { XMLBuilder, XMLParser } from 'fast-xml-parser';

// --- Shared Decorators (src/common/decorators/validation.decorators.ts) ---
export function IsStrongPassword() {
  return applyDecorators(
    IsString(),
    MinLength(8),
    Matches(/((?=.*\d)|(?=.*\W+))(?![.\n])(?=.*[A-Z])(?=.*[a-z]).*$/, {
      message: 'Password too weak. It must contain an uppercase letter, a lowercase letter, and a number or special character.',
    }),
  );
}

// --- Shared Interceptors (src/common/interceptors/xml.interceptor.ts) ---
@Injectable()
export class XmlInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const request = context.switchToHttp().getRequest();
    const acceptHeader = request.headers['accept'];

    if (acceptHeader && acceptHeader.includes('application/xml')) {
      const response = context.switchToHttp().getResponse();
      response.header('Content-Type', 'application/xml');
      
      return next.handle().pipe(
        map(data => {
          const builder = new XMLBuilder({ format: true });
          // Assumes data is an object with a root key, or just a plain object
          const rootKey = Array.isArray(data) ? 'data' : Object.keys(data)[0] || 'data';
          return builder.build({ [rootKey]: data });
        }),
      );
    }
    return next.handle();
  }
}

// --- User Module ---

// (src/user/user.enums.ts)
export enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }

// (src/user/user.entity.ts) - A mock entity class for serialization
export class User {
  @Expose() id: string;
  @Expose() email: string;
  @Exclude() passwordHash: string;
  @Expose() role: UserRole;
  @Expose() isActive: boolean;
  @Expose() @Type(() => String) createdAt: Date;

  constructor(partial: Partial<User>) {
    Object.assign(this, partial);
  }
}

// (src/user/dto/create-user.dto.ts)
export class CreateUserDto {
  @IsEmail()
  email: string;

  @IsStrongPassword()
  password: string;

  @IsEnum(UserRole)
  @IsOptional()
  role: UserRole = UserRole.USER;

  @IsPhoneNumber('ZZ') // ZZ is a generic region code for any valid number
  @IsOptional()
  phone?: string;
}

// (src/user/user.service.ts)
@Injectable()
export class UserService {
  private readonly users: User[] = [];

  create(dto: CreateUserDto): User {
    const user = new User({
      id: uuidv4(),
      email: dto.email,
      passwordHash: `hashed_${dto.password}`,
      role: dto.role,
      isActive: true,
      createdAt: new Date(),
    });
    this.users.push(user);
    return user;
  }

  findById(id: string): User | null {
    return this.users.find(u => u.id === id) || null;
  }
}

// (src/user/user.controller.ts)
@Controller('users')
@UseInterceptors(ClassSerializerInterceptor) // Enables @Exclude/@Expose globally for this controller
export class UserController {
  constructor(private readonly userService: UserService) {}

  @Post()
  createUser(@Body() createUserDto: CreateUserDto): User {
    return this.userService.create(createUserDto);
  }

  @Post('from-xml')
  createUserFromXml(@Body() xmlData: string): User {
    const parser = new XMLParser();
    const parsed = parser.parse(xmlData);
    const dto = new CreateUserDto();
    Object.assign(dto, parsed.user);
    // In a real app, you'd need to run validation manually on this DTO.
    return this.userService.create(dto);
  }

  @Get(':id')
  @UseInterceptors(XmlInterceptor) // Apply XML interceptor for this route
  getUser(
    @Param('id', new ParseUUIDPipe({ version: '4' })) id: string,
    @Query('transform') transform: boolean, // Demonstrates implicit type coercion
  ): User {
    console.log(`Type of 'transform' query param: ${typeof transform}`); // Will be boolean
    return this.userService.findById(id);
  }
}

// (src/user/user.module.ts)
@Module({
  controllers: [UserController],
  providers: [UserService],
})
export class UserModule {}

// --- Main Application ---
// (src/main.ts)
async function bootstrap() {
  const app = await NestFactory.create(UserModule, { logger: false });

  app.useGlobalPipes(
    new ValidationPipe({
      transform: true,
      transformOptions: {
        enableImplicitConversion: true, // Coerces primitive types automatically
      },
      whitelist: true,
      forbidNonWhitelisted: true,
    }),
  );

  // The ClassSerializerInterceptor is applied at the controller level in this example,
  // but could also be applied globally:
  // app.useGlobalInterceptors(new ClassSerializerInterceptor(app.get(Reflector)));

  console.log('Variation 4: "Modern & Modular" Developer setup is complete.');
  // In a real app: await app.listen(3000);
}

bootstrap();
</raw_code>