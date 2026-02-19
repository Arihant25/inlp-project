// To make this code runnable, you would need to install the following packages:
// npm install @nestjs/common @nestjs/core @nestjs/swagger @nestjs/mapped-types reflect-metadata rxjs class-validator class-transformer uuid
// A global ValidationPipe should be enabled in main.ts: app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
// Swagger setup should be done in main.ts as well.

import {
  Controller,
  Get,
  Post,
  Body,
  Param,
  Delete,
  Patch,
  Put,
  Query,
  Injectable,
  NotFoundException,
  ConflictException,
  ParseUUIDPipe,
  HttpCode,
  HttpStatus,
} from '@nestjs/common';
import { ApiTags, ApiOperation, ApiResponse, ApiQuery, ApiParam, ApiBody } from '@nestjs/swagger';
import { IsEmail, IsString, IsNotEmpty, IsEnum, IsOptional, IsBoolean, MinLength, Min, Max } from 'class-validator';
import { Type } from 'class-transformer';
import { PartialType } from '@nestjs/mapped-types';
import { v4 as uuidv4 } from 'uuid';

// --- Domain ---
enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- DTOs with Swagger Documentation ---
class CreateUserRequestDto {
  @ApiProperty({ example: 'test@user.com', description: 'User email address' })
  @IsEmail()
  email: string;

  @ApiProperty({ example: 'strongpassword123', description: 'User password' })
  @IsString()
  @MinLength(8)
  password_hash: string;

  @ApiProperty({ enum: UserRole, default: UserRole.USER, description: 'User role' })
  @IsEnum(UserRole)
  role: UserRole;
}

class UpdateUserRequestDto extends PartialType(CreateUserRequestDto) {
  @ApiProperty({ example: true, description: 'Set user account to active or inactive' })
  @IsBoolean()
  @IsOptional()
  is_active?: boolean;
}

class UserResponseDto {
  @ApiProperty()
  id: string;
  @ApiProperty()
  email: string;
  @ApiProperty()
  role: UserRole;
  @ApiProperty()
  is_active: boolean;
  @ApiProperty()
  created_at: Date;

  constructor(partial: Partial<UserResponseDto>) {
    Object.assign(this, partial);
  }
}

class UserPaginationQueryDto {
  @ApiProperty({ required: false, default: 1, description: 'Page number' })
  @IsOptional()
  @Type(() => Number)
  @Min(1)
  page: number = 1;

  @ApiProperty({ required: false, default: 10, description: 'Items per page' })
  @IsOptional()
  @Type(() => Number)
  @Min(1)
  @Max(100)
  limit: number = 10;

  @ApiProperty({ required: false, enum: UserRole, description: 'Filter by user role' })
  @IsOptional()
  @IsEnum(UserRole)
  role?: UserRole;

  @ApiProperty({ required: false, type: Boolean, description: 'Filter by active status' })
  @IsOptional()
  @Type(() => String) // Query params are strings, transform handles 'true'/'false'
  is_active?: string;
}

// --- Service ---
@Injectable()
class UsersService {
  private users: User[] = [
    { id: uuidv4(), email: 'api.admin@service.com', password_hash: 'hash1', role: UserRole.ADMIN, is_active: true, created_at: new Date() },
    { id: uuidv4(), email: 'api.user@service.com', password_hash: 'hash2', role: UserRole.USER, is_active: true, created_at: new Date() },
    { id: uuidv4(), email: 'inactive.user@service.com', password_hash: 'hash3', role: UserRole.USER, is_active: false, created_at: new Date() },
  ];

  async create(dto: CreateUserRequestDto): Promise<User> {
    if (this.users.find(u => u.email === dto.email)) {
      throw new ConflictException('Email already registered');
    }
    const user: User = { id: uuidv4(), ...dto, is_active: true, created_at: new Date() };
    this.users.push(user);
    return user;
  }

  async findOne(id: string): Promise<User> {
    const user = this.users.find(u => u.id === id);
    if (!user) throw new NotFoundException(`User with ID ${id} not found`);
    return user;
  }

  async findAll(query: UserPaginationQueryDto): Promise<{ results: User[], total: number }> {
    let results = this.users;
    if (query.role) {
      results = results.filter(u => u.role === query.role);
    }
    if (query.is_active !== undefined) {
      const isActive = query.is_active === 'true';
      results = results.filter(u => u.is_active === isActive);
    }
    const total = results.length;
    const paginated = results.slice((query.page - 1) * query.limit, query.page * query.limit);
    return { results: paginated, total };
  }

  async update(id: string, dto: UpdateUserRequestDto): Promise<User> {
    const user = await this.findOne(id);
    Object.assign(user, dto);
    return user;
  }

  async delete(id: string): Promise<void> {
    const index = this.users.findIndex(u => u.id === id);
    if (index === -1) throw new NotFoundException(`User with ID ${id} not found`);
    this.users.splice(index, 1);
  }
}

// --- Controller with Swagger ---
@ApiTags('Users')
@Controller('users')
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Post()
  @ApiOperation({ summary: 'Create a new user' })
  @ApiResponse({ status: 201, description: 'The user has been successfully created.', type: UserResponseDto })
  @ApiResponse({ status: 409, description: 'Conflict. Email already exists.' })
  async createUser(@Body() createUserDto: CreateUserRequestDto): Promise<UserResponseDto> {
    const user = await this.usersService.create(createUserDto);
    const { password_hash, ...result } = user; // Exclude password from response
    return new UserResponseDto(result);
  }

  @Get()
  @ApiOperation({ summary: 'List all users with pagination and filtering' })
  @ApiResponse({ status: 200, description: 'List of users.', type: [UserResponseDto] })
  async listUsers(@Query() query: UserPaginationQueryDto) {
    const { results, total } = await this.usersService.findAll(query);
    const data = results.map(user => {
      const { password_hash, ...result } = user;
      return new UserResponseDto(result);
    });
    return { data, total, page: query.page, limit: query.limit };
  }

  @Get(':id')
  @ApiOperation({ summary: 'Get a user by ID' })
  @ApiParam({ name: 'id', type: 'string', format: 'uuid' })
  @ApiResponse({ status: 200, description: 'User details.', type: UserResponseDto })
  @ApiResponse({ status: 404, description: 'User not found.' })
  async getUserById(@Param('id', ParseUUIDPipe) id: string): Promise<UserResponseDto> {
    const user = await this.usersService.findOne(id);
    const { password_hash, ...result } = user;
    return new UserResponseDto(result);
  }

  @Put(':id')
  @Patch(':id') // Handle both PUT and PATCH
  @ApiOperation({ summary: 'Update a user by ID' })
  @ApiParam({ name: 'id', type: 'string', format: 'uuid' })
  @ApiBody({ type: UpdateUserRequestDto })
  @ApiResponse({ status: 200, description: 'User updated successfully.', type: UserResponseDto })
  @ApiResponse({ status: 404, description: 'User not found.' })
  async updateUser(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() updateUserDto: UpdateUserRequestDto,
  ): Promise<UserResponseDto> {
    const user = await this.usersService.update(id, updateUserDto);
    const { password_hash, ...result } = user;
    return new UserResponseDto(result);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  @ApiOperation({ summary: 'Delete a user by ID' })
  @ApiParam({ name: 'id', type: 'string', format: 'uuid' })
  @ApiResponse({ status: 204, description: 'User deleted successfully.' })
  @ApiResponse({ status: 404, description: 'User not found.' })
  async deleteUser(@Param('id', ParseUUIDPipe) id: string): Promise<void> {
    await this.usersService.delete(id);
  }
}