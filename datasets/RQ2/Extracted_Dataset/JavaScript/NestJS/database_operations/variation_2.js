// VARIATION 2: The "Concise & Functional" Developer
// This style favors brevity and functional approaches. It uses mapped-types for DTOs,
// EntityManager for some operations, and a more direct configuration style.

// --- DEPENDENCIES (for context, not executable) ---
// @nestjs/common, @nestjs/core, @nestjs/typeorm, @nestjs/mapped-types, typeorm, class-validator, class-transformer, reflect-metadata, sqlite3

import {
  Injectable,
  Module,
  Controller,
  Get,
  Post,
  Body,
  Param,
  Patch,
  Delete,
  Query,
  NotFoundException,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { InjectEntityManager, TypeOrmModule } from '@nestjs/typeorm';
import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  OneToMany,
  ManyToOne,
  ManyToMany,
  JoinTable,
  EntityManager,
  FindOptionsWhere,
} from 'typeorm';
import {
  IsEmail,
  IsEnum,
  IsNotEmpty,
  IsOptional,
  IsString,
  IsUUID,
  IsBoolean,
  MinLength,
} from 'class-validator';
import { PartialType } from '@nestjs/mapped-types';

// --- ENUMS ---
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

// --- ENTITIES ---
@Entity()
class Role {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  name: string;

  @ManyToMany(() => User, user => user.roles)
  users: User[];
}

@Entity()
class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  email: string;

  @Column()
  passwordHash: string;

  @Column({ default: true })
  isActive: boolean;

  @CreateDateColumn()
  createdAt: Date;

  @OneToMany(() => Post, post => post.author)
  posts: Post[];

  @ManyToMany(() => Role, role => role.users, { cascade: true })
  @JoinTable()
  roles: Role[];
}

@Entity()
class Post {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  title: string;

  @Column('text')
  content: string;

  @Column({ type: 'simple-enum', enum: PostStatus, default: PostStatus.DRAFT })
  status: PostStatus;

  @ManyToOne(() => User, user => user.posts, { onDelete: 'CASCADE', nullable: false })
  author: User;
}

// --- DTOS ---
class CreateUserDto {
  @IsEmail()
  email: string;

  @IsString()
  @MinLength(8)
  password: string;

  @IsUUID('4', { each: true })
  @IsOptional()
  roleIds?: string[];
}

class UpdateUserDto extends PartialType(CreateUserDto) {
    @IsBoolean()
    @IsOptional()
    isActive?: boolean;
}

class UserFilterDto {
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

// --- SERVICES ---
@Injectable()
class UserService {
  constructor(@InjectEntityManager() private entityManager: EntityManager) {}

  // Create using EntityManager transaction
  async create(dto: CreateUserDto): Promise<User> {
    return this.entityManager.transaction(async (em) => {
      const existing = await em.findOneBy(User, { email: dto.email });
      if (existing) {
        throw new HttpException('Email in use', HttpStatus.CONFLICT);
      }

      const user = em.create(User, {
        email: dto.email,
        passwordHash: `hashed_${dto.password}`, // Hashing omitted for brevity
        isActive: true,
      });

      if (dto.roleIds?.length) {
        const roles = await em.findByIds(Role, dto.roleIds);
        if (roles.length !== dto.roleIds.length) {
            throw new HttpException('Invalid role ID provided', HttpStatus.BAD_REQUEST);
        }
        user.roles = roles;
      }
      
      return em.save(user);
    });
  }

  // Read with simple filters
  async findAll(filter: UserFilterDto): Promise<User[]> {
    const where: FindOptionsWhere<User> = {};
    if (filter.isActive !== undefined) {
      where.isActive = filter.isActive;
    }
    return this.entityManager.find(User, { where, relations: ['roles'] });
  }

  async findById(id: string): Promise<User> {
    const user = await this.entityManager.findOne(User, {
      where: { id },
      relations: ['posts', 'roles'],
    });
    if (!user) throw new NotFoundException('User not found');
    return user;
  }

  async update(id: string, dto: UpdateUserDto): Promise<User> {
    const user = await this.findById(id);
    
    // Using Object.assign for concise property updates
    Object.assign(user, {
        email: dto.email,
        isActive: dto.isActive,
        passwordHash: dto.password ? `hashed_${dto.password}` : user.passwordHash
    });

    if (dto.roleIds) {
        user.roles = await this.entityManager.findByIds(Role, dto.roleIds);
    }

    return this.entityManager.save(user);
  }

  async remove(id: string): Promise<{ deleted: boolean }> {
    const result = await this.entityManager.delete(User, id);
    if (result.affected === 0) {
      throw new NotFoundException('User not found');
    }
    return { deleted: true };
  }
}

// --- CONTROLLERS ---
@Controller('users')
class UsersController {
  constructor(private readonly userService: UserService) {}

  @Post()
  createUser(@Body() dto: CreateUserDto) {
    return this.userService.create(dto);
  }

  @Get()
  getUsers(@Query() filter: UserFilterDto) {
    return this.userService.findAll(filter);
  }

  @Get(':id')
  getUser(@Param('id') id: string) {
    return this.userService.findById(id);
  }

  @Patch(':id')
  updateUser(@Param('id') id: string, @Body() dto: UpdateUserDto) {
    return this.userService.update(id, dto);
  }

  @Delete(':id')
  deleteUser(@Param('id') id: string) {
    return this.userService.remove(id);
  }
}

// --- MODULES ---
@Module({
  imports: [TypeOrmModule.forFeature([User, Post, Role])],
  controllers: [UsersController],
  providers: [UserService],
})
class UserModule {}

// --- MIGRATION (Example) ---
// Filename: src/db/migrations/1678886400001-CreateTables.ts
class CreateTables1678886400001 implements MigrationInterface {
    public async up(qr: QueryRunner): Promise<void> {
        await qr.query(`
            CREATE TABLE "role" (
                "id" varchar PRIMARY KEY NOT NULL,
                "name" varchar NOT NULL
            );
            CREATE UNIQUE INDEX "IDX_role_name" ON "role" ("name");
        `);
        await qr.query(`
            CREATE TABLE "user" (
                "id" varchar PRIMARY KEY NOT NULL,
                "email" varchar NOT NULL,
                "passwordHash" varchar NOT NULL,
                "isActive" boolean NOT NULL DEFAULT (1),
                "createdAt" datetime NOT NULL DEFAULT (datetime('now'))
            );
            CREATE UNIQUE INDEX "IDX_user_email" ON "user" ("email");
        `);
        // ... other tables (Post, user_roles) would be created similarly
    }

    public async down(qr: QueryRunner): Promise<void> {
        await qr.query(`DROP INDEX "IDX_user_email";`);
        await qr.query(`DROP TABLE "user";`);
        await qr.query(`DROP INDEX "IDX_role_name";`);
        await qr.query(`DROP TABLE "role";`);
    }
}

// --- ROOT MODULE (Example with in-memory DB for compilability) ---
@Module({
  imports: [
    TypeOrmModule.forRoot({
      type: 'sqlite',
      database: ':memory:',
      entities: [User, Post, Role],
      synchronize: true, // OK for in-memory / dev
    }),
    UserModule,
  ],
})
class AppModule {}