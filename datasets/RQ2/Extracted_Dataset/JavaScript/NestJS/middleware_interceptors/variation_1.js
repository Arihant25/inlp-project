<script>
// VARIATION 1: The "By-the-Book" Developer
// STYLE: Follows official NestJS documentation closely. Class-based, explicit, and verbose.
// DEPENDENCIES: @nestjs/common, @nestjs/core, nestjs-throttler, reflect-metadata, rxjs, express

// --- FILENAME: src/domain/user.entity.ts ---
export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

export class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- FILENAME: src/middleware/logger.middleware.ts ---
import { Injectable, NestMiddleware, Logger } from '@nestjs/common';
import { Request, Response, NextFunction } from 'express';

@Injectable()
export class LoggerMiddleware implements NestMiddleware {
  private readonly logger = new Logger('HTTP');

  use(request: Request, response: Response, next: NextFunction) {
    const { ip, method, originalUrl } = request;
    const userAgent = request.get('user-agent') || '';

    response.on('finish', () => {
      const { statusCode } = response;
      const contentLength = response.get('content-length');
      this.logger.log(
        `${method} ${originalUrl} ${statusCode} ${contentLength} - ${userAgent} ${ip}`,
      );
    });

    next();
  }
}

// --- FILENAME: src/interceptors/transform.interceptor.ts ---
import {
  Injectable,
  NestInterceptor,
  ExecutionContext,
  CallHandler,
} from '@nestjs/common';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

export interface Response<T> {
  data: T;
}

@Injectable()
export class TransformInterceptor<T>
  implements NestInterceptor<T, Response<T>>
{
  intercept(
    context: ExecutionContext,
    next: CallHandler,
  ): Observable<Response<T>> {
    return next.handle().pipe(
      map(data => {
        // Recursively remove password_hash from response
        const scrubData = (d: any) => {
          if (d && typeof d === 'object') {
            if (d.password_hash) {
              delete d.password_hash;
            }
            Object.keys(d).forEach(key => scrubData(d[key]));
          }
          return d;
        };
        return { data: scrubData(data) };
      }),
    );
  }
}

// --- FILENAME: src/filters/http-exception.filter.ts ---
import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
} from '@nestjs/common';

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse();
    const request = ctx.getRequest();

    const status =
      exception instanceof HttpException
        ? exception.getStatus()
        : HttpStatus.INTERNAL_SERVER_ERROR;

    const message =
      exception instanceof HttpException
        ? exception.getResponse()
        : 'Internal server error';

    response.status(status).json({
      statusCode: status,
      timestamp: new Date().toISOString(),
      path: request.url,
      message,
    });
  }
}

// --- FILENAME: src/app.controller.ts ---
import { Controller, Get, Post, UseInterceptors, Body, Param, ForbiddenException } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { v4 as uuidv4 } from 'uuid';

@Controller('users')
@UseInterceptors(TransformInterceptor)
export class AppController {
  private mockUser: User = {
    id: uuidv4(),
    email: 'test@example.com',
    password_hash: 'a_very_secret_hash_string',
    role: UserRole.ADMIN,
    is_active: true,
    created_at: new Date(),
  };

  @Get(':id')
  findOne(@Param('id') id: string): User {
    console.log(`Fetching user with id: ${id}`);
    return this.mockUser;
  }

  @Throttle(5, 60) // Override default rate limit: 5 requests per 60 seconds
  @Post()
  create(@Body() body: any): User {
    console.log('Creating user:', body);
    return this.mockUser;
  }
  
  @Get('error/test')
  testError() {
    throw new ForbiddenException('You do not have access to this resource.');
  }
}

// --- FILENAME: src/app.module.ts ---
import { Module, NestModule, MiddlewareConsumer } from '@nestjs/common';
import { ThrottlerModule, ThrottlerGuard } from '@nestjs/throttler';
import { APP_GUARD } from '@nestjs/core';

@Module({
  imports: [
    ThrottlerModule.forRoot({
      ttl: 60,
      limit: 10, // Default: 10 requests per 60 seconds
    }),
  ],
  controllers: [AppController],
  providers: [
    {
      provide: APP_GUARD,
      useClass: ThrottlerGuard,
    },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(LoggerMiddleware).forRoutes('*');
  }
}

// --- FILENAME: src/main.ts ---
import { NestFactory } from '@nestjs/core';
import { INestApplication } from '@nestjs/common';

async function bootstrap() {
  const app: INestApplication = await NestFactory.create(AppModule, { logger: false });

  // 1. CORS Handling
  app.enableCors({
    origin: 'https://example.com',
    methods: 'GET,HEAD,PUT,PATCH,POST,DELETE',
    credentials: true,
  });

  // 2. Global Error Handling
  app.useGlobalFilters(new HttpExceptionFilter());

  // Note: Request Logging, Rate Limiting, and Transformation are handled
  // in AppModule, AppController, and dedicated files.

  // This is a mock bootstrap for demonstration. In a real app, you would call app.listen(3000).
  console.log('Variation 1: "By-the-Book" application bootstrapped.');
  console.log('CORS enabled for https://example.com');
  console.log('Global HttpExceptionFilter applied.');
  console.log('LoggerMiddleware applied to all routes.');
  console.log('ThrottlerGuard (Rate Limiting) applied globally.');
  console.log('TransformInterceptor applied to AppController.');
}

// bootstrap(); // To run, uncomment this line.
</script>