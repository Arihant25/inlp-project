// --- DEPENDENCIES ---
// @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/jwt, @nestjs/passport
// passport, passport-local, passport-jwt, passport-google-oauth20, bcrypt, class-validator, class-transformer
// express-session, reflect-metadata, rxjs

// --- FILE: src/app.roles.ts ---
export enum AppRole {
  USER = 'USER',
  ADMIN = 'ADMIN',
}

// --- FILE: src/user/user.service.mock.ts ---
import { Injectable } from '@nestjs/common';
import * as bcrypt from 'bcrypt';
import { v4 as uuidv4 } from 'uuid';
import { AppRole } from '../app.roles';

@Injectable()
export class UserServiceMock {
  private readonly users = [];

  constructor() {
    this.seedData();
  }

  private async seedData() {
    this.users.push({
      id: uuidv4(),
      email: 'test.user@domain.com',
      password_hash: await bcrypt.hash('password', 10),
      role: AppRole.USER,
    });
    this.users.push({
      id: uuidv4(),
      email: 'test.admin@domain.com',
      password_hash: await bcrypt.hash('admin_password', 10),
      role: AppRole.ADMIN,
    });
  }

  async findByEmail(email: string) {
    return this.users.find(u => u.email === email);
  }

  async findOrCreateForOAuth(email: string) {
    let user = await this.findByEmail(email);
    if (!user) {
      user = {
        id: uuidv4(),
        email,
        password_hash: null,
        role: AppRole.USER,
      };
      this.users.push(user);
    }
    return user;
  }
}

// --- FILE: src/user/user.module.ts ---
import { Module } from '@nestjs/common';
import { UserServiceMock } from './user.service.mock';

@Module({
  providers: [UserServiceMock],
  exports: [UserServiceMock],
})
export class UserModule {}

// --- FILE: src/auth/auth.roles.decorator.ts ---
import { SetMetadata } from '@nestjs/common';
import { AppRole } from '../app.roles';
export const HasRoles = (...roles: AppRole[]) => SetMetadata('roles', roles);

// --- FILE: src/auth/auth.roles.guard.ts ---
import { Injectable, CanActivate, ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';

@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const roles = this.reflector.get<string[]>('roles', context.getHandler());
    if (!roles || roles.length === 0) {
      return true;
    }
    const request = context.switchToHttp().getRequest();
    const user = request.user;
    return roles.includes(user.role);
  }
}

// --- FILE: src/auth/auth.local.strategy.ts ---
import { Strategy as LocalStrategy } from 'passport-local';
import { PassportStrategy } from '@nestjs/passport';
import { Injectable, UnauthorizedException } from '@nestjs/common';
import { AuthService } from './auth.service';

@Injectable()
export class AppLocalStrategy extends PassportStrategy(LocalStrategy) {
  constructor(private authService: AuthService) {
    super({ usernameField: 'email' });
  }

  async validate(email: string, password: string): Promise<any> {
    const user = await this.authService.validateLocalUser(email, password);
    if (!user) {
      throw new UnauthorizedException();
    }
    return user;
  }
}

// --- FILE: src/auth/auth.jwt.strategy.ts ---
import { ExtractJwt, Strategy as JwtStrategy } from 'passport-jwt';
import { PassportStrategy } from '@nestjs/passport';
import { Injectable } from '@nestjs/common';

@Injectable()
export class AppJwtStrategy extends PassportStrategy(JwtStrategy) {
  constructor() {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      secretOrKey: 'SECRET_FOR_MONOLITHIC_MODULE',
    });
  }

  async validate(payload: any) {
    return { userId: payload.sub, email: payload.email, role: payload.role };
  }
}

// --- FILE: src/auth/auth.google.strategy.ts ---
import { PassportStrategy } from '@nestjs/passport';
import { Strategy as GoogleStrategy } from 'passport-google-oauth20';
import { Injectable } from '@nestjs/common';
import { AuthService } from './auth.service';

@Injectable()
export class AppGoogleStrategy extends PassportStrategy(GoogleStrategy, 'google') {
  constructor(private authService: AuthService) {
    super({
      clientID: 'MONOLITHIC_GOOGLE_ID',
      clientSecret: 'MONOLITHIC_GOOGLE_SECRET',
      callbackURL: 'http://localhost:3000/api/auth/google/callback',
      scope: ['email', 'profile'],
    });
  }

  async validate(accessToken: string, refreshToken: string, profile: any, done: Function) {
    const user = await this.authService.handleOAuth(profile.emails[0].value);
    done(null, user);
  }
}

// --- FILE: src/auth/auth.service.ts ---
import { Injectable } from '@nestjs/common';
import { UserServiceMock } from '../user/user.service.mock';
import { JwtService } from '@nestjs/jwt';
import * as bcrypt from 'bcrypt';

@Injectable()
export class AuthService {
  constructor(
    private userService: UserServiceMock,
    private jwtService: JwtService,
  ) {}

  async validateLocalUser(email: string, pass: string): Promise<any> {
    const user = await this.userService.findByEmail(email);
    if (user && (await bcrypt.compare(pass, user.password_hash))) {
      const { password_hash, ...result } = user;
      return result;
    }
    return null;
  }

  async handleOAuth(email: string) {
    return this.userService.findOrCreateForOAuth(email);
  }

  async issueToken(user: any) {
    const payload = { email: user.email, sub: user.id, role: user.role };
    return {
      access_token: this.jwtService.sign(payload),
    };
  }
}

// --- FILE: src/auth/auth.controller.ts ---
import { Controller, Request, Post, UseGuards, Get } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { AuthService } from './auth.service';

@Controller('api/auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  @UseGuards(AuthGuard('local'))
  @Post('login')
  async login(@Request() req) {
    return this.authService.issueToken(req.user);
  }

  @UseGuards(AuthGuard('google'))
  @Get('google')
  googleLogin() { /* Initiates Google OAuth flow */ }

  @UseGuards(AuthGuard('google'))
  @Get('google/callback')
  googleCallback(@Request() req) {
    // For session-based OAuth, req.user is now in the session.
    // For token-based, we issue a JWT here.
    return this.authService.issueToken(req.user);
  }
}

// --- FILE: src/auth/auth.module.ts ---
import { Module } from '@nestjs/common';
import { AuthService } from './auth.service';
import { AuthController } from './auth.controller';
import { UserModule } from '../user/user.module';
import { PassportModule } from '@nestjs/passport';
import { JwtModule } from '@nestjs/jwt';
import { AppLocalStrategy } from './auth.local.strategy';
import { AppJwtStrategy } from './auth.jwt.strategy';
import { AppGoogleStrategy } from './auth.google.strategy';

@Module({
  imports: [
    UserModule,
    PassportModule.register({ session: true }), // Enable session support for OAuth
    JwtModule.register({
      secret: 'SECRET_FOR_MONOLITHIC_MODULE',
      signOptions: { expiresIn: '60m' },
    }),
  ],
  providers: [AuthService, AppLocalStrategy, AppJwtStrategy, AppGoogleStrategy],
  controllers: [AuthController],
})
export class AuthModule {}

// --- FILE: src/posts/posts.controller.ts ---
import { Controller, Get, UseGuards, Post, Body, Request } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { HasRoles } from '../auth/auth.roles.decorator';
import { RolesGuard } from '../auth/auth.roles.guard';
import { AppRole } from '../app.roles';

@Controller('api/posts')
export class PostsController {
  // This route is protected by JWT, and requires ADMIN role
  @UseGuards(AuthGuard('jwt'), RolesGuard)
  @HasRoles(AppRole.ADMIN)
  @Post()
  createPost(@Body() postDto: any, @Request() req) {
    return {
      message: `Admin ${req.user.email} created a post.`,
      data: postDto,
    };
  }

  // This route is protected by JWT, and requires USER or ADMIN role
  @UseGuards(AuthGuard('jwt'), RolesGuard)
  @HasRoles(AppRole.USER, AppRole.ADMIN)
  @Get()
  getPosts(@Request() req) {
    return {
      message: `User ${req.user.email} is fetching posts.`,
      data: [{ title: 'First Post' }, { title: 'Second Post' }],
    };
  }
}

// --- FILE: src/posts/posts.module.ts ---
import { Module } from '@nestjs/common';
import { PostsController } from './posts.controller';

@Module({
  controllers: [PostsController],
})
export class PostsModule {}

// --- FILE: src/app.module.ts ---
import { Module } from '@nestjs/common';
import { AuthModule } from './auth/auth.module';
import { UserModule } from './user/user.module';
import { PostsModule } from './posts/posts.module';

@Module({
  imports: [AuthModule, UserModule, PostsModule],
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

  // Session support is required for passport-google-oauth20 flow
  app.use(
    session({
      secret: 'a_strong_session_secret',
      resave: false,
      saveUninitialized: false,
    }),
  );
  app.use(passport.initialize());
  app.use(passport.session());

  await app.listen(3000);
}
bootstrap();
*/