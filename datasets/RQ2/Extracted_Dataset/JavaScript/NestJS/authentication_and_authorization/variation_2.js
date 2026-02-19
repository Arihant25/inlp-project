// --- DEPENDENCIES ---
// @nestjs/common, @nestjs/core, @nestjs/config, @nestjs/platform-express, @nestjs/jwt, @nestjs/passport
// passport, passport-jwt, passport-google-oauth20, bcrypt, class-validator, class-transformer
// express-session, reflect-metadata, rxjs

// --- FILE: src/core/constants.ts ---
export const JWT_SECRET_KEY = 'jwt.secret';
export const GOOGLE_CLIENT_ID_KEY = 'google.clientId';
export const GOOGLE_CLIENT_SECRET_KEY = 'google.clientSecret';
export const ROLE_METADATA_KEY = 'role_key';

// --- FILE: src/domain/user.types.ts ---
export enum Role {
  Standard = 'USER',
  Administrator = 'ADMIN',
}

export interface UserEntity {
  id: string;
  email: string;
  passwordHash: string;
  role: Role;
  isActive: boolean;
  createdAt: Date;
}

// --- FILE: src/data/user.repository.mock.ts ---
import { Injectable } from '@nestjs/common';
import { UserEntity, Role } from '../domain/user.types';
import * as bcrypt from 'bcrypt';
import { v4 as uuid } from 'uuid';

@Injectable()
export class UserRepository {
  private readonly usersDb: Map<string, UserEntity> = new Map();

  constructor() {
    this.seed();
  }

  private async seed() {
    const user: UserEntity = {
      id: uuid(),
      email: 'user@test.com',
      passwordHash: await bcrypt.hash('userpass', 10),
      role: Role.Standard,
      isActive: true,
      createdAt: new Date(),
    };
    const admin: UserEntity = {
      id: uuid(),
      email: 'admin@test.com',
      passwordHash: await bcrypt.hash('adminpass', 10),
      role: Role.Administrator,
      isActive: true,
      createdAt: new Date(),
    };
    this.usersDb.set(user.email, user);
    this.usersDb.set(admin.email, admin);
  }

  findByEmail = async (email: string): Promise<UserEntity | null> => {
    return this.usersDb.get(email) || null;
  };

  findOrCreate = async (profile: { email: string }): Promise<UserEntity> => {
    const existing = await this.findByEmail(profile.email);
    if (existing) return existing;

    const newUser: UserEntity = {
      id: uuid(),
      email: profile.email,
      passwordHash: null,
      role: Role.Standard,
      isActive: true,
      createdAt: new Date(),
    };
    this.usersDb.set(newUser.email, newUser);
    return newUser;
  };
}

// --- FILE: src/data/data.module.ts ---
import { Module } from '@nestjs/common';
import { UserRepository } from './user.repository.mock';

@Module({
  providers: [UserRepository],
  exports: [UserRepository],
})
export class DataModule {}

// --- FILE: src/auth/decorators/require-role.decorator.ts ---
import { SetMetadata } from '@nestjs/common';
import { Role } from '../../domain/user.types';
import { ROLE_METADATA_KEY } from '../../core/constants';

export const RequireRole = (role: Role) => SetMetadata(ROLE_METADATA_KEY, role);

// --- FILE: src/auth/guards/role.guard.ts ---
import { Injectable, CanActivate, ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Role } from '../../domain/user.types';
import { ROLE_METADATA_KEY } from '../../core/constants';

@Injectable()
export class RoleGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const requiredRole = this.reflector.get<Role>(ROLE_METADATA_KEY, context.getHandler());
    if (!requiredRole) {
      return true; // No role specified, access granted
    }
    const request = context.switchToHttp().getRequest();
    const user = request.user;
    return user?.role === requiredRole;
  }
}

// --- FILE: src/auth/strategies/jwt.strategy.ts ---
import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PassportStrategy } from '@nestjs/passport';
import { ExtractJwt, Strategy } from 'passport-jwt';
import { JWT_SECRET_KEY } from '../../core/constants';

@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy, 'jwt') {
  constructor(configSvc: ConfigService) {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      secretOrKey: configSvc.get<string>(JWT_SECRET_KEY),
    });
  }

  // Passport automatically builds a user object based on the return value
  validate = async (payload: { sub: string; email: string; role: Role }) => ({
    id: payload.sub,
    email: payload.email,
    role: payload.role,
  });
}

// --- FILE: src/auth/strategies/google.strategy.ts ---
import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PassportStrategy } from '@nestjs/passport';
import { Strategy, Profile } from 'passport-google-oauth20';
import { UserRepository } from '../../data/user.repository.mock';
import { GOOGLE_CLIENT_ID_KEY, GOOGLE_CLIENT_SECRET_KEY } from '../../core/constants';

@Injectable()
export class GoogleStrategy extends PassportStrategy(Strategy, 'google') {
  constructor(
    private readonly userRepo: UserRepository,
    configSvc: ConfigService,
  ) {
    super({
      clientID: configSvc.get<string>(GOOGLE_CLIENT_ID_KEY),
      clientSecret: configSvc.get<string>(GOOGLE_CLIENT_SECRET_KEY),
      callbackURL: '/auth/google/redirect',
      scope: ['email'],
    });
  }

  validate = async (_accessToken: string, _refreshToken: string, profile: Profile) => {
    return this.userRepo.findOrCreate({ email: profile.emails[0].value });
  };
}

// --- FILE: src/auth/auth.service.ts ---
import { Injectable } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { UserRepository } from '../data/user.repository.mock';
import * as bcrypt from 'bcrypt';
import { UserEntity } from '../domain/user.types';

@Injectable()
export class AuthService {
  constructor(
    private readonly userRepo: UserRepository,
    private readonly jwtSvc: JwtService,
  ) {}

  async attemptLogin(email: string, pass: string): Promise<UserEntity | null> {
    const user = await this.userRepo.findByEmail(email);
    if (!user || !user.isActive || !user.passwordHash) return null;

    const isMatch = await bcrypt.compare(pass, user.passwordHash);
    return isMatch ? user : null;
  }

  generateJwt(user: UserEntity): { accessToken: string } {
    const payload = { sub: user.id, email: user.email, role: user.role };
    return { accessToken: this.jwtSvc.sign(payload) };
  }
}

// --- FILE: src/auth/auth.controller.ts ---
import { Controller, Post, Body, UseGuards, Get, Req, UnauthorizedException, HttpCode, HttpStatus } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { AuthService } from './auth.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly authSvc: AuthService) {}

  @Post('token')
  @HttpCode(HttpStatus.OK)
  async getToken(@Body() { email, password }: { email: string; password; string }) {
    const user = await this.authSvc.attemptLogin(email, password);
    if (!user) throw new UnauthorizedException();
    return this.authSvc.generateJwt(user);
  }

  @Get('google')
  @UseGuards(AuthGuard('google'))
  googleLogin() {
    // Redirects to Google
  }

  @Get('google/redirect')
  @UseGuards(AuthGuard('google'))
  googleRedirect(@Req() req) {
    // req.user is available here from GoogleStrategy
    // In a real app, you'd set a session or issue a JWT
    return {
      message: 'OAuth successful',
      user: req.user,
    };
  }
}

// --- FILE: src/auth/auth.module.ts ---
import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { DataModule } from '../data/data.module';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { GoogleStrategy } from './strategies/google.strategy';
import { JwtStrategy } from './strategies/jwt.strategy';
import { JWT_SECRET_KEY } from '../core/constants';

@Module({
  imports: [
    DataModule,
    PassportModule,
    ConfigModule.forRoot({
      // Mock config values
      load: [() => ({
        [JWT_SECRET_KEY]: 'a-very-concise-secret-key',
        [GOOGLE_CLIENT_ID_KEY]: 'CONCISE_GOOGLE_ID',
        [GOOGLE_CLIENT_SECRET_KEY]: 'CONCISE_GOOGLE_SECRET',
      })],
    }),
    JwtModule.registerAsync({
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: (configSvc: ConfigService) => ({
        secret: configSvc.get<string>(JWT_SECRET_KEY),
        signOptions: { expiresIn: '1h' },
      }),
    }),
  ],
  controllers: [AuthController],
  providers: [AuthService, JwtStrategy, GoogleStrategy],
})
export class AuthModule {}

// --- FILE: src/posts/posts.controller.ts ---
import { Controller, Get, UseGuards, Body, Post as HttpPost, Req } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { RequireRole } from '../auth/decorators/require-role.decorator';
import { RoleGuard } from '../auth/guards/role.guard';
import { Role } from '../domain/user.types';

@Controller('posts')
@UseGuards(AuthGuard('jwt')) // Apply JWT guard to the whole controller
export class PostsController {
  @Get('profile')
  getProfile(@Req() { user }) {
    return user;
  }

  @RequireRole(Role.Administrator)
  @UseGuards(RoleGuard)
  @HttpPost('publish')
  publishPost(@Body() { title }: { title: string }) {
    return { status: 'published', title };
  }
}

// --- FILE: src/app.module.ts ---
import { Module } from '@nestjs/common';
import { AuthModule } from './auth/auth.module';
import { PostsController } from './posts/posts.controller';

@Module({
  imports: [AuthModule],
  controllers: [PostsController],
})
export class AppModule {}

// --- FILE: src/main.ts ---
/*
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import * as session from 'express-session';
import * as passport from 'passport';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  
  // Session setup for OAuth
  app.use(session({ secret: 'concise-session', resave: false, saveUninitialized: false }));
  app.use(passport.initialize());
  app.use(passport.session());

  await app.listen(3000);
}
bootstrap();
*/