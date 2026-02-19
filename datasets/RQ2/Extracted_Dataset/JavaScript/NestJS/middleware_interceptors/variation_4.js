<script>
// VARIATION 4: The "Modern & Modular" Developer
// STYLE: Focuses on reusable modules, custom decorators, and advanced patterns like reflection.
// DEPENDENCIES: @nestjs/common, @nestjs/core, nestjs-throttler, reflect-metadata, rxjs, express

// --- FILENAME: src/domain/entities.ts ---
export enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
export class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- FILENAME: src/core/decorators/mask-fields.decorator.ts ---
import { SetMetadata } from '@nestjs/common';
export const MASK_FIELDS_KEY = 'mask_fields';
// Custom decorator to specify which fields to remove from the response
export const Mask = (...fields: string[]) => SetMetadata(MASK_FIELDS_KEY, fields);

// --- FILENAME: src/core/interceptors/masking.interceptor.ts ---
import {
  Injectable,
  NestInterceptor,
  ExecutionContext,
  CallHandler,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { cloneDeep } from 'lodash'; // A safe way to deep clone

@Injectable()
export class MaskingInterceptor implements NestInterceptor {
  constructor(private reflector: Reflector) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const fieldsToMask = this.reflector.get<string[]>(
      MASK_FIELDS_KEY,
      context.getHandler(),
    );

    return next.handle().pipe(
      map(data => {
        if (!fieldsToMask || !data) {
          return data;
        }
        // Use a deep clone to avoid mutating the original object
        const dataClone = cloneDeep(data);
        this.removeFields(dataClone, fieldsToMask);
        return dataClone;
      }),
    );
  }

  private removeFields(obj: any, fields: string[]) {
    if (obj === null || typeof obj !== 'object') {
      return;
    }
    if (Array.isArray(obj)) {
      obj.forEach(item => this.removeFields(item, fields));
    } else {
      for (const field of fields) {
        if (obj.hasOwnProperty(field)) {
          delete obj[field];
        }
      }
      for (const key in obj) {
        this.removeFields(obj[key], fields);
      }
    }
  }
}

// --- FILENAME: src/core/filters/base-exception.filter.ts ---
import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
  Logger,
} from '@nestjs/common';

@Catch()
export class BaseExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger('ExceptionFilter');

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse();
    const request = ctx.getRequest();

    const status =
      exception instanceof HttpException
        ? exception.getStatus()
        : HttpStatus.INTERNAL_SERVER_ERROR;

    const errorResponse = {
      code: status,
      errorId: `err-${Date.now()}`,
      message: exception instanceof Error ? exception.message : 'An internal error occurred.',
      path: request.url,
    };
    
    this.logger.error(`Error ${errorResponse.errorId}: ${errorResponse.message}`, exception instanceof Error ? exception.stack : '');

    response.status(status).json(errorResponse);
  }
}

// --- FILENAME: src/features/users/users.controller.ts ---
import { Controller, Get, Param, UseInterceptors, BadRequestException } from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';

@Controller('users')
@UseInterceptors(MaskingInterceptor) // Apply the interceptor at the controller level
export class UsersController {
  @Get('profile/:id')
  // Use the custom decorator to mask sensitive fields for this specific endpoint
  @Mask('password_hash', 'role')
  getUserProfile(@Param('id') id: string): User {
    return {
      id: uuidv4(),
      email: 'modular@example.com',
      password_hash: 'hash_to_be_masked',
      role: UserRole.ADMIN, // This will also be masked
      is_active: true,
      created_at: new Date(),
    };
  }

  @Get('details/:id')
  // No @Mask decorator, so password_hash will be returned (but still masked by the interceptor if logic was global)
  // For this example, we show it being returned.
  getUserDetails(@Param('id') id: string): User {
     if (id === 'invalid') {
        throw new BadRequestException('Invalid user ID format.');
     }
     return {
      id: uuidv4(),
      email: 'details@example.com',
      password_hash: 'this_hash_is_visible',
      role: UserRole.USER,
      is_active: false,
      created_at: new Date(),
    };
  }
}

// --- FILENAME: src/app.module.ts ---
import { Module, NestModule, MiddlewareConsumer } from '@nestjs/common';
import { ThrottlerModule, ThrottlerGuard } from 'nestjs-throttler';
import { APP_GUARD, APP_FILTER } from '@nestjs/core';
import { Request, Response, NextFunction } from 'express';

// A simple functional middleware for logging
const loggerFn = (req: Request, res: Response, next: NextFunction) => {
  console.log(`Incoming Request... ${req.method} ${req.url}`);
  next();
};

@Module({
  imports: [
    ThrottlerModule.forRoot([{
      name: 'default',
      ttl: 60000, // 60 seconds in milliseconds
      limit: 10,
    }]),
  ],
  controllers: [UsersController],
  providers: [
    { provide: APP_GUARD, useClass: ThrottlerGuard },
    { provide: APP_FILTER, useClass: BaseExceptionFilter },
  ],
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(loggerFn).forRoutes(UsersController);
  }
}

// --- FILENAME: src/main.ts ---
import { NestFactory, Reflector } from '@nestjs/core';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);

  // CORS Handling
  app.enableCors();

  // Rate Limiting is handled via the global ThrottlerGuard.
  // Request Logging is a functional middleware applied in AppModule.
  // Response Transformation is handled by the custom MaskingInterceptor and @Mask decorator.
  // Error Handling is managed by the global BaseExceptionFilter.
  
  // The Reflector is needed for the MaskingInterceptor to work.
  // It's automatically available for injection, but we note its importance here.
  const reflector = app.get(Reflector);

  console.log('Variation 4: "Modern & Modular" application bootstrapped.');
  console.log('CORS enabled.');
  console.log('Global BaseExceptionFilter and ThrottlerGuard applied.');
  console.log('MaskingInterceptor and @Mask decorator are used for flexible response transformation.');
  // await app.listen(3000);
}

// bootstrap(); // To run, uncomment this line.
</script>