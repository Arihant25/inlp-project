/*
 * VARIATION 2: The "Type-Safe Enthusiast"
 * Style: Service-layer oriented, functional, mimicking a TypeScript/modern JS approach.
 * Tech: Fastify, Prisma
 * Organization:
 *   - prisma/ (schema definition)
 *   - lib/ (Prisma client setup)
 *   - services/ (business logic encapsulation)
 *   - routes/ (route handlers)
 *   - app.js (entry point)
 * Note: Prisma Client is mocked here for self-containment, as it requires a generation step.
 */

// --- package.json ---
/*
{
  "name": "variation-2-prisma",
  "version": "1.0.0",
  "main": "app.js",
  "dependencies": {
    "@fastify/sensible": "^5.5.0",
    "fastify": "^4.26.2",
    "uuid": "^9.0.1"
  },
  "devDependencies": {
    "@prisma/client": "^5.12.1",
    "prisma": "^5.12.1",
    "vitest": "^1.5.0",
    "vitest-mock-extended": "^1.3.1"
  }
}
*/

const Fastify = require('fastify');
const { v4: uuidv4 } = require('uuid');
const { mockDeep, mockReset } = require('vitest-mock-extended'); // A popular mocking library

// --- 1. Prisma Schema (prisma/schema.prisma) ---
const prismaSchema = `
datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

generator client {
  provider = "prisma-client-js"
}

model User {
  id            String    @id @default(uuid())
  email         String    @unique
  password_hash String
  is_active     Boolean   @default(true)
  created_at    DateTime  @default(now())
  posts         Post[]
  roles         UserRole[]
}

model Post {
  id         String   @id @default(uuid())
  title      String
  content    String?
  status     Status   @default(DRAFT)
  created_at DateTime @default(now())
  author     User     @relation(fields: [user_id], references: [id])
  user_id    String
}

model Role {
  id    String     @id @default(uuid())
  name  String     @unique // "ADMIN", "USER"
  users UserRole[]
}

model UserRole {
  user    User   @relation(fields: [user_id], references: [id])
  user_id String
  role    Role   @relation(fields: [role_id], references: [id])
  role_id String

  @@id([user_id, role_id])
}

enum Status {
  DRAFT
  PUBLISHED
}
`;

// --- 2. Prisma Client Setup (lib/prisma.js) ---
// In a real app, this would be `new PrismaClient()`. We mock it for this snippet.
const prismaClient = mockDeep();

// --- 3. Service Layer (services/userService.js) ---
const userService = {
  // Transaction Example
  async createUserWithRoles(prisma, userData) {
    const { email, password_hash, roleNames } = userData;

    // Prisma's $transaction API for atomicity
    return prisma.$transaction(async (tx) => {
      const user = await tx.user.create({
        data: {
          email,
          password_hash,
        },
      });

      if (roleNames && roleNames.length > 0) {
        const roles = await tx.role.findMany({
          where: { name: { in: roleNames } },
        });

        await tx.userRole.createMany({
          data: roles.map((role) => ({
            user_id: user.id,
            role_id: role.id,
          })),
        });
      }

      // Refetch the user with their roles
      return tx.user.findUnique({
        where: { id: user.id },
        include: { roles: { include: { role: true } } },
      });
    });
  },

  // Query Building Example
  async findUsers(prisma, filters) {
    const { isActive, includePosts } = filters;
    const whereClause = {};
    if (isActive !== undefined) {
      whereClause.is_active = isActive;
    }

    const includeClause = {};
    if (includePosts) {
      includeClause.posts = true;
    }

    return prisma.user.findMany({
      where: whereClause,
      include: includeClause,
    });
  },

  async findUserById(prisma, id) {
    return prisma.user.findUnique({
      where: { id },
      include: {
        posts: true,
        roles: { include: { role: true } },
      },
    });
  },
};

// --- 4. Route Definitions (routes/userApi.js) ---
async function userApi(fastify, options) {
  const { prisma, services } = options;

  fastify.post('/users', async (request, reply) => {
    try {
      const user = await services.userService.createUserWithRoles(prisma, request.body);
      reply.code(201).send(user);
    } catch (error) {
      fastify.log.error(error);
      // Using @fastify/sensible for standard error handling
      throw fastify.httpErrors.internalServerError('Could not create user');
    }
  });

  fastify.get('/users', async (request, reply) => {
    const filters = {
      isActive: request.query.is_active ? request.query.is_active === 'true' : undefined,
      includePosts: request.query.with_posts === 'true',
    };
    return services.userService.findUsers(prisma, filters);
  });

  fastify.get('/users/:id', async (request, reply) => {
    const user = await services.userService.findUserById(prisma, request.params.id);
    if (!user) {
      throw fastify.httpErrors.notFound('User not found');
    }
    return user;
  });
}

// --- 5. App Entry Point (app.js) ---
const app = Fastify({ logger: true });

// Mocking setup for demonstration
const setupMocks = () => {
    const MOCK_USER_ID = uuidv4();
    const MOCK_ADMIN_ROLE_ID = uuidv4();
    const MOCK_USER_ROLE_ID = uuidv4();

    prismaClient.user.findMany.mockResolvedValue([
        { id: MOCK_USER_ID, email: 'test@example.com', is_active: true, posts: [] }
    ]);
    prismaClient.user.findUnique.mockImplementation(async (args) => {
        if (args.where.id === MOCK_USER_ID) {
            return {
                id: MOCK_USER_ID,
                email: 'test@example.com',
                is_active: true,
                posts: [{ id: uuidv4(), title: 'Mock Post', content: '...' }],
                roles: [{ role: { id: MOCK_USER_ROLE_ID, name: 'USER' } }]
            };
        }
        return null;
    });
    // Mock the transaction
    prismaClient.$transaction.mockImplementation(callback => callback(prismaClient));
    prismaClient.user.create.mockResolvedValue({ id: MOCK_USER_ID, email: 'new@example.com' });
    prismaClient.role.findMany.mockResolvedValue([{ id: MOCK_USER_ROLE_ID, name: 'USER' }]);
    prismaClient.userRole.createMany.mockResolvedValue({ count: 1 });
};

const startServer = async () => {
  try {
    setupMocks();
    // Decorate fastify instance with prisma client and services
    app.decorate('prisma', prismaClient);
    app.decorate('services', { userService });
    
    // Sensible plugin adds helpers like httpErrors
    app.register(require('@fastify/sensible'));

    // Register routes, passing dependencies
    app.register(userApi, { prefix: '/api/v1', prisma: app.prisma, services: app.services });

    await app.listen({ port: 3001 });
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
};

if (require.main === module) {
  startServer();
} else {
  module.exports = { app, prismaClient, userService };
}