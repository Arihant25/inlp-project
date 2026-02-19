// Variation 1: The "Standard" Idiomatic Approach
// This implementation uses the built-in @nestjs/cache-manager for a standard cache-aside pattern.
// It uses an interceptor for simple GET endpoints and manual cache interaction for mutations.
// DEPENDENCIES: @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/cache-manager, cache-manager, reflect-metadata, rxjs, class-validator, class-transformer, uuid

import {
  Injectable,
  Inject,
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Param,
  Body,
  NotFoundException,
  Module,
  UseInterceptors,
  ParseUUIDPipe,
} from '@nestjs/common';
import { CacheModule, CacheInterceptor, CACHE_MANAGER } from '@nestjs/cache-manager';
import { Cache } from 'cache-manager';
import { v4 as uuidv4 } from 'uuid';
import { IsEmail, IsEnum, IsNotEmpty, IsString } from 'class-validator';
import { Type } from 'class-transformer';

// --- Domain Schema ---

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

// --- Mock Database ---

const MOCK_USER_DB = new Map<string, User>();
const initialUser: User = {
  id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
  email: 'test@example.com',
  password_hash: 'hashed_password',
  role: UserRole.ADMIN,
  is_active: true,
  created_at: new Date(),
};
MOCK_USER_DB.set(initialUser.id, initialUser);

// --- Data Transfer Objects ---

class UpdateUserDto {
  @IsEmail()
  email: string;

  @IsEnum(UserRole)
  role: UserRole;
}

// --- Service Layer ---

@Injectable()
class MockUserRepository {
  async findOne(id: string): Promise<User | null> {
    console.log(`[DB] Fetching user ${id}...`);
    await new Promise(resolve => setTimeout(resolve, 500)); // Simulate DB latency
    return MOCK_USER_DB.get(id) || null;
  }

  async update(id: string, data: Partial<User>): Promise<User | null> {
    console.log(`[DB] Updating user ${id}...`);
    const user = MOCK_USER_DB.get(id);
    if (!user) return null;
    const updatedUser = { ...user, ...data };
    MOCK_USER_DB.set(id, updatedUser);
    return updatedUser;
  }

  async delete(id: string): Promise<void> {
    console.log(`[DB] Deleting user ${id}...`);
    MOCK_USER_DB.delete(id);
  }
}

@Injectable()
export class UserService {
  constructor(
    @Inject(CACHE_MANAGER) private cacheManager: Cache,
    private readonly userRepository: MockUserRepository,
  ) {}

  private getUserCacheKey(id: string): string {
    return `user:${id}`;
  }

  // Cache-Aside Pattern: Manual Implementation
  async findOneById(id: string): Promise<User> {
    const cacheKey = this.getUserCacheKey(id);
    
    // 1. Check cache first
    const cachedUser = await this.cacheManager.get<User>(cacheKey);
    if (cachedUser) {
      console.log(`[CACHE] HIT for user ${id}`);
      // The class-transformer might be needed if the cache store serializes objects improperly
      return Type(() => User)(cachedUser);
    }
    console.log(`[CACHE] MISS for user ${id}`);

    // 2. If miss, fetch from DB
    const user = await this.userRepository.findOne(id);
    if (!user) {
      throw new NotFoundException(`User with ID ${id} not found.`);
    }

    // 3. Store in cache with a TTL (Time-To-Live) of 60 seconds
    await this.cacheManager.set(cacheKey, user, 60 * 1000);
    console.log(`[CACHE] SET for user ${id}`);

    return user;
  }

  // Cache Invalidation on Update
  async updateUser(id: string, updateUserDto: UpdateUserDto): Promise<User> {
    const updatedUser = await this.userRepository.update(id, updateUserDto);
    if (!updatedUser) {
      throw new NotFoundException(`User with ID ${id} not found.`);
    }

    // Invalidate cache
    const cacheKey = this.getUserCacheKey(id);
    await this.cacheManager.del(cacheKey);
    console.log(`[CACHE] DELETED/INVALIDATED for user ${id}`);

    return updatedUser;
  }

  // Cache Invalidation on Delete
  async deleteUser(id: string): Promise<void> {
    await this.userRepository.delete(id);
    
    // Invalidate cache
    const cacheKey = this.getUserCacheKey(id);
    await this.cacheManager.del(cacheKey);
    console.log(`[CACHE] DELETED/INVALIDATED for user ${id}`);
  }
}

// --- Controller Layer ---

@Controller('users')
export class UserController {
  constructor(private readonly userService: UserService) {}

  // This endpoint demonstrates the manual cache-aside implementation in the service
  @Get(':id')
  async findOne(@Param('id', ParseUUIDPipe) id: string): Promise<User> {
    return this.userService.findOneById(id);
  }

  // This endpoint demonstrates automatic caching via an interceptor for a simple case
  @UseInterceptors(CacheInterceptor)
  @Get()
  async findAll(): Promise<User[]> {
    console.log('[CONTROLLER] findAll called. If cached, this log and the service method will not run.');
    // In a real app, this would fetch all users from the service.
    return Array.from(MOCK_USER_DB.values());
  }

  @Put(':id')
  async update(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() updateUserDto: UpdateUserDto,
  ): Promise<User> {
    return this.userService.updateUser(id, updateUserDto);
  }

  @Delete(':id')
  async remove(@Param('id', ParseUUIDPipe) id: string): Promise<{ message: string }> {
    await this.userService.deleteUser(id);
    return { message: `User ${id} deleted successfully.` };
  }
}

// --- Module Definition ---

@Module({
  imports: [
    CacheModule.register({
      ttl: 5 * 1000, // default TTL 5 seconds
      max: 100, // max number of items in cache
      isGlobal: true,
    }),
  ],
  controllers: [UserController],
  providers: [UserService, MockUserRepository],
})
export class UserModule {}