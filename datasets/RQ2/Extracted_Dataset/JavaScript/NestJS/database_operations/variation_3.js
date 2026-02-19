// VARIATION 3: The "Service-Oriented/Domain-Driven" Developer
// This style focuses on rich business logic within services and repositories.
// It introduces custom repositories to encapsulate query logic and custom exceptions
// for domain-specific errors.

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
  Inject,
  HttpStatus,
  HttpException,
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
  Repository,
  DataSource,
  MigrationInterface,
  QueryRunner,
  EntityRepository,
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

// --- DOMAIN EXCEPTIONS ---
class UserNotFoundException extends NotFoundException {
  constructor(userId: string) {
    super(`User with id ${userId} was not found.`);
  }
}
class EmailInUseException extends ConflictException {
  constructor(email: string) {
    super(`The email ${email} is already registered.`);
  }
}

// --- ENUMS ---
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

// --- ENTITIES ---
@Entity({ name: 'roles' })
class RoleEntity {
  @PrimaryGeneratedColumn('uuid') id: string;
  @Column({ unique: true }) name: string;
  @ManyToMany(() => UserEntity, user => user.roles) users: UserEntity[];
}

@Entity({ name: 'users' })
class UserEntity {
  @PrimaryGeneratedColumn('uuid') id: string;
  @Column({ unique: true }) email: string;
  @Column() passwordHash: string;
  @Column({ default: true }) isActive: boolean;
  @CreateDateColumn() createdAt: Date;
  @OneToMany(() => PostEntity, post => post.author) posts: PostEntity[];
  @ManyToMany(() => RoleEntity, role => role.users, { cascade: true })
  @JoinTable({ name: 'user_roles_join_table' }) roles: RoleEntity[];
}

@Entity({ name: 'posts' })
class PostEntity {
  @PrimaryGeneratedColumn('uuid') id: string;
  @Column() title: string;
  @Column('text') content: string;
  @Column({ type: 'enum', enum: PostStatus, default: PostStatus.DRAFT }) status: PostStatus;
  @ManyToOne(() => UserEntity, user => user.posts, { onDelete: 'CASCADE' }) author: UserEntity;
}

// --- DTOS ---
class RegisterUserPayload {
  @IsEmail() email: string;
  @IsString() @MinLength(8) password: string;
  @IsUUID('4', { each: true }) @IsOptional() defaultRoleIds?: string[];
}
class UpdateUserStatusPayload {
  @IsBoolean() isActive: boolean;
}
class UserFilterParams {
  @IsOptional() @IsString() emailContains?: string;
  @IsOptional() @IsString() role?: string;
}

// --- CUSTOM REPOSITORY ---
@Injectable()
class UserRepository extends Repository<UserEntity> {
  constructor(private dataSource: DataSource) {
    super(UserEntity, dataSource.createEntityManager());
  }

  public async findByIdOrFail(id: string): Promise<UserEntity> {
    const user = await this.findOne({ where: { id }, relations: ['roles', 'posts'] });
    if (!user) {
      throw new UserNotFoundException(id);
    }
    return user;
  }

  public async findByEmail(email: string): Promise<UserEntity | null> {
    return this.findOneBy({ email });
  }

  public async findWithFilters(filters: UserFilterParams): Promise<UserEntity[]> {
    const qb = this.createQueryBuilder('user').leftJoinAndSelect('user.roles', 'role');
    if (filters.emailContains) {
      qb.andWhere('user.email LIKE :email', { email: `%${filters.emailContains}%` });
    }
    if (filters.role) {
      qb.andWhere('role.name = :roleName', { roleName: filters.role });
    }
    return qb.getMany();
  }
}

// --- DOMAIN SERVICES ---
@Injectable()
class UserRegistrationService {
  constructor(
    private readonly userRepository: UserRepository,
    private readonly dataSource: DataSource,
  ) {}

  // Transactional business operation
  async registerNewUser(payload: RegisterUserPayload): Promise<UserEntity> {
    return this.dataSource.transaction(async (transactionalEntityManager) => {
      const userRepo = transactionalEntityManager.withRepository(this.userRepository);
      const roleRepo = transactionalEntityManager.getRepository(RoleEntity);

      if (await userRepo.findByEmail(payload.email)) {
        throw new EmailInUseException(payload.email);
      }

      const user = userRepo.create({
        email: payload.email,
        passwordHash: `hashed_${payload.password}`, // Hashing logic here
      });

      if (payload.defaultRoleIds?.length) {
        const roles = await roleRepo.findByIds(payload.defaultRoleIds);
        user.roles = roles;
      }

      return userRepo.save(user);
    });
  }

  async deactivateUser(userId: string): Promise<UserEntity> {
    const user = await this.userRepository.findByIdOrFail(userId);
    user.isActive = false;
    return this.userRepository.save(user);
  }
}

// --- CONTROLLERS (Application Layer) ---
@Controller('users')
class UsersController {
  constructor(
    private readonly registrationService: UserRegistrationService,
    private readonly userRepository: UserRepository,
  ) {}

  @Post('register')
  async register(@Body() payload: RegisterUserPayload) {
    // Exclude password hash from response
    const { passwordHash, ...user } = await this.registrationService.registerNewUser(payload);
    return user;
  }

  @Get(':id')
  async findOne(@Param('id') id: string) {
    const { passwordHash, ...user } = await this.userRepository.findByIdOrFail(id);
    return user;
  }

  @Get()
  async findWithFilters(@Query() filters: UserFilterParams) {
    return this.userRepository.findWithFilters(filters);
  }

  @Patch(':id/deactivate')
  async deactivate(@Param('id') id: string) {
    const { passwordHash, ...user } = await this.registrationService.deactivateUser(id);
    return user;
  }
}

// --- MODULES ---
@Module({
  imports: [TypeOrmModule.forFeature([UserEntity, PostEntity, RoleEntity])],
  controllers: [UsersController],
  providers: [UserRegistrationService, UserRepository],
})
class UsersDomainModule {}

// --- MIGRATION (Example) ---
// Filename: src/migrations/1678886400002-DomainSchema.ts
class DomainSchema1678886400002 implements MigrationInterface {
    public async up(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`
            CREATE TABLE "roles" (
                "id" uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
                "name" varchar UNIQUE NOT NULL
            );
        `);
        await queryRunner.query(`
            CREATE TABLE "users" (
                "id" uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
                "email" varchar UNIQUE NOT NULL,
                "passwordHash" varchar NOT NULL,
                "isActive" boolean NOT NULL DEFAULT true,
                "createdAt" timestamptz NOT NULL DEFAULT now()
            );
        `);
        // ... other tables
    }
    public async down(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`DROP TABLE "users";`);
        await queryRunner.query(`DROP TABLE "roles";`);
    }
}