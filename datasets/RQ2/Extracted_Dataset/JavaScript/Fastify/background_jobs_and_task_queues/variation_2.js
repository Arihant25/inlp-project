// Variation 2: The "OOP/Class-based" Developer
// Style: Object-Oriented, encapsulating logic in classes.
// Organization: Separate classes for Server, TaskService, and JobProcessing.
// Naming: PascalCase for classes, camelCase for methods and variables.

const Fastify = require('fastify');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- Mock Services (could be in their own files) ---
class MockDataStore {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
    }
    async findUserById(id) { return this.users.get(id); }
    async findPostById(id) { return this.posts.get(id); }
    async saveUser(user) { this.users.set(user.id, user); }
    async savePost(post) { this.posts.set(post.id, post); }
}

class MockEmailer {
    async send(to, subject, body) {
        console.log(`[Emailer] Sending email to ${to} with subject "${subject}"`);
        await new Promise(resolve => setTimeout(resolve, 500));
        if (Math.random() > 0.8) throw new Error("Failed to connect to mail server");
        console.log(`[Emailer] Email sent successfully.`);
        return { success: true };
    }
}

class MockImageEngine {
    async process(buffer) {
        console.log(`[ImageEngine] Starting image processing pipeline...`);
        await new Promise(resolve => setTimeout(resolve, 2000));
        console.log(`[ImageEngine] Pipeline finished.`);
        return { url: `cdn.example.com/${uuidv4()}.jpg` };
    }
}

// --- Core Application Classes ---

class TaskService {
    constructor(connection) {
        this.queueName = 'app-tasks';
        this.queue = new Queue(this.queueName, { connection });
        this.scheduler = new QueueScheduler(this.queueName, { connection });
        console.log('TaskService initialized.');
    }

    async scheduleWelcomeEmail(userId) {
        return this.queue.add('email:send-welcome', { userId }, {
            attempts: 3,
            backoff: { type: 'exponential', delay: 2000 },
        });
    }

    async scheduleImageProcessing(postId, imageBuffer) {
        return this.queue.add('image:process-post', { postId, imageBuffer }, {
            attempts: 2,
            backoff: { type: 'fixed', delay: 10000 },
        });
    }

    async schedulePeriodicJobs() {
        await this.queue.add('system:generate-report', { type: 'monthly' }, {
            repeat: { pattern: '0 2 * * 1' }, // At 02:00 on Monday.
            jobId: 'monthly-system-report'
        });
        console.log('Scheduled periodic system report job.');
    }

    async getJob(jobId) {
        return this.queue.getJob(jobId);
    }

    async close() {
        await this.scheduler.close();
        await this.queue.close();
    }
}

class JobProcessor {
    constructor(connection, queueName, dependencies) {
        this.worker = new Worker(queueName, this.process.bind(this), { connection });
        this.db = dependencies.db;
        this.emailer = dependencies.emailer;
        this.imageEngine = dependencies.imageEngine;
        this.registerEvents();
        console.log('JobProcessor initialized and listening for jobs.');
    }

    registerEvents() {
        this.worker.on('completed', job => console.log(`Job ${job.id} of type ${job.name} completed.`));
        this.worker.on('failed', (job, err) => console.error(`Job ${job.id} failed: ${err.message}`));
    }

    async process(job) {
        console.log(`Processing job ${job.name} with ID ${job.id}`);
        switch (job.name) {
            case 'email:send-welcome':
                const user = await this.db.findUserById(job.data.userId);
                if (!user) throw new Error(`User not found: ${job.data.userId}`);
                await this.emailer.send(user.email, 'Welcome!', 'Thanks for joining.');
                break;
            case 'image:process-post':
                const post = await this.db.findPostById(job.data.postId);
                if (!post) throw new Error(`Post not found: ${job.data.postId}`);
                await this.imageEngine.process(job.data.imageBuffer);
                break;
            case 'system:generate-report':
                console.log('Generating periodic system report...');
                await new Promise(resolve => setTimeout(resolve, 3000));
                console.log('Report generated.');
                break;
            default:
                throw new Error(`No processor for job name ${job.name}`);
        }
    }

    async close() {
        await this.worker.close();
    }
}

class Server {
    constructor(port, taskService, db) {
        this.port = port;
        this.fastify = Fastify({ logger: true });
        this.taskService = taskService;
        this.db = db;
        this.configureRoutes();
    }

    configureRoutes() {
        this.fastify.post('/users', this.createUser.bind(this));
        this.fastify.post('/posts', this.createPost.bind(this));
        this.fastify.get('/jobs/:id', this.getJobStatus.bind(this));
    }

    async createUser(request, reply) {
        const { email, password } = request.body;
        const user = { id: uuidv4(), email, password_hash: `hash(${password})`, role: 'USER', is_active: true, created_at: new Date() };
        await this.db.saveUser(user);
        const job = await this.taskService.scheduleWelcomeEmail(user.id);
        reply.code(201).send({ userId: user.id, jobId: job.id });
    }

    async createPost(request, reply) {
        const { userId, title, content } = request.body;
        const post = { id: uuidv4(), user_id: userId, title, content, status: 'DRAFT' };
        await this.db.savePost(post);
        const job = await this.taskService.scheduleImageProcessing(post.id, Buffer.from('mock-img-data'));
        reply.code(201).send({ postId: post.id, jobId: job.id });
    }

    async getJobStatus(request, reply) {
        const job = await this.taskService.getJob(request.params.id);
        if (!job) return reply.code(404).send({ error: 'Not Found' });
        const state = await job.getState();
        return { id: job.id, name: job.name, state, failedReason: job.failedReason };
    }

    async start() {
        try {
            await this.taskService.schedulePeriodicJobs();
            await this.fastify.listen({ port: this.port });
        } catch (err) {
            this.fastify.log.error(err);
            process.exit(1);
        }
    }
}

// --- Application Entry Point ---
async function main() {
    const connection = new IORedis('redis://127.0.0.1:6379', { maxRetriesPerRequest: null });

    const db = new MockDataStore();
    const emailer = new MockEmailer();
    const imageEngine = new MockImageEngine();

    const taskService = new TaskService(connection);
    const jobProcessor = new JobProcessor(connection, taskService.queueName, { db, emailer, imageEngine });

    const server = new Server(3000, taskService, db);
    await server.start();

    // Graceful shutdown
    const signals = ['SIGINT', 'SIGTERM'];
    signals.forEach(signal => {
        process.on(signal, async () => {
            console.log(`Received ${signal}, shutting down...`);
            await server.fastify.close();
            await jobProcessor.close();
            await taskService.close();
            await connection.quit();
            process.exit(0);
        });
    });
}

main().catch(console.error);