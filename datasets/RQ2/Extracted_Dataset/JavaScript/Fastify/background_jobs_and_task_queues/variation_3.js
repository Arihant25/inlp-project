// Variation 3: The "Modular/Service-Oriented" Developer
// Style: Functional, but highly modularized using Fastify plugins.
// Organization: Simulates a structured project with separate concerns (plugins, routes, workers).

const Fastify = require('fastify');
const fp = require('fastify-plugin');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- (Simulated) config/database.js ---
const mockDb = {
    users: new Map(),
    posts: new Map(),
    getUser: async (id) => mockDb.users.get(id),
    saveUser: async (user) => mockDb.users.set(user.id, user),
    getPost: async (id) => mockDb.posts.get(id),
    savePost: async (post) => mockDb.posts.set(post.id, post),
};

// --- (Simulated) services/email.js ---
const emailService = {
    sendWelcome: async (user) => {
        console.log(`EMAIL_SERVICE: Sending welcome email to ${user.email}`);
        await new Promise(res => setTimeout(res, 300));
        if (Math.random() > 0.9) throw new Error("SMTP Timeout");
        console.log(`EMAIL_SERVICE: Welcome email sent.`);
    }
};

// --- (Simulated) services/image.js ---
const imageService = {
    processPostImage: async (postId) => {
        console.log(`IMAGE_SERVICE: Processing image for post ${postId}`);
        await new Promise(res => setTimeout(res, 1200));
        console.log(`IMAGE_SERVICE: Image processed.`);
        return { path: `/images/processed/${uuidv4()}.webp` };
    }
};

// --- (Simulated) plugins/queues.js ---
const queuesPlugin = fp(async (fastify, opts) => {
    const connection = new IORedis(opts.redisUrl, { maxRetriesPerRequest: null });

    const emailQueue = new Queue('email-queue', { connection });
    const imageQueue = new Queue('image-queue', { connection });
    const systemQueue = new Queue('system-queue', { connection });

    // For repeatable jobs
    const systemScheduler = new QueueScheduler('system-queue', { connection });

    fastify.decorate('queues', { emailQueue, imageQueue, systemQueue });
    fastify.addHook('onClose', async () => {
        await systemScheduler.close();
        await emailQueue.close();
        await imageQueue.close();
        await systemQueue.close();
        await connection.quit();
    });
    console.log('Queues plugin registered.');
});

// --- (Simulated) routes/users.js ---
async function userRoutes(fastify, opts) {
    fastify.post('/users', async (request, reply) => {
        const { email, password } = request.body;
        const user = { id: uuidv4(), email, password_hash: '...', role: 'USER', is_active: true, created_at: new Date() };
        await mockDb.saveUser(user);

        const job = await fastify.queues.emailQueue.add('send-welcome', { userId: user.id }, {
            attempts: 4,
            backoff: { type: 'exponential', delay: 5000 }
        });

        reply.status(201).send({ message: 'User created', userId: user.id, emailJobId: job.id });
    });
}

// --- (Simulated) routes/posts.js ---
async function postRoutes(fastify, opts) {
    fastify.post('/posts', async (request, reply) => {
        const { userId, title, content } = request.body;
        const post = { id: uuidv4(), user_id: userId, title, content, status: 'DRAFT' };
        await mockDb.savePost(post);

        const job = await fastify.queues.imageQueue.add('process-post-image', { postId: post.id });
        reply.status(201).send({ message: 'Post created', postId: post.id, imageJobId: job.id });
    });
}

// --- (Simulated) routes/jobs.js ---
async function jobRoutes(fastify, opts) {
    fastify.get('/jobs/:queue/:id', async (request, reply) => {
        const { queue, id } = request.params;
        const queueInstance = fastify.queues[`${queue}Queue`];

        if (!queueInstance) {
            return reply.status(404).send({ error: 'Queue not found' });
        }
        const job = await queueInstance.getJob(id);
        if (!job) {
            return reply.status(404).send({ error: 'Job not found' });
        }
        const state = await job.getState();
        return { id: job.id, name: job.name, state, data: job.data, failedReason: job.failedReason };
    });
}

// --- (Simulated) workers/index.js ---
function startWorkers(redisUrl) {
    const connection = new IORedis(redisUrl, { maxRetriesPerRequest: null });

    const emailWorker = new Worker('email-queue', async job => {
        if (job.name === 'send-welcome') {
            const user = await mockDb.getUser(job.data.userId);
            if (!user) throw new Error('User not found');
            await emailService.sendWelcome(user);
        }
    }, { connection });

    const imageWorker = new Worker('image-queue', async job => {
        if (job.name === 'process-post-image') {
            await imageService.processPostImage(job.data.postId);
        }
    }, { connection });

    const systemWorker = new Worker('system-queue', async job => {
        if (job.name === 'cleanup-logs') {
            console.log('WORKER: Cleaning up old system logs...');
            await new Promise(res => setTimeout(res, 4000));
            console.log('WORKER: Log cleanup complete.');
        }
    }, { connection });

    emailWorker.on('failed', (j, e) => console.error(`Email job ${j.id} failed: ${e.message}`));
    imageWorker.on('failed', (j, e) => console.error(`Image job ${j.id} failed: ${e.message}`));

    console.log('All workers started.');
    return [emailWorker, imageWorker, systemWorker];
}

// --- (Simulated) app.js ---
async function buildApp() {
    const app = Fastify({ logger: true });
    const REDIS_URL = 'redis://127.0.0.1:6379';

    // Register plugins
    app.register(queuesPlugin, { redisUrl: REDIS_URL });

    // Register routes after plugins are ready
    app.register(async (fastify, opts) => {
        fastify.register(userRoutes, { prefix: '/api' });
        fastify.register(postRoutes, { prefix: '/api' });
        fastify.register(jobRoutes, { prefix: '/api' });
    });

    await app.ready();

    // Schedule periodic tasks
    await app.queues.systemQueue.add('cleanup-logs', {}, {
        repeat: { every: 60 * 60 * 1000 }, // every hour
        jobId: 'periodic-log-cleanup'
    });
    console.log('Scheduled periodic log cleanup.');

    return app;
}

// --- (Simulated) server.js ---
async function main() {
    const REDIS_URL = 'redis://127.0.0.1:6379';
    const workers = startWorkers(REDIS_URL);
    const app = await buildApp();

    try {
        await app.listen({ port: 3000 });
    } catch (err) {
        app.log.error(err);
        await Promise.all(workers.map(w => w.close()));
        process.exit(1);
    }
}

main();