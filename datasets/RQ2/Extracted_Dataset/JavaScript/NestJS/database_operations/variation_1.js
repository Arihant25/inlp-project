// VARIATION 1: The "By-the-Book" Developer
// This style emphasizes clarity, strict adherence to NestJS/TypeORM conventions,
// and robust separation of concerns. It uses the Data Mapper pattern via InjectRepository.

// --- DEPENDENCIES (for context, not executable) ---
// @nestjs/common, @nestjs/core, @nestjs/typeorm, typeorm, class-validator, class-transformer, reflect-metadata, pg

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
  ConflictException,
  InternalServerErrorException,
  BadRequestException,
  Inject,
} from '@nestjs/common';
import { InjectRepository, TypeOrmModule, getDataSourceToken } from '@nestjs/typeorm';
import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  OneToMany,
  ManyToOne,
  ManyToMany,
  JoinTable,
  Repository,
  DataSource,
  MigrationInterface,
  QueryRunner,
  Index,
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
import { Type } from 'class-transformer';

// --- ENUMS ---
export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER',
}

export enum PostStatus {
  DRAFT = 'DRAFT',
  PUBLISHED = 'PUBLISHED',
}

// --- ENTITIES ---

@Entity('roles')
class Role {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'varchar', length: 50, unique: true })
  name: string;

  @ManyToMany(() => User, (user) => user.roles)
  users: User[];
}

@Entity('users')
@Index(['email'], { unique: true })
class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'varchar', length: 255, unique: true })
  email: string;

  @Column({ name: 'password_hash', type: 'varchar', length: 255 })
  passwordHash: string;

  @Column({ name: 'is_active', default: true })
  isActive: boolean;

  @CreateDateColumn({ name: 'created_at', type: 'timestamp with time zone' })
  createdAt: Date;

  @OneToMany(() => Post, (post) => post.author)
  posts: Post[];

  @ManyToMany(() => Role, (role) => role.users, { cascade: true, eager: true })
  @JoinTable({
    name: 'user_roles',
    joinColumn: { name: 'user_id', referencedColumnName: 'id' },
    inverseJoinColumn: { name: 'role_id', referencedColumnName: 'id' },
  })
  roles: Role[];
}

@Entity('posts')
class Post {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'varchar', length: 255 })
  title: string;

  @Column({ type: 'text' })
  content: string;

  @Column({ type: 'enum', enum: PostStatus, default: PostStatus.DRAFT })
  status: PostStatus;

  @ManyToOne(() => User, (user) => user.posts, { onDelete: 'CASCADE' })
  author: User;
}

// --- DTOS (Data Transfer Objects) ---

class CreateUserDto {
  @IsEmail()
  @IsNotEmpty()
  email: string;

  @IsString()
  @MinLength(8)
  @IsNotEmpty()
  password: string;

  @IsUUID('4', { each: true })
  @IsOptional()
  roleIds?: string[];
}

class UpdateUserDto {
  @IsEmail()
  @IsOptional()
  email?: string;

  @IsBoolean()
  @IsOptional()
  isActive?: boolean;

  @IsUUID('4', { each: true })
  @IsOptional()
  roleIds?: string[];
}

class UserQueryDto {
  @IsOptional()
  @IsBoolean()
  @Type(() => Boolean)
  isActive?: boolean;

  @IsOptional()
  @IsString()
  roleName?: string;
}

class CreatePostDto {
  @IsString()
  @IsNotEmpty()
  title: string;

  @IsString()
  @IsNotEmpty()
  content: string;

  @IsEnum(PostStatus)
  @IsOptional()
  status?: PostStatus;

  @IsUUID()
  @IsNotEmpty()
  authorId: string;
}

// --- SERVICES ---

@Injectable()
class UsersService {
  constructor(
    @InjectRepository(User)
    private readonly userRepository: Repository<User>,
    @InjectRepository(Role)
    private readonly roleRepository: Repository<Role>,
    private readonly dataSource: DataSource,
  ) {}

  // CRUD: Create with Transaction
  async createUser(createUserDto: CreateUserDto): Promise<User> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      const existingUser = await queryRunner.manager.findOneBy(User, { email: createUserDto.email });
      if (existingUser) {
        throw new ConflictException('Email already exists');
      }

      const newUser = new User();
      newUser.email = createUserDto.email;
      // In a real app, hash the password. e.g., bcrypt.hash(createUserDto.password, 10)
      newUser.passwordHash = `hashed_${createUserDto.password}`;
      
      if (createUserDto.roleIds && createUserDto.roleIds.length > 0) {
        const roles = await queryRunner.manager.findByIds(Role, createUserDto.roleIds);
        if (roles.length !== createUserDto.roleIds.length) {
            throw new BadRequestException('One or more role IDs are invalid');
        }
        newUser.roles = roles;
      }

      const savedUser = await queryRunner.manager.save(User, newUser);
      await queryRunner.commitTransaction();
      return savedUser;
    } catch (error) {
      await queryRunner.rollbackTransaction();
      if (error instanceof ConflictException || error instanceof BadRequestException) {
        throw error;
      }
      throw new InternalServerErrorException('Failed to create user');
    } finally {
      await queryRunner.release();
    }
  }

  // CRUD: Read with Query Builder for filtering
  async findUsers(query: UserQueryDto): Promise<User[]> {
    const queryBuilder = this.userRepository.createQueryBuilder('user');

    queryBuilder.leftJoinAndSelect('user.roles', 'role');

    if (query.isActive !== undefined) {
      queryBuilder.andWhere('user.isActive = :isActive', { isActive: query.isActive });
    }

    if (query.roleName) {
      queryBuilder.andWhere('role.name = :roleName', { roleName: query.roleName });
    }

    return await queryBuilder.getMany();
  }

  // CRUD: Read One
  async findOneUser(id: string): Promise<User> {
    const user = await this.userRepository.findOne({
      where: { id },
      relations: ['posts', 'roles'],
    });
    if (!user) {
      throw new NotFoundException(`User with ID "${id}" not found`);
    }
    return user;
  }

  // CRUD: Update
  async updateUser(id: string, updateUserDto: UpdateUserDto): Promise<User> {
    const user = await this.findOneUser(id); // leverages findOne for existence check
    
    if (updateUserDto.email) user.email = updateUserDto.email;
    if (updateUserDto.isActive !== undefined) user.isActive = updateUserDto.isActive;

    if (updateUserDto.roleIds) {
        const roles = await this.roleRepository.findByIds(updateUserDto.roleIds);
        user.roles = roles;
    }

    return this.userRepository.save(user);
  }

  // CRUD: Delete
  async deleteUser(id: string): Promise<void> {
    const result = await this.userRepository.delete(id);
    if (result.affected === 0) {
      throw new NotFoundException(`User with ID "${id}" not found`);
    }
  }
}

// --- CONTROLLERS ---

@Controller('users')
class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Post()
  create(@Body() createUserDto: CreateUserDto) {
    return this.usersService.createUser(createUserDto);
  }

  @Get()
  findAll(@Query() query: UserQueryDto) {
    return this.usersService.findUsers(query);
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.usersService.findOneUser(id);
  }

  @Patch(':id')
  update(@Param('id') id: string, @Body() updateUserDto: UpdateUserDto) {
    return this.usersService.updateUser(id, updateUserDto);
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

// --- DATABASE MIGRATION (Example) ---
// Filename: src/migrations/1678886400000-InitialSchema.ts
class InitialSchema1678886400000 implements MigrationInterface {
    public async up(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`
            CREATE TABLE "roles" (
                "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
                "name" character varying(50) NOT NULL,
                CONSTRAINT "UQ_648e3f5447f725579d7d4ffdfb7" UNIQUE ("name"),
                CONSTRAINT "PK_c1433d71a4838793a49dcad46ab" PRIMARY KEY ("id")
            );
        `);
        await queryRunner.query(`
            CREATE TABLE "users" (
                "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
                "email" character varying(255) NOT NULL,
                "password_hash" character varying(255) NOT NULL,
                "is_active" boolean NOT NULL DEFAULT true,
                "created_at" TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                CONSTRAINT "UQ_97672ac88f789774dd47f7c8be3" UNIQUE ("email"),
                CONSTRAINT "PK_a3ffb1c0c8416b9fc6f907b7433" PRIMARY KEY ("id")
            );
        `);
        await queryRunner.query(`
            CREATE TABLE "posts" (
                "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
                "title" character varying(255) NOT NULL,
                "content" text NOT NULL,
                "status" "public"."posts_status_enum" NOT NULL DEFAULT 'DRAFT',
                "authorId" uuid,
                CONSTRAINT "PK_2829ac61eff60fcec60d7274b9e" PRIMARY KEY ("id"),
                CONSTRAINT "FK_c5a322ad134042970114b719393" FOREIGN KEY ("authorId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE NO ACTION
            );
        `);
        await queryRunner.query(`
            CREATE TABLE "user_roles" (
                "user_id" uuid NOT NULL,
                "role_id" uuid NOT NULL,
                CONSTRAINT "PK_21db842c83a6a0c354965933341" PRIMARY KEY ("user_id", "role_id"),
                CONSTRAINT "FK_ab40a6f0cd74082342339103843" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE,
                CONSTRAINT "FK_8603b8d8c6396034c353798895d" FOREIGN KEY ("role_id") REFERENCES "roles"("id") ON DELETE CASCADE ON UPDATE CASCADE
            );
        `);
    }

    public async down(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`DROP TABLE "user_roles";`);
        await queryRunner.query(`DROP TABLE "posts";`);
        await queryRunner.query(`DROP TABLE "users";`);
        await queryRunner.query(`DROP TABLE "roles";`);
    }
}

// --- TYPEORM CONFIG FOR CLI ---
// Filename: typeorm.config.ts
/*
import { DataSource } from 'typeorm';
import { config } from 'dotenv';

config(); // Load .env file

export default new DataSource({
    type: 'postgres',
    host: process.env.DB_HOST,
    port: parseInt(process.env.DB_PORT, 10),
    username: process.env.DB_USERNAME,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    entities: [__dirname + '/../**//*.entity{.ts,.js}'],
    migrations: [__dirname + '/migrations/*{.ts,.js}'],
    synchronize: false, // Never use TRUE in production
});
*/