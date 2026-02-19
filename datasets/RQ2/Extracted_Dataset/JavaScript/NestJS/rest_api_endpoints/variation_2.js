// To make this code runnable, you would need to install the following packages:
// npm install @nestjs/common @nestjs/core @nestjs/mapped-types reflect-metadata rxjs class-validator class-transformer uuid
// A global ValidationPipe should be enabled in main.ts: app.useGlobalPipes(new ValidationPipe());

import {
  Controller,
  Get,
  Post,
  Body,
  Param,
  Delete,
  Patch,
  Query,
  Injectable,
  NotFoundException,
  ConflictException,
  ParseUUIDPipe,
  HttpCode,
  HttpStatus,
} from '@nestjs/common';
import { IsEmail, IsString, IsNotEmpty, IsEnum, IsOptional, IsBoolean, MinLength } from 'class-validator';
import { PartialType } from '@nestjs/mapped-types'; // For DRY DTOs
import { v4 as uuidv4 } from 'uuid';

// --- Domain & Enums ---
enum Role {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

interface UserEntity {
  id: string;
  email: string;
  password_hash: string;
  role: Role;
  is_active: boolean;
  created_at: Date;
}

// --- DTOs ---
class CreateUserDTO {
  @IsEmail()
  email: string;

  @IsString()
  @MinLength(8)
  password_hash: string;

  @IsEnum(Role)
  role: Role;
}

// Use PartialType for concise Update DTO
class UpdateUserDTO extends PartialType(CreateUserDTO) {
  @IsBoolean()
  @IsOptional()
  is_active?: boolean;
}

// --- Service Layer ---
@Injectable()
class UserService {
  private users: UserEntity[] = [
    {
      id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
      email: 'jane.doe@test.com',
      password_hash: 'securehash123',
      role: Role.ADMIN,
      is_active: true,
      created_at: new Date('2023-01-15T10:00:00Z'),
    },
    {
      id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef1',
      email: 'john.smith@test.com',
      password_hash: 'securehash456',
      role: Role.USER,
      is_active: true,
      created_at: new Date('2023-02-20T14:30:00Z'),
    },
  ];

  createUser = async (dto: CreateUserDTO): Promise<UserEntity> => {
    if (this.users.some(u => u.email === dto.email)) {
      throw new ConflictException('Email already in use.');
    }
    const newUser: UserEntity = {
      id: uuidv4(),
      ...dto,
      is_active: true,
      created_at: new Date(),
    };
    this.users.push(newUser);
    return newUser;
  };

  findUserById = async (id: string): Promise<UserEntity> => {
    const user = this.users.find(u => u.id === id);
    if (!user) throw new NotFoundException('User not found.');
    return user;
  };

  findAllUsers = async (query: {
    page?: string;
    limit?: string;
    role?: Role;
    isActive?: string;
  }): Promise<UserEntity[]> => {
    const { page = '1', limit = '10', role, isActive } = query;
    const pageNum = parseInt(page, 10);
    const limitNum = parseInt(limit, 10);

    let results = this.users;

    if (role) {
      results = results.filter(u => u.role === role);
    }
    if (isActive !== undefined) {
      results = results.filter(u => u.is_active === (isActive === 'true'));
    }

    return results.slice((pageNum - 1) * limitNum, pageNum * limitNum);
  };

  updateUser = async (id: string, dto: UpdateUserDTO): Promise<UserEntity> => {
    const userIndex = this.users.findIndex(u => u.id === id);
    if (userIndex === -1) throw new NotFoundException('User not found.');

    this.users[userIndex] = { ...this.users[userIndex], ...dto };
    return this.users[userIndex];
  };

  deleteUser = async (id: string): Promise<{ message: string }> => {
    const initialLength = this.users.length;
    this.users = this.users.filter(u => u.id !== id);
    if (this.users.length === initialLength) {
      throw new NotFoundException('User not found.');
    }
    return { message: 'User deleted successfully.' };
  };
}

// --- Controller Layer ---
@Controller('users')
export class UserController {
  constructor(private readonly userService: UserService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  postUser(@Body() dto: CreateUserDTO) {
    return this.userService.createUser(dto);
  }

  @Get()
  getUsers(@Query() query: { page?: string; limit?: string; role?: Role; isActive?: string }) {
    return this.userService.findAllUsers(query);
  }

  @Get(':id')
  getUser(@Param('id', new ParseUUIDPipe({ version: '4' })) id: string) {
    return this.userService.findUserById(id);
  }

  @Patch(':id')
  patchUser(
    @Param('id', new ParseUUIDPipe({ version: '4' })) id: string,
    @Body() dto: UpdateUserDTO,
  ) {
    return this.userService.updateUser(id, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteUser(@Param('id', new ParseUUIDPipe({ version: '4' })) id: string) {
    return this.userService.deleteUser(id);
  }
}