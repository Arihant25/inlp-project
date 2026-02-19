// To make this code runnable, you would need to install the following packages:
// npm install @nestjs/common @nestjs/core reflect-metadata rxjs class-validator class-transformer uuid
// And run it within a NestJS application context.
// A global ValidationPipe should be enabled in main.ts: app.useGlobalPipes(new ValidationPipe());

import {
  Controller,
  Get,
  Post,
  Body,
  Param,
  Delete,
  Put,
  Query,
  Injectable,
  NotFoundException,
  ConflictException,
  ParseUUIDPipe,
  DefaultValuePipe,
  ParseIntPipe,
  HttpCode,
  HttpStatus,
} from '@nestjs/common';
import { IsEmail, IsString, IsNotEmpty, IsEnum, IsOptional, IsBoolean, IsUUID, MinLength } from 'class-validator';
import { v4 as uuidv4 } from 'uuid';

// --- DOMAIN ---

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

// --- DTOs (Data Transfer Objects) ---

class CreateUserDto {
  @IsEmail()
  @IsNotEmpty()
  email: string;

  @IsString()
  @MinLength(8)
  @IsNotEmpty()
  password_hash: string;

  @IsEnum(UserRole)
  @IsNotEmpty()
  role: UserRole;
}

class UpdateUserDto {
  @IsEmail()
  @IsOptional()
  email?: string;

  @IsString()
  @MinLength(8)
  @IsOptional()
  password_hash?: string;

  @IsEnum(UserRole)
  @IsOptional()
  role?: UserRole;

  @IsBoolean()
  @IsOptional()
  is_active?: boolean;
}

// --- SERVICE ---

@Injectable()
class UsersService {
  private readonly users: User[] = [
    // Mock initial data
    {
      id: 'f8b4a3a0-4c3d-4e2a-8b1e-9d7f6c5b4a3b',
      email: 'admin@example.com',
      password_hash: 'hashed_password_1',
      role: UserRole.ADMIN,
      is_active: true,
      created_at: new Date(),
    },
    {
      id: 'c2a1b3e4-5d6f-7a8b-9c0d-1e2f3a4b5c6d',
      email: 'user@example.com',
      password_hash: 'hashed_password_2',
      role: UserRole.USER,
      is_active: false,
      created_at: new Date(),
    },
  ];

  async create(createUserDto: CreateUserDto): Promise<User> {
    const existingUser = this.users.find(user => user.email === createUserDto.email);
    if (existingUser) {
      throw new ConflictException('User with this email already exists');
    }
    const newUser: User = {
      id: uuidv4(),
      ...createUserDto,
      is_active: true,
      created_at: new Date(),
    };
    this.users.push(newUser);
    return newUser;
  }

  async findAll(
    page: number,
    limit: number,
    role?: UserRole,
    isActive?: string,
  ): Promise<{ data: User[]; total: number }> {
    let filteredUsers = [...this.users];

    if (role) {
      filteredUsers = filteredUsers.filter(user => user.role === role);
    }

    if (isActive !== undefined) {
      const isActiveBool = isActive === 'true';
      filteredUsers = filteredUsers.filter(user => user.is_active === isActiveBool);
    }

    const total = filteredUsers.length;
    const paginatedUsers = filteredUsers.slice((page - 1) * limit, page * limit);

    return { data: paginatedUsers, total };
  }

  async findOne(id: string): Promise<User> {
    const user = this.users.find(user => user.id === id);
    if (!user) {
      throw new NotFoundException(`User with ID "${id}" not found`);
    }
    return user;
  }

  async update(id: string, updateUserDto: UpdateUserDto): Promise<User> {
    const user = await this.findOne(id);
    const userIndex = this.users.findIndex(u => u.id === id);

    const updatedUser = { ...user, ...updateUserDto };
    this.users[userIndex] = updatedUser;

    return updatedUser;
  }

  async remove(id: string): Promise<void> {
    const userIndex = this.users.findIndex(user => user.id === id);
    if (userIndex === -1) {
      throw new NotFoundException(`User with ID "${id}" not found`);
    }
    this.users.splice(userIndex, 1);
  }
}

// --- CONTROLLER ---

@Controller('users')
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Post()
  async create(@Body() createUserDto: CreateUserDto): Promise<User> {
    return this.usersService.create(createUserDto);
  }

  @Get()
  async findAll(
    @Query('page', new DefaultValuePipe(1), ParseIntPipe) page: number,
    @Query('limit', new DefaultValuePipe(10), ParseIntPipe) limit: number,
    @Query('role') role?: UserRole,
    @Query('isActive') isActive?: string,
  ): Promise<{ data: User[]; total: number }> {
    return this.usersService.findAll(page, limit, role, isActive);
  }

  @Get(':id')
  async findOne(@Param('id', ParseUUIDPipe) id: string): Promise<User> {
    return this.usersService.findOne(id);
  }

  @Put(':id')
  async update(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() updateUserDto: UpdateUserDto,
  ): Promise<User> {
    return this.usersService.update(id, updateUserDto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@Param('id', ParseUUIDPipe) id: string): Promise<void> {
    return this.usersService.remove(id);
  }
}