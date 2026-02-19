// VARIATION 4: The "Active Record" Enthusiast
// This style leverages TypeORM's Active Record pattern, where entities extend BaseEntity
// and contain their own persistence methods (e.g., find, save, remove). The service layer
// becomes thinner, orchestrating calls to these entity methods.

// --- DEPENDENCIES (for context, not executable) ---
// @nestjs/common, @nestjs/core, @nestjs/typeorm, typeorm, class-validator, class-transformer, reflect-metadata, sqlite3

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
  BadRequestException,
} from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  OneToMany,
  ManyToOne,
  ManyToMany,
  JoinTable,
  BaseEntity,
  DataSource,
  FindManyOptions,
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

// --- ENUMS ---
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

// --- ACTIVE RECORD ENTITIES ---
@Entity('roles')
class Role extends BaseEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  name: string;

  @ManyToMany(() => User, user => user.roles)
  users: User[];
}

@Entity('users')
class User extends BaseEntity {
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

  @ManyToMany(() => Role, role => role.users)
  @JoinTable({ name: 'user_roles' })
  roles: Role[];

  static findByEmail(email: string) {
    return this.findOneBy({ email });
  }

  static async findByIdWithRelations(id: string) {
    const user = await this.findOne({ where: { id }, relations: ['posts', 'roles'] });
    if (!user) {
      throw new NotFoundException(`User with ID ${id} not found.`);
    }
    return user;
  }
}

@Entity('posts')
class Post extends BaseEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  title: string;

  @Column('text')
  content: string;

  @Column({ type: 'simple-enum', enum: PostStatus, default: PostStatus.DRAFT })
  status: PostStatus;

  @ManyToOne(() => User, user => user.posts, { onDelete: 'CASCADE' })
  author: User;
}

// --- DTOS ---
class CreateUserRequest {
  @IsEmail() email: string;
  @IsString() @MinLength(8) password: string;
  @IsUUID('4', { each: true }) @IsOptional() roleIds?: string[];
}
class UpdateUserRequest {
  @IsEmail() @IsOptional() email?: string;
  @IsBoolean() @IsOptional() isActive?: boolean;
}
class UserQueryParams {
  @IsBoolean() @IsOptional() active?: boolean;
}

// --- SERVICES ---
@Injectable()
class UsersService {
  constructor(private dataSource: DataSource) {}

  // Create with transaction using Active Record
  async createUser(data: CreateUserRequest): Promise<User> {
    const { email, password, roleIds } = data;

    if (await User.findByEmail(email)) {
      throw new BadRequestException('Email is already taken.');
    }

    // Active Record methods can accept an EntityManager to participate in a transaction
    return this.dataSource.transaction(async (transactionalEntityManager) => {
      const user = new User();
      user.email = email;
      user.passwordHash = `hashed_${password}`; // Hashing logic here

      if (roleIds?.length) {
        const roles = await transactionalEntityManager.findByIds(Role, roleIds);
        user.roles = roles;
      }
      
      // The .save() method is part of BaseEntity
      await user.save({ data: { entityManager: transactionalEntityManager } });
      return user;
    });
  }

  async findUsers(params: UserQueryParams): Promise<User[]> {
    const options: FindManyOptions<User> = { relations: ['roles'] };
    if (params.active !== undefined) {
      options.where = { isActive: params.active };
    }
    return User.find(options);
  }

  async findOne(id: string): Promise<User> {
    return User.findByIdWithRelations(id);
  }

  async updateUser(id: string, data: UpdateUserRequest): Promise<User> {
    const user = await User.findByIdWithRelations(id);
    
    // Update properties and save
    user.email = data.email ?? user.email;
    user.isActive = data.isActive ?? user.isActive;
    
    await user.save();
    return user;
  }

  async deleteUser(id: string): Promise<void> {
    const user = await this.findOne(id);
    await user.remove();
  }
}

// --- CONTROLLERS ---
@Controller('users')
class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Post()
  create(@Body() body: CreateUserRequest) {
    return this.usersService.createUser(body);
  }

  @Get()
  findAll(@Query() query: UserQueryParams) {
    return this.usersService.findUsers(query);
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.usersService.findOne(id);
  }

  @Patch(':id')
  update(@Param('id') id: string, @Body() body: UpdateUserRequest) {
    return this.usersService.updateUser(id, body);
  }

  @Delete(':id')
  remove(@Param('id') id: string) {
    return this.usersService.deleteUser(id);
  }
}

// --- MODULES ---
@Module({
  imports: [TypeOrmModule.forFeature([User, Post, Role])],
  controllers: [UsersController],
  providers: [UsersService],
})
class UsersModule {}

// --- MIGRATION (Example) ---
// Filename: src/migrations/1678886400003-ActiveRecordSchema.ts
class ActiveRecordSchema1678886400003 implements MigrationInterface {
    public async up(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`
            CREATE TABLE "roles" (
                "id" varchar PRIMARY KEY NOT NULL,
                "name" varchar NOT NULL
            );
            CREATE UNIQUE INDEX "IDX_roles_name" ON "roles" ("name");
        `);
        await queryRunner.query(`
            CREATE TABLE "users" (
                "id" varchar PRIMARY KEY NOT NULL,
                "email" varchar NOT NULL,
                "passwordHash" varchar NOT NULL,
                "isActive" boolean NOT NULL DEFAULT (1),
                "createdAt" datetime NOT NULL DEFAULT (datetime('now'))
            );
            CREATE UNIQUE INDEX "IDX_users_email" ON "users" ("email");
        `);
        // ... other tables (posts, user_roles)
    }

    public async down(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`DROP TABLE "users";`);
        await queryRunner.query(`DROP TABLE "roles";`);
    }
}