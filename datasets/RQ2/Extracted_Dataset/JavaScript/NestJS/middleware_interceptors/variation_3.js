<script>
// VARIATION 3: The "Enterprise/OOP" Developer
// STYLE: Focuses on DI, services, abstraction, and clear separation of concerns.
// DEPENDENCIES: @nestjs/common, @nestjs/core, nestjs-throttler, reflect-metadata, rxjs, express

// --- FILENAME: src/domain/models.ts ---
export enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
export class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- FILENAME: src/shared/services/logging.service.ts ---
import { Injectable, Logger, Scope } from '@nestjs/common';

@Injectable({ scope: Scope.TRANSIENT })
export class LoggingService extends Logger {
  log(message: string, context?: string) {
    super.log(message, context || 'Application');
  }
  error(message: string, trace: string, context?: string) {
    super.error(message, trace, context || 'Application');
  }
}

// --- FILENAME: src/core/middleware/request-logging.middleware.ts ---
import { Injectable, NestMiddleware } from '@nestjs/common';
import { Request, Response, NextFunction } from 'express';

@Injectable()
export class RequestLoggingMiddleware implements NestMiddleware {
  // Injecting the abstract logging service
  constructor(private readonly loggingService: LoggingService) {}

  use(req: Request, res: Response, next: NextFunction) {
    const startTime = Date.now();
    res.on('finish', () => {
      const duration = Date.now() - startTime;
      const { method, originalUrl } = req;
      const { statusCode } = res;
      const message = `[${method}] ${originalUrl} -> ${statusCode} [${duration}ms]`;
      this.loggingService.log(message, 'RequestLifecycle');
    });
    next();
  }
}

// --- FILENAME: src/core/interceptors/api-response.interceptor.ts ---
import {
  CallHandler,
  ExecutionContext,
  Injectable,
  NestInterceptor,
} from '@nestjs/common';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

// This interceptor wraps every successful response in a consistent structure.
@Injectable()
export class ApiResponseInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const httpContext = context.switchToHttp();
    const response = httpContext.getResponse();
    
    return next.handle().pipe(
      map(data => {
        // Remove sensitive data before wrapping
        if (data && data.password_hash) {
            delete data.password_hash;
        }
        return {
          statusCode: response.statusCode,
          data,
        };
      }),
    );
  }
}

// --- FILENAME: src/core/filters/global-exception.filter.ts ---
import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
} from '@nestjs/common';

@Catch()
export class GlobalExceptionFilter implements ExceptionFilter {
  constructor(private readonly loggingService: LoggingService) {}

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse();
    const request = ctx.getRequest();
    const status =
      exception instanceof HttpException
        ? exception.getStatus()
        : HttpStatus.INTERNAL_SERVER_ERROR;

    const errorPayload = {
      statusCode: status,
      error: exception instanceof HttpException ? exception.name : 'InternalServerError',
      message: exception instanceof Error ? exception.message : 'An unexpected error occurred',
      timestamp: new Date().toISOString(),
      path: request.url,
    };

    this.loggingService.error(
      `Exception caught: ${errorPayload.message}`,
      exception instanceof Error ? exception.stack : '',
      'GlobalExceptionFilter'
    );

    response.status(status).json(errorPayload);
  }
}

// --- FILENAME: src/features/user/user.controller.ts ---
import { Controller, Get, Param, UseInterceptors, NotFoundException } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { v4 as uuidv4 } from 'uuid';

@Controller('users')
export class UserController {
  @Get(':id')
  @Throttle(10, 30)
  public findUserById(@Param('id') id: string): User {
    if (id === '0') {
        throw new NotFoundException(`User with ID ${id} not found.`);
    }
    return {
      id: uuidv4(),
      email: 'enterprise@example.com',
      password_hash: 'enterprise_grade_secure_hash',
      role: UserRole.ADMIN,
      is_active: true,
      created_at: new Date(),
    };
  }
}

// --- FILENAME: src/app.module.ts ---
import { Module, NestModule, MiddlewareConsumer } from '@nestjs/common';
import { ThrottlerModule, ThrottlerGuard } from '@nestjs-throttler';
import { APP_GUARD, APP_FILTER, APP_INTERCEPTOR } from '@nestjs/core';

@Module({
  imports: [
    ThrottlerModule.forRoot({ ttl: 60, limit: 100 }),
  ],
  controllers: [UserController],
  providers: [
    LoggingService,
    { provide: APP_GUARD, useClass: ThrottlerGuard },
    { provide: APP_FILTER, useFactory: (loggingService: LoggingService) => new GlobalExceptionFilter(loggingService), inject: [LoggingService] },
    { provide: APP_INTERCEPTOR, useClass: ApiResponseInterceptor },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(RequestLoggingMiddleware).forRoutes('*');
  }
}

// --- FILENAME: src/main.ts ---
import { NestFactory } from '@nestjs/core';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, {
    // Use a custom logger instance if needed, but here we rely on our service
    logger: ['error', 'warn'],
  });

  // CORS Configuration driven by a potential ConfigService (simulated here)
  const corsOptions = {
    origin: ['http://localhost:3000', 'https://production.ui'],
    methods: ['GET', 'POST', 'PUT', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization'],
  };
  app.enableCors(corsOptions);

  console.log('Variation 3: "Enterprise/OOP" application bootstrapped.');
  console.log('CORS configured with specific options.');
  console.log('Global providers for Filter, Guard, and Interceptor are registered in AppModule.');
  console.log('RequestLoggingMiddleware uses injected LoggingService.');
  // await app.listen(3000);
}

// bootstrap(); // To run, uncomment this line.
</script>