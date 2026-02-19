// To make this code runnable, you would need to install the following packages:
// npm install @nestjs/common @nestjs/core reflect-metadata rxjs class-validator class-transformer uuid
// A global ValidationPipe should be enabled in main.ts: app.useGlobalPipes(new ValidationPipe({ transform: true }));

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
} from '@nestjs/common';
import { IsEmail, IsString, IsNotEmpty, IsEnum, IsOptional, IsBoolean, MinLength } from 'class-validator';
import { Type } from 'class-transformer';
import { v4 as uuidv4 } from 'uuid';

// --- Domain Model ---

enum UserRoleEnum {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

interface IUser {
  id: string;
  email: string;
  password_hash: string;
  role: UserRoleEnum;
  is_active: boolean;
  created_at: Date;
}

// --- Data Transfer Objects ---

class UserCreationPayload {
  @IsEmail()
  public readonly email: string;

  @IsString()
  @MinLength(8)
  public readonly password_hash: string;

  @IsEnum(UserRoleEnum)
  public readonly role: UserRoleEnum;
}

class UserUpdatePayload {
  @IsEmail()
  @IsOptional()
  public readonly email?: string;

  @IsString()
  @MinLength(8)
  @IsOptional()
  public readonly password_hash?: string;

  @IsEnum(UserRoleEnum)
  @IsOptional()
  public readonly role?: UserRoleEnum;

  @IsBoolean()
  @IsOptional()
  public readonly is_active?: boolean;
}

class UserQueryParameters {
    @IsOptional()
    @Type(() => Number)
    page: number = 1;

    @IsOptional()
    @Type(() => Number)
    limit: number = 10;

    @IsOptional()
    @IsEnum(UserRoleEnum)
    role?: UserRoleEnum;

    @IsOptional()
    @IsBoolean()
    @Type(() => Boolean)
    is_active?: boolean;
}

// --- Persistence Layer (Mock Repository) ---

@Injectable()
class UserRepository {
  private usersStore: Map<string, IUser> = new Map([
    ['0c8f2b2a-9b7a-4b6e-8b0a-9b0c2f2a9b7a', {
      id: '0c8f2b2a-9b7a-4b6e-8b0a-9b0c2f2a9b7a',
      email: 'super.admin@corp.com',
      password_hash: 'super_secret_hash_1',
      role: UserRoleEnum.ADMIN,
      is_active: true,
      created_at: new Date(),
    }],
    ['1d9g3c3b-0c8b-5c7f-9c1b-0c1d3g3b0c8b', {
      id: '1d9g3c3b-0c8b-5c7f-9c1b-0c1d3g3b0c8b',
      email: 'regular.user@corp.com',
      password_hash: 'super_secret_hash_2',
      role: UserRoleEnum.USER,
      is_active: false,
      created_at: new Date(),
    }],
  ]);

  public async findById(id: string): Promise<IUser | null> {
    return this.usersStore.get(id) || null;
  }

  public async findByEmail(email: string): Promise<IUser | null> {
    for (const user of this.usersStore.values()) {
      if (user.email === email) return user;
    }
    return null;
  }

  public async findAll(): Promise<IUser[]> {
    return Array.from(this.usersStore.values());
  }

  public async save(user: IUser): Promise<IUser> {
    this.usersStore.set(user.id, user);
    return user;
  }

  public async deleteById(id: string): Promise<boolean> {
    return this.usersStore.delete(id);
  }
}

// --- Service Layer ---

@Injectable()
class UserManagementService {
  constructor(private readonly userRepository: UserRepository) {}

  public async registerNewUser(payload: UserCreationPayload): Promise<IUser> {
    if (await this.userRepository.findByEmail(payload.email)) {
      throw new ConflictException('An account with this email already exists.');
    }
    const newUser: IUser = {
      id: uuidv4(),
      email: payload.email,
      password_hash: payload.password_hash, // In a real app, hash this
      role: payload.role,
      is_active: true,
      created_at: new Date(),
    };
    return this.userRepository.save(newUser);
  }

  public async retrieveUserById(userIdentifier: string): Promise<IUser> {
    const user = await this.userRepository.findById(userIdentifier);
    if (!user) {
      throw new NotFoundException(`User with identifier ${userIdentifier} could not be found.`);
    }
    return user;
  }

  public async listAllUsers(params: UserQueryParameters) {
    let allUsers = await this.userRepository.findAll();

    if (params.role) {
        allUsers = allUsers.filter(u => u.role === params.role);
    }
    if (params.is_active !== undefined) {
        allUsers = allUsers.filter(u => u.is_active === params.is_active);
    }

    const total = allUsers.length;
    const data = allUsers.slice((params.page - 1) * params.limit, params.page * params.limit);

    return { data, total, page: params.page, limit: params.limit };
  }

  public async modifyUser(userIdentifier: string, payload: UserUpdatePayload): Promise<IUser> {
    const existingUser = await this.retrieveUserById(userIdentifier);
    const updatedUser = { ...existingUser, ...payload };
    return this.userRepository.save(updatedUser);
  }

  public async deregisterUser(userIdentifier: string): Promise<void> {
    const wasDeleted = await this.userRepository.deleteById(userIdentifier);
    if (!wasDeleted) {
      throw new NotFoundException(`User with identifier ${userIdentifier} could not be found.`);
    }
  }
}

// --- Controller (Presentation Layer) ---

@Controller('users')
export class UsersController {
  constructor(private readonly userManagementService: UserManagementService) {}

  @Post()
  public async handleCreateUser(@Body() userPayload: UserCreationPayload) {
    return this.userManagementService.registerNewUser(userPayload);
  }

  @Get()
  public async handleListUsers(@Query() queryParams: UserQueryParameters) {
    return this.userManagementService.listAllUsers(queryParams);
  }

  @Get(':id')
  public async handleGetUserById(@Param('id', ParseUUIDPipe) userIdentifier: string) {
    return this.userManagementService.retrieveUserById(userIdentifier);
  }

  @Put(':id')
  public async handleUpdateUser(
    @Param('id', ParseUUIDPipe) userIdentifier: string,
    @Body() updatePayload: UserUpdatePayload,
  ) {
    return this.userManagementService.modifyUser(userIdentifier, updatePayload);
  }

  @Delete(':id')
  @HttpCode(204)
  public async handleDeleteUser(@Param('id', ParseUUIDPipe) userIdentifier: string) {
    await this.userManagementService.deregisterUser(userIdentifier);
  }
}