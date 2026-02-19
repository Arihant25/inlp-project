<script>
// VARIATION 2: The "Functional & Concise" Developer
// STYLE: Prefers functional approaches, concise syntax, and leveraging built-in decorators.
// DEPENDENCIES: @nestjs/common, @nestjs/core, nestjs-throttler, class-transformer, class-validator, reflect-metadata, rxjs, express

// --- FILENAME: src/domain/user.dto.ts ---
import { Exclude } from 'class-transformer';

export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

export class UserResponseDto {
  id: string;
  email: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;

  @Exclude()
  password_hash: string;

  constructor(partial: Partial<UserResponseDto>) {
    Object.assign(this, partial);
  }
}

// --- FILENAME: src/filters/error.filter.ts ---
import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';
import { Request, Response } from 'express';

@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger(AllExceptionsFilter.name);

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();
    const status =
      exception instanceof HttpException
        ? exception.getStatus()
        : HttpStatus.INTERNAL_SERVER_ERROR;

    const errorResponse = {
      statusCode: status,
      path: request.url,
      error: exception instanceof HttpException ? exception.message : 'Internal Server Error',
    };

    this.logger.error(
      `HTTP Status: ${status} Error Message: ${JSON.stringify(errorResponse)}`,
      exception instanceof Error ? exception.stack : undefined,
    );

    response.status(status).json(errorResponse);
  }
}

// --- FILENAME: src/app.controller.ts ---
import { Controller, Get, Param, UseFilters, ClassSerializerInterceptor, UseInterceptors, InternalServerErrorException } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { v4 as uuidv4 } from 'uuid';

@Controller('users')
@UseFilters(new AllExceptionsFilter())
export class UserController {
  // Mock data representing a full user entity from a database
  private mockUser = {
    id: uuidv4(),
    email: 'concise@example.com',
    password_hash: 'another_very_secret_hash',
    role: UserRole.USER,
    is_active: true,
    created_at: new Date(),
  };

  @Get(':id')
  @Throttle(20, 60) // More generous limit for this specific route
  @UseInterceptors(ClassSerializerInterceptor) // Activates @Exclude in DTO
  async findUser(@Param('id') id: string): Promise<UserResponseDto> {
    console.log(`Finding user ${id}`);
    // The DTO constructor automatically maps fields and prepares for serialization
    return new UserResponseDto(this.mockUser);
  }

  @Get('error/internal')
  testError() {
    throw new InternalServerErrorException('A database connection failed.');
  }
}

// --- FILENAME: src/app.module.ts ---
import { Module, NestModule, MiddlewareConsumer, RequestMethod } from '@nestjs/common';
import { ThrottlerModule, ThrottlerGuard } from '@nestjs/throttler';
import { APP_GUARD } from '@nestjs/core';
import { Request, Response, NextFunction } from 'express';

// Functional Middleware for Request Logging
const requestLogger = (req: Request, res: Response, next: NextFunction) => {
  console.log(`[REQ] ${req.method} ${req.originalUrl}`);
  next();
};

@Module({
  imports: [
    ThrottlerModule.forRoot({ ttl: 60, limit: 15 }), // Global rate limit
  ],
  controllers: [UserController],
  providers: [{ provide: APP_GUARD, useClass: ThrottlerGuard }],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer
      .apply(requestLogger) // Apply functional middleware
      .forRoutes({ path: '*', method: RequestMethod.ALL });
  }
}

// --- FILENAME: src/main.ts ---
import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);

  // 1. CORS Handling (concise version)
  app.enableCors(); // Enables CORS with default permissive settings

  // 2. Global Pipes for validation (often used with interceptors)
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));

  // Note: Error handling is applied at the controller level with @UseFilters.
  // Request logging is a functional middleware in AppModule.
  // Rate limiting is a global guard.
  // Transformation is handled by ClassSerializerInterceptor and @Exclude decorator.

  console.log('Variation 2: "Functional & Concise" application bootstrapped.');
  console.log('CORS enabled with default settings.');
  console.log('Functional request logger middleware applied.');
  console.log('Global ThrottlerGuard (Rate Limiting) applied.');
  console.log('ClassSerializerInterceptor used for response transformation.');
  // await app.listen(3000);
}

// bootstrap(); // To run, uncomment this line.
</script>