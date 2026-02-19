// --- DEPENDENCIES ---
// @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/jwt, @nestjs/passport
// passport, passport-jwt, passport-google-oauth20, bcrypt, class-validator, class-transformer
// express-session, reflect-metadata, rxjs

// --- FILE: src/shared/user.enums.ts ---
export enum UserRole {
  USER = 'USER',
  ADMIN = 'ADMIN',
}

// --- FILE: src/users/user.entity.ts ---
export class User {
  id: string;
  email: string;
  password_hash: string;
  role: UserRole;
  is_active: boolean;
  created_at: Date;
}

// --- FILE: src/users/users.service.ts (Mock Implementation) ---
import { Injectable } from '@nestjs/common';
import { User } from './user.entity';
import { UserRole } from '../shared/user.enums';
import * as bcrypt from 'bcrypt';
import { v4 as uuidv4 } from 'uuid';

@Injectable()
export class UsersService {
  private readonly users: User[] = [];

  constructor() {
    // Create mock users
    this.createMockUsers();
  }

  private async createMockUsers() {
    const regularPassword = await bcrypt.hash('password123', 10);
    const adminPassword = await bcrypt.hash('adminPass456', 10);

    this.users.push({
      id: uuidv4(),
      email: 'user@example.com',
      password_hash: regularPassword,
      role: UserRole.USER,
      is_active: true,
      created_at: new Date(),
    });

    this.users.push({
      id: uuidv4(),
      email: 'admin@example.com',
      password_hash: adminPassword,
      role: UserRole.ADMIN,
      is_active: true,
      created_at: new Date(),
    });
  }

  async findOneByEmail(email: string): Promise<User | undefined> {
    return this.users.find(user => user.email === email);
  }

  async findOrCreateFromOAuth(profile: any): Promise<User> {
    const existingUser = this.users.find(user => user.email === profile.email);
    if (existingUser) {
      return existingUser;
    }
    const newUser: User = {
      id: uuidv4(),
      email: profile.email,
      password_hash: null, // No password for OAuth users
      role: UserRole.USER,
      is_active: true,
      created_at: new Date(),
    };
    this.users.push(newUser);
    return newUser;
  }
}

// --- FILE: src/users/users.module.ts ---
import { Module } from '@nestjs/common';
import { UsersService } from './users.service';

@Module({
  providers: [UsersService],
  exports: [UsersService],
})
export class UsersModule {}

// --- FILE: src/auth/dto/login.dto.ts ---
import { IsEmail, IsNotEmpty, IsString } from 'class-validator';

export class LoginDto {
  @IsEmail()
  email: string;

  @IsString()
  @IsNotEmpty()
  password;
}

// --- FILE: src/auth/decorators/roles.decorator.ts ---
import { SetMetadata } from '@nestjs/common';
import { UserRole } from '../../shared/user.enums';

export const ROLES_KEY = 'roles';
export const Roles = (...roles: UserRole[]) => SetMetadata(ROLES_KEY, roles);

// --- FILE: src/auth/guards/jwt-auth.guard.ts ---
import { Injectable } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';

@Injectable()
export class JwtAuthGuard extends AuthGuard('jwt') {}

// --- FILE: src/auth/guards/roles.guard.ts ---
import { Injectable, CanActivate, ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { UserRole } from '../../shared/user.enums';
import { ROLES_KEY } from '../decorators/roles.decorator';

@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const requiredRoles = this.reflector.getAllAndOverride<UserRole[]>(ROLES_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (!requiredRoles) {
      return true;
    }
    const { user } = context.switchToHttp().getRequest();
    return requiredRoles.some((role) => user.role?.includes(role));
  }
}

// --- FILE: src/auth/strategies/jwt.strategy.ts ---
import { Injectable } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { ExtractJwt, Strategy } from 'passport-jwt';

@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy) {
  constructor() {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      ignoreExpiration: false,
      secretOrKey: 'MY_SUPER_SECRET_KEY', // In production, use ConfigService
    });
  }

  async validate(payload: any) {
    // The payload is the decoded JWT. Passport will attach this to request.user
    return { userId: payload.sub, email: payload.email, role: payload.role };
  }
}

// --- FILE: src/auth/strategies/google.strategy.ts ---
import { PassportStrategy } from '@nestjs/passport';
import { Strategy as GoogleStrategy, VerifyCallback } from 'passport-google-oauth20';
import { Injectable } from '@nestjs/common';
import { AuthService } from '../auth.service';

@Injectable()
export class GoogleStrategyService extends PassportStrategy(GoogleStrategy, 'google') {
  constructor(private readonly authService: AuthService) {
    super({
      clientID: 'YOUR_GOOGLE_CLIENT_ID', // In production, use ConfigService
      clientSecret: 'YOUR_GOOGLE_CLIENT_SECRET',
      callbackURL: 'http://localhost:3000/auth/google/callback',
      scope: ['email', 'profile'],
    });
  }

  async validate(accessToken: string, refreshToken: string, profile: any, done: VerifyCallback): Promise<any> {
    const user = await this.authService.validateOAuthUser({
      email: profile.emails[0].value,
      displayName: profile.displayName,
    });
    done(null, user);
  }
}

// --- FILE: src/auth/auth.service.ts ---
import { Injectable, UnauthorizedException } from '@nestjs/common';
import { UsersService } from '../users/users.service';
import { JwtService } from '@nestjs/jwt';
import * as bcrypt from 'bcrypt';
import { User } from '../users/user.entity';

@Injectable()
export class AuthService {
  constructor(
    private usersService: UsersService,
    private jwtService: JwtService,
  ) {}

  async validateUserByPassword(email: string, pass: string): Promise<any> {
    const user = await this.usersService.findOneByEmail(email);
    if (user && user.is_active && (await bcrypt.compare(pass, user.password_hash))) {
      const { password_hash, ...result } = user;
      return result;
    }
    return null;
  }

  async login(user: User) {
    const payload = { email: user.email, sub: user.id, role: user.role };
    return {
      access_token: this.jwtService.sign(payload),
    };
  }

  async validateOAuthUser(profile: { email: string; displayName: string }): Promise<User> {
    return this.usersService.findOrCreateFromOAuth(profile);
  }
}

// --- FILE: src/auth/auth.controller.ts ---
import { Controller, Post, Body, UseGuards, Request, Get, Req, Res } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { AuthService } from './auth.service';
import { LoginDto } from './dto/login.dto';
import { Response } from 'express';

@Controller('auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  @Post('login')
  async login(@Body() loginDto: LoginDto) {
    const user = await this.authService.validateUserByPassword(loginDto.email, loginDto.password);
    if (!user) {
      throw new UnauthorizedException('Invalid credentials');
    }
    return this.authService.login(user);
  }

  @Get('google')
  @UseGuards(AuthGuard('google'))
  async googleAuth(@Req() req) {
    // Initiates the Google OAuth2 login flow
  }

  @Get('google/callback')
  @UseGuards(AuthGuard('google'))
  googleAuthRedirect(@Req() req, @Res() res: Response) {
    // Here, req.user is populated by passport. We can create a session or JWT.
    // For this example, we'll just send back the user info.
    // In a real app, you'd redirect them to the frontend with a token or session cookie.
    res.json(req.user);
  }
}

// --- FILE: src/auth/auth.module.ts ---
import { Module } from '@nestjs/common';
import { AuthService } from './auth.service';
import { AuthController } from './auth.controller';
import { UsersModule } from '../users/users.module';
import { PassportModule } from '@nestjs/passport';
import { JwtModule } from '@nestjs/jwt';
import { JwtStrategy } from './strategies/jwt.strategy';
import { GoogleStrategyService } from './strategies/google.strategy';

@Module({
  imports: [
    UsersModule,
    PassportModule.register({ defaultStrategy: 'jwt', session: true }),
    JwtModule.register({
      secret: 'MY_SUPER_SECRET_KEY',
      signOptions: { expiresIn: '3600s' },
    }),
  ],
  controllers: [AuthController],
  providers: [AuthService, JwtStrategy, GoogleStrategyService],
  exports: [AuthService],
})
export class AuthModule {}

// --- FILE: src/posts/posts.controller.ts ---
import { Controller, Get, UseGuards, Post as HttpPost, Body, Request } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { RolesGuard } from '../auth/guards/roles.guard';
import { Roles } from '../auth/decorators/roles.decorator';
import { UserRole } from '../shared/user.enums';

@Controller('posts')
export class PostsController {
  @UseGuards(JwtAuthGuard)
  @Get('protected')
  getProtectedData(@Request() req) {
    return { message: 'This is protected data', user: req.user };
  }

  @Roles(UserRole.ADMIN)
  @UseGuards(JwtAuthGuard, RolesGuard)
  @HttpPost('admin')
  createPostAsAdmin(@Body() postData: any, @Request() req) {
    return { message: 'Post created by admin', post: postData, user: req.user };
  }

  @Roles(UserRole.USER, UserRole.ADMIN)
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Get('all')
  getAllPosts() {
    return [{ id: 1, title: 'A post for all authenticated users' }];
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
import { UsersModule } from './users/users.module';
import { PostsModule } from './posts/posts.module';

@Module({
  imports: [AuthModule, UsersModule, PostsModule],
})
export class AppModule {}

// --- FILE: src/main.ts ---
/*
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import * as session from 'express-session';
import * as passport from 'passport';
import { ValidationPipe } from '@nestjs/common';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  
  app.useGlobalPipes(new ValidationPipe());

  // Session setup for OAuth
  app.use(
    session({
      secret: 'SOME_SESSION_SECRET',
      resave: false,
      saveUninitialized: false,
      cookie: { maxAge: 3600000 },
    }),
  );
  app.use(passport.initialize());
  app.use(passport.session());

  await app.listen(3000);
}
bootstrap();
*/