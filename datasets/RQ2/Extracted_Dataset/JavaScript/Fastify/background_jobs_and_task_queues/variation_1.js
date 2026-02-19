// Variation 1: The "Pragmatic Monolith" Developer
// Style: Functional, all-in-one file for simplicity.
// Organization: All logic (server, routes, queue, worker) in a single file.
// Naming: Standard camelCase, descriptive function names.

const Fastify = require('fastify');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- Mocks & Configuration ---
// In a real app, this would come from environment variables
const REDIS_CONNECTION_STRING = 'redis://127.0.0.1:6379';
const connection = new IORedis(REDIS_CONNECTION_STRING, { maxRetriesPerRequest: null });

// Mock Database
const mockDb = {
    users: new Map(),
    posts: new Map(),
    findUserById: async (id) => mockDb.users.get(id),
    findPostById: async (id) => mockDb.posts.get(id),
};

// Mock Email Service
const mockEmailService = {
    send: async ({ to, subject, body }) => {
        console.log(`--- Sending Email ---`);
        console.log(`To: ${to}`);
        console.log(`Subject: ${subject}`);
        console.log(`Body: ${body}`);
        // Simulate network delay and potential failure
        if (Math.random() > 0.8) {
            throw new Error("SMTP server connection failed");
        }
        console.log(`--- Email Sent ---`);
        return { messageId: uuidv4() };
    }
};

// Mock Image Processor
const mockImageProcessor = {
    resizeAndWatermark: async (buffer) => {
        console.log(`[Image Processor] Resizing and watermarking image...`);
        await new Promise(resolve => setTimeout(resolve, 1500)); // Simulate processing time
        console.log(`[Image Processor] Processing complete.`);
        return Buffer.from('processed-image-data');
    }
};

// --- Task Queue & Worker Setup ---
const TASK_QUEUE_NAME = 'default-tasks';

const taskQueue = new Queue(TASK_QUEUE_NAME, { connection });
const queueScheduler = new QueueScheduler(TASK_QUEUE_NAME, { connection }); // For repeatable jobs

const worker = new Worker(TASK_QUEUE_NAME, async job => {
    console.log(`Processing job '${job.name}' (ID: ${job.id})`);
    switch (job.name) {
        case 'sendWelcomeEmail':
            const user = await mockDb.findUserById(job.data.userId);
            if (!user) throw new Error(`User with ID ${job.data.userId} not found.`);
            await mockEmailService.send({
                to: user.email,
                subject: 'Welcome to Our Platform!',
                body: `Hi ${user.email}, thanks for signing up!`
            });
            break;
        case 'processPostImage':
            const post = await mockDb.findPostById(job.data.postId);
            if (!post) throw new Error(`Post with ID ${job.data.postId} not found.`);
            await mockImageProcessor.resizeAndWatermark(job.data.imageBuffer);
            // In a real app, you'd save the processed image URL to the post
            console.log(`Image for post "${post.title}" processed.`);
            break;
        case 'generateMonthlyReport':
            console.log(`[Periodic Task] Generating monthly report for ${job.data.month}...`);
            await new Promise(resolve => setTimeout(resolve, 5000)); // Simulate long-running report generation
            console.log(`[Periodic Task] Monthly report generation complete.`);
            break;
        default:
            throw new Error(`Unknown job name: ${job.name}`);
    }
}, { connection });

worker.on('completed', job => {
    console.log(`Job '${job.name}' (ID: ${job.id}) has completed.`);
});

worker.on('failed', (job, err) => {
    console.error(`Job '${job.name}' (ID: ${job.id}) has failed with error: ${err.message}`);
});

// --- Periodic Task Scheduling ---
async function schedulePeriodicTasks() {
    await taskQueue.add(
        'generateMonthlyReport',
        { month: new Date().toLocaleString('default', { month: 'long' }) },
        {
            repeat: {
                cron: '0 0 1 * *' // At 00:00 on day-of-month 1.
            },
            jobId: 'monthly-report-job' // Prevent duplicates
        }
    );
    console.log('Scheduled monthly report generation task.');
}

// --- Fastify Server ---
const fastify = Fastify({ logger: true });

// Decorate fastify instance with the queue for easy access in routes
fastify.decorate('taskQueue', taskQueue);

// --- API Routes ---

// Create a new user and schedule a welcome email
fastify.post('/users', async (request, reply) => {
    const { email, password } = request.body;
    const newUser = {
        id: uuidv4(),
        email,
        password_hash: `hashed_${password}`,
        role: 'USER',
        is_active: true,
        created_at: new Date().toISOString()
    };
    mockDb.users.set(newUser.id, newUser);

    const job = await fastify.taskQueue.add('sendWelcomeEmail', { userId: newUser.id }, {
        attempts: 3,
        backoff: {
            type: 'exponential',
            delay: 1000,
        },
    });

    return reply.status(201).send({
        message: 'User created and welcome email scheduled.',
        userId: newUser.id,
        jobId: job.id
    });
});

// Create a new post and schedule image processing
fastify.post('/posts', async (request, reply) => {
    const { userId, title, content } = request.body;
    const newPost = {
        id: uuidv4(),
        user_id: userId,
        title,
        content,
        status: 'DRAFT'
    };
    mockDb.posts.set(newPost.id, newPost);

    const job = await fastify.taskQueue.add('processPostImage', {
        postId: newPost.id,
        imageBuffer: Buffer.from('fake-image-data')
    }, {
        attempts: 2,
        backoff: { type: 'fixed', delay: 5000 }
    });

    return reply.status(201).send({
        message: 'Post created and image processing scheduled.',
        postId: newPost.id,
        jobId: job.id
    });
});

// Check the status of a job
fastify.get('/jobs/:id', async (request, reply) => {
    const { id } = request.params;
    const job = await fastify.taskQueue.getJob(id);

    if (!job) {
        return reply.status(404).send({ error: 'Job not found' });
    }

    const state = await job.getState();
    const progress = job.progress;
    const returnValue = job.returnvalue;

    return {
        id: job.id,
        name: job.name,
        data: job.data,
        state,
        progress,
        returnValue,
        failedReason: job.failedReason,
    };
});


// --- Start Server ---
const start = async () => {
    try {
        await schedulePeriodicTasks();
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        await worker.close();
        await queueScheduler.close();
        await taskQueue.close();
        process.exit(1);
    }
};

start();