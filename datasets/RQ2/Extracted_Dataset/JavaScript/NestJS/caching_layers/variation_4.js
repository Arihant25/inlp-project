// Variation 4: The "Decorator-Driven" Approach
// This implementation uses custom decorators (`@Cacheable`, `@CacheEvict`) and a NestJS Interceptor
// to create an Aspect-Oriented Programming (AOP) style of caching.
// The service logic remains clean and declarative, with caching behavior defined via metadata.
// DEPENDENCIES: @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/cache-manager, cache-manager, reflect-metadata, rxjs, uuid

import {
  Injectable,
  Inject,
  Controller,
  Get,
  Param,
  NotFoundException,
  Module,
  SetMetadata,
  UseInterceptors,
  ExecutionContext,
  CallHandler,
  NestInterceptor,
  Delete,
  ParseUUIDPipe,
} from '@nestjs/common';
import { CacheModule, CACHE_MANAGER } from '@nestjs/cache-manager';
import { Cache } from 'cache-manager';
import { Reflector } from '@nestjs/core';
import { of, from, Observable } from 'rxjs';
import { switchMap, tap } from 'rxjs/operators';
import { v4 as uuidv4 } from 'uuid';

// --- Domain Schema ---
export enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
export class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- Mock Database ---
const MOCK_DB = new Map<string, User>();
const user1: User = { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'jane.doe@aop.com', password_hash: 'abc', role: UserRole.USER, is_active: true, created_at: new Date() };
MOCK_DB.set(user1.id, user1);

// --- Custom Decorators ---
export const CACHEABLE_KEY = 'isCacheable';
export const CACHE_EVICT_KEY = 'cacheEvictKey';
export const CACHE_TTL_KEY = 'cacheTTL';

// Decorator to mark a method's result as cacheable
export const Cacheable = (keyPrefix: string, ttl: number) => {
  return (target: any, propertyKey: string, descriptor: PropertyDescriptor) => {
    SetMetadata(CACHEABLE_KEY, keyPrefix)(target, propertyKey, descriptor);
    SetMetadata(CACHE_TTL_KEY, ttl)(target, propertyKey, descriptor);
  };
};

// Decorator to mark a method that should evict a cache entry
export const CacheEvict = (keyPrefix: string) => SetMetadata(CACHE_EVICT_KEY, keyPrefix);

// --- Caching Interceptor ---
@Injectable()
export class AopCacheInterceptor implements NestInterceptor {
  constructor(
    @Inject(CACHE_MANAGER) private cacheManager: Cache,
    private reflector: Reflector,
  ) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const handler = context.getHandler();
    const request = context.switchToHttp().getRequest();
    
    const cacheKeyPrefix = this.reflector.get<string>(CACHEABLE_KEY, handler);
    const evictKeyPrefix = this.reflector.get<string>(CACHE_EVICT_KEY, handler);
    
    // Logic for cache eviction
    if (evictKeyPrefix) {
      const id = request.params.id;
      if (!id) return next.handle();
      
      const cacheKey = `${evictKeyPrefix}:${id}`;
      return next.handle().pipe(
        tap(async () => {
          console.log(`[INTERCEPTOR] Evicting cache for key: ${cacheKey}`);
          await this.cacheManager.del(cacheKey);
        }),
      );
    }

    // Logic for cache-aside (get/set)
    if (cacheKeyPrefix) {
      const id = request.params.id;
      if (!id) return next.handle();

      const cacheKey = `${cacheKeyPrefix}:${id}`;
      const ttl = this.reflector.get<number>(CACHE_TTL_KEY, handler);

      return from(this.cacheManager.get(cacheKey)).pipe(
        switchMap(cachedValue => {
          if (cachedValue) {
            console.log(`[INTERCEPTOR] Cache HIT for key: ${cacheKey}`);
            return of(cachedValue);
          }
          
          console.log(`[INTERCEPTOR] Cache MISS for key: ${cacheKey}`);
          return next.handle().pipe(
            tap(async (response) => {
              console.log(`[INTERCEPTOR] Setting cache for key: ${cacheKey}`);
              await this.cacheManager.set(cacheKey, response, ttl * 1000);
            }),
          );
        }),
      );
    }

    return next.handle();
  }
}

// --- Service Layer (very clean) ---
@Injectable()
export class DecoratedUserService {
  async findOne(id: string): Promise<User> {
    console.log('--- SERVICE: Executing findOne (DB call simulation) ---');
    await new Promise(res => setTimeout(res, 500));
    const user = MOCK_DB.get(id);
    if (!user) throw new NotFoundException();
    return user;
  }

  async delete(id: string): Promise<void> {
    console.log('--- SERVICE: Executing delete (DB call simulation) ---');
    MOCK_DB.delete(id);
  }
}

// --- Controller Layer ---
@Controller('decorated-users')
@UseInterceptors(AopCacheInterceptor) // Apply the interceptor to the whole controller
export class DecoratedUserController {
  constructor(private readonly userService: DecoratedUserService) {}

  @Get(':id')
  @Cacheable('user', 60) // Cache results for 60 seconds
  getUser(@Param('id', ParseUUIDPipe) id: string): Promise<User> {
    return this.userService.findOne(id);
  }

  @Delete(':id')
  @CacheEvict('user') // Evict the 'user' cache for this ID
  deleteUser(@Param('id', ParseUUIDPipe) id: string): Promise<void> {
    return this.userService.delete(id);
  }
}

// --- Module Definition ---
@Module({
  imports: [CacheModule.register({ isGlobal: true })],
  controllers: [DecoratedUserController],
  providers: [DecoratedUserService],
})
export class DecoratedUserModule {}