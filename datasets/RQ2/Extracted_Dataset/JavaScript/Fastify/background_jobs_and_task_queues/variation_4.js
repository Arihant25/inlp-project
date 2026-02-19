// Variation 4: The "Configuration-Driven" Developer
// Style: Functional, with logic driven by a central configuration object.
// Organization: Generic factory functions for creating queues and workers based on config.

const Fastify = require('fastify');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- Mock Implementations ---
const mockDatabase = {
    _data: { users: new Map(), posts: new Map() },
    fetchUser: async (id) => mockDatabase._data.users.get(id),
    persistUser: async (user) => mockDatabase._data.users.set(user.id, user),
    fetchPost: async (id) => mockDatabase._data.posts.get(id),
    persistPost: async (post) => mockDatabase._data.posts.set(post.id, post),
};

const mockServices = {
    email: {
        send: async (address, subject, body) => {
            console.log(`[SERVICE.EMAIL] Sending to ${address} | Subject: ${subject}`);
            await new Promise(r => setTimeout(r, 400));
            if (Math.random() > 0.85) throw new Error("Mail delivery subsystem error");
            return true;
        }
    },
    image: {
        optimize: async (postId) => {
            console.log(`[SERVICE.IMAGE] Optimizing image for post ${postId}`);
            await new Promise(r => setTimeout(r, 1800));
            return { optimizedUrl: `https://cdn.example.com/${postId}.jpg` };
        }
    },
    analytics: {
        generateReport: async (params) => {
            console.log(`[SERVICE.ANALYTICS] Generating report for period: ${params.period}`);
            await new Promise(r => setTimeout(r, 6000));
            console.log(`[SERVICE.ANALYTICS] Report complete.`);
            return { reportUrl: `https://reports.example.com/${uuidv4()}.pdf` };
        }
    }
};

// --- Central Job Configuration ---
const JOB_CONFIG = {
    'user-notifications': {
        processors: {
            'send-activation-email': async (job) => {
                const user = await mockDatabase.fetchUser(job.data.userId);
                if (!user) throw new Error(`User ${job.data.userId} not found`);
                await mockServices.email.send(user.email, 'Activate Your Account', 'Please click here...');
            }
        },
        defaultJobOptions: {
            attempts: 3,
            backoff: { type: 'exponential', delay: 3000 },
        }
    },
    'media-processing': {
        processors: {
            'process-post-header-image': async (job) => {
                const post = await mockDatabase.fetchPost(job.data.postId);
                if (!post) throw new Error(`Post ${job.data.postId} not found`);
                await mockServices.image.optimize(post.id);
            }
        },
        defaultJobOptions: {
            attempts: 2,
            backoff: { type: 'fixed', delay: 15000 },
        }
    },
    'system-tasks': {
        processors: {
            'generate-weekly-digest': async (job) => {
                await mockServices.analytics.generateReport({ period: 'weekly' });
            }
        },
        periodic: [
            {
                name: 'generate-weekly-digest',
                data: { timestamp: Date.now() },
                options: { repeat: { cron: '0 4 * * SUN' } } // 4 AM every Sunday
            }
        ]
    }
};

// --- Generic Queue & Worker Factories ---
function createQueueManager(config, connection) {
    const queues = {};
    for (const queueName in config) {
        queues[queueName] = new Queue(queueName, {
            connection,
            defaultJobOptions: config[queueName].defaultJobOptions || {}
        });
    }
    return queues;
}

function createAndRunWorkers(config, connection) {
    const workers = [];
    const schedulers = [];
    for (const queueName in config) {
        const queueConfig = config[queueName];
        if (queueConfig.processors) {
            const worker = new Worker(queueName, async (job) => {
                const processor = queueConfig.processors[job.name];
                if (!processor) throw new Error(`No processor for ${job.name} in queue ${queueName}`);
                console.log(`[WORKER:${queueName}] Executing job '${job.name}' (ID: ${job.id})`);
                await processor(job);
            }, { connection });

            worker.on('failed', (job, err) => console.error(`[WORKER:${queueName}] Job ${job.id} failed: ${err.message}`));
            workers.push(worker);
        }
        if (queueConfig.periodic) {
            schedulers.push(new QueueScheduler(queueName, { connection }));
        }
    }
    return { workers, schedulers };
}

async function schedulePeriodicJobs(queues, config) {
    for (const queueName in config) {
        const queueConfig = config[queueName];
        if (queueConfig.periodic) {
            const queue = queues[queueName];
            for (const periodicJob of queueConfig.periodic) {
                await queue.add(periodicJob.name, periodicJob.data, periodicJob.options);
                console.log(`Scheduled periodic job '${periodicJob.name}' on queue '${queueName}'`);
            }
        }
    }
}

// --- Main Application Setup ---
async function main() {
    const fastify = Fastify({ logger: { level: 'info' } });
    const connection = new IORedis('redis://127.0.0.1:6379', { maxRetriesPerRequest: null });

    // Create infrastructure from config
    const queues = createQueueManager(JOB_CONFIG, connection);
    const { workers, schedulers } = createAndRunWorkers(JOB_CONFIG, connection);
    await schedulePeriodicJobs(queues, JOB_CONFIG);

    // --- API Routes ---
    fastify.post('/users', async (request, reply) => {
        const { email, password } = request.body;
        const user = { id: uuidv4(), email, password_hash: `...`, role: 'USER', is_active: false, created_at: new Date() };
        await mockDatabase.persistUser(user);
        const job = await queues['user-notifications'].add('send-activation-email', { userId: user.id });
        return reply.status(201).send({ userId: user.id, jobId: job.id, queue: 'user-notifications' });
    });

    fastify.post('/posts', async (request, reply) => {
        const { userId, title, content } = request.body;
        const post = { id: uuidv4(), user_id: userId, title, content, status: 'PUBLISHED' };
        await mockDatabase.persistPost(post);
        const job = await queues['media-processing'].add('process-post-header-image', { postId: post.id });
        return reply.status(201).send({ postId: post.id, jobId: job.id, queue: 'media-processing' });
    });

    fastify.get('/jobs/:queueName/:jobId', async (request, reply) => {
        const { queueName, jobId } = request.params;
        const queue = queues[queueName];
        if (!queue) return reply.status(404).send({ error: 'Queue not found' });
        const job = await queue.getJob(jobId);
        if (!job) return reply.status(404).send({ error: 'Job not found' });
        return {
            id: job.id,
            name: job.name,
            state: await job.getState(),
            failedReason: job.failedReason,
            timestamp: new Date(job.timestamp).toISOString(),
        };
    });

    // --- Start Server ---
    try {
        await fastify.listen({ port: 3000 });
    } catch (err) {
        fastify.log.error(err);
        await Promise.all(workers.map(w => w.close()));
        await Promise.all(schedulers.map(s => s.close()));
        await Promise.all(Object.values(queues).map(q => q.close()));
        await connection.quit();
        process.exit(1);
    }
}

main();