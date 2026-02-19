// --- DEPENDENCIES ---
// @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/jwt, @nestjs/passport
// passport, passport-jwt, passport-google-oauth20, bcrypt, class-validator, class-transformer
// express-session, reflect-metadata, rxjs

// --- FILE: src/domain/model.ts ---
export enum Role {
  USER = 'USER',
  ADMIN = 'ADMIN',
}

export interface UserPrincipal {
  id: string;
  email: string;
  role: Role;
}

// --- FILE: src/common/decorators/auth.decorator.ts ---
import { applyDecorators, UseGuards, SetMetadata } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { Role } from '../../domain/model';
import { RolesGuard } from '../guards/roles.guard';

export const ROLES_METADATA_KEY = 'auth_roles';

export function Authenticated(...roles: Role[]) {
  const guards = [AuthGuard('jwt')];
  const decorators = [];

  if (roles.length > 0) {
    guards.push(RolesGuard);
    decorators.push(SetMetadata(ROLES_METADATA_KEY, roles));
  }

  return applyDecorators(
    ...decorators,
    UseGuards(...guards),
  );
}

// --- FILE: src/common/guards/roles.guard.ts ---
import { Injectable, CanActivate, ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Role } from '../../domain/model';
import { ROLES_METADATA_KEY } from '../decorators/auth.decorator';

@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const requiredRoles = this.reflector.get<Role[]>(ROLES_METADATA_KEY, context.getHandler());
    if (!requiredRoles) {
      return true;
    }
    const { user } = context.switchToHttp().getRequest();
    return requiredRoles.some(role => user.role === role);
  }
}

// --- FILE: src/modules/user/user.service.mock.ts ---
import { Injectable } from '@nestjs/common';
import * as bcrypt from 'bcrypt';
import { v4 as uuid } from 'uuid';
import { Role } from '../../domain/model';

@Injectable()
export class UserService {
  private readonly users = new Map();

  constructor() {
    this.seed();
  }

  private async seed() {
    const users = [
      { id: uuid(), email: 'user@system.io', password: 'user-pass', role: Role.USER },
      { id: uuid(), email: 'admin@system.io', password: 'admin-pass', role: Role.ADMIN },
    ];
    for (const u of users) {
      const password_hash = await bcrypt.hash(u.password, 12);
      this.users.set(u.email, { ...u, password_hash });
    }
  }

  async findByEmail(email: string): Promise<any | undefined> {
    return this.users.get(email);
  }

  async findOrCreate(email: string): Promise<any> {
    if (this.users.has(email)) {
      return this.users.get(email);
    }
    const newUser = {
      id: uuid(),
      email,
      password_hash: null,
      role: Role.USER,
    };
    this.users.set(email, newUser);
    return newUser;
  }
}

// --- FILE: src/modules/user/user.module.ts ---
import { Module } from '@nestjs/common';
import { UserService } from './user.service.mock';

@Module({
  providers: [UserService],
  exports: [UserService],
})
export class UserModule {}

// --- FILE: src/modules/auth/auth.service.ts ---
import { Injectable, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { UserService } from '../user/user.service.mock';
import * as bcrypt from 'bcrypt';
import { UserPrincipal } from '../../domain/model';

@Injectable()
export class AuthService {
  constructor(
    private readonly userService: UserService,
    private readonly jwtService: JwtService,
  ) {}

  async validateCredentials(email: string, pass: string): Promise<UserPrincipal> {
    const user = await this.userService.findByEmail(email);
    if (!user || !user.password_hash) {
      throw new UnauthorizedException('Invalid credentials');
    }

    const passwordMatches = await bcrypt.compare(pass, user.password_hash);
    if (!passwordMatches) {
      throw new UnauthorizedException('Invalid credentials');
    }

    return { id: user.id, email: user.email, role: user.role };
  }

  async processOAuthUser(email: string): Promise<UserPrincipal> {
    const user = await this.userService.findOrCreate(email);
    return { id: user.id, email: user.email, role: user.role };
  }

  createToken(principal: UserPrincipal): { accessToken: string } {
    const payload = { sub: principal.id, email: principal.email, role: principal.role };
    return {
      accessToken: this.jwtService.sign(payload),
    };
  }
}

// --- FILE: src/modules/auth/strategies/jwt.strategy.ts ---
import { Injectable } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { ExtractJwt, Strategy } from 'passport-jwt';
import { UserPrincipal } from '../../../domain/model';

@Injectable()
export class JwtAuthStrategy extends PassportStrategy(Strategy) {
  constructor() {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      secretOrKey: 'SECURITY_FIRST_SECRET',
    });
  }

  async validate(payload: any): Promise<UserPrincipal> {
    return { id: payload.sub, email: payload.email, role: payload.role };
  }
}

// --- FILE: src/modules/auth/strategies/google.strategy.ts ---
import { Injectable } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { Strategy } from 'passport-google-oauth20';
import { AuthService } from '../auth.service';

@Injectable()
export class GoogleAuthStrategy extends PassportStrategy(Strategy, 'google') {
  constructor(private readonly authService: AuthService) {
    super({
      clientID: 'ABSTRACTED_GOOGLE_ID',
      clientSecret: 'ABSTRACTED_GOOGLE_SECRET',
      callbackURL: '/auth/google/redirect',
      scope: ['email'],
    });
  }

  async validate(accessToken: string, refreshToken: string, profile: any): Promise<any> {
    return this.authService.processOAuthUser(profile.emails[0].value);
  }
}

// --- FILE: src/modules/auth/auth.controller.ts ---
import { Controller, Post, Body, UseGuards, Get, Req } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { AuthService } from './auth.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('login')
  async login(@Body() credentials: { email: string; password; string }) {
    const principal = await this.authService.validateCredentials(credentials.email, credentials.password);
    return this.authService.createToken(principal);
  }

  @Get('google')
  @UseGuards(AuthGuard('google'))
  initiateGoogleAuth() { /* Redirects to Google */ }

  @Get('google/redirect')
  @UseGuards(AuthGuard('google'))
  handleGoogleRedirect(@Req() req) {
    // req.user is the UserPrincipal from our strategy
    return this.authService.createToken(req.user);
  }
}

// --- FILE: src/modules/auth/auth.module.ts ---
import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { UserModule } from '../user/user.module';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { GoogleAuthStrategy } from './strategies/google.strategy';
import { JwtAuthStrategy } from './strategies/jwt.strategy';
import { RolesGuard } from '../../common/guards/roles.guard';

@Module({
  imports: [
    UserModule,
    PassportModule,
    JwtModule.register({
      secret: 'SECURITY_FIRST_SECRET',
      signOptions: { expiresIn: '15m' },
    }),
  ],
  controllers: [AuthController],
  providers: [AuthService, JwtAuthStrategy, GoogleAuthStrategy, RolesGuard],
})
export class AuthModule {}

// --- FILE: src/modules/posts/posts.controller.ts ---
import { Controller, Get, Post, Body, Req } from '@nestjs/common';
import { Authenticated } from '../../common/decorators/auth.decorator';
import { Role } from '../../domain/model';

@Controller('posts')
export class PostsController {
  // Requires any authenticated user (JWT valid)
  @Authenticated()
  @Get('me')
  getMyPosts(@Req() req) {
    return { message: `Fetching posts for user ${req.user.email}` };
  }

  // Requires an authenticated user with the ADMIN role
  @Authenticated(Role.ADMIN)
  @Post('publish')
  publishPost(@Body() post: { title: string; content: string }, @Req() req) {
    return {
      message: `Admin ${req.user.email} published a post.`,
      post,
    };
  }

  // Requires an authenticated user with USER role
  @Authenticated(Role.USER)
  @Post('draft')
  saveDraft(@Body() post: { title: string; content: string }, @Req() req) {
    return {
      message: `User ${req.user.email} saved a draft.`,
      post,
    };
  }
}

// --- FILE: src/app.module.ts ---
import { Module } from '@nestjs/common';
import { AuthModule } from './modules/auth/auth.module';
import { UserModule } from './modules/user/user.module';
import { PostsController } from './modules/posts/posts.controller';

@Module({
  imports: [AuthModule, UserModule],
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

  // Session middleware for OAuth
  app.use(session({ secret: 'abstracted-secret', resave: false, saveUninitialized: false }));
  app.use(passport.initialize());
  app.use(passport.session());

  await app.listen(3000);
}
bootstrap();
*/