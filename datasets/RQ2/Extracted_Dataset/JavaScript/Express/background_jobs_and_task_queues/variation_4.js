<pre>
// Variation 4: The "Modern Minimalist" Developer
// Style: Functional, concise, using factory functions and a flatter structure.
// Focuses on clear data flow and minimal boilerplate.

/*
-- package.json --
{
  "name": "variation-4-minimalist",
  "version": "1.0.0",
  "description": "Minimalist Express and BullMQ with factories",
  "main": "index.js",
  "scripts": {
    "start": "node index.js"
  },
  "dependencies": {
    "express": "^4.18.2",
    "bullmq": "^4.12.3",
    "ioredis": "^5.3.2",
    "uuid": "^9.0.0"
  }
}

-- How to run --
1. Save this file as `index.js`.
2. Create `package.json` with the content above.
3. Run `npm install`.
4. Ensure Redis is running on localhost:6379.
5. Run `npm start`.
*/

const express = require('express');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- (File: config.js) ---
const config = {
    redis: { host: 'localhost', port: 6379, maxRetriesPerRequest: null },
    serverPort: 3004,
};

// --- (File: data.js) ---
const dataStore = {
    users: new Map(),
    posts: new Map(),
};

// --- (File: jobs/factories.js) ---
const createQueueAndScheduler = (name, connection) => {
    new QueueScheduler(name, { connection });
    return new Queue(name, { connection });
};

const createWorker = (name, processor, connection) => {
    const worker = new Worker(name, processor, { connection });
    worker.on('failed', (job, err) => {
        console.error(`Job ${job.id} in queue ${name} failed:`, err.message);
    });
    return worker;
};

// --- (File: jobs/handlers.js) ---
const jobHandlers = {
    handleWelcomeEmail: async (job) => {
        const { email } = job.data;
        console.log(`[TASK] Sending welcome email to ${email}`);
        await new Promise(r => setTimeout(r, 1000)); // Simulate API call
        if (Math.random() < 0.1) throw new Error("SMTP server unavailable");
        return { status: 'sent', time: new Date().toISOString() };
    },
    handleImageProcessing: async (job) => {
        const { postId } = job.data;
        console.log(`[TASK] Processing image for post ${postId}`);
        await job.log('Resizing image...');
        await new Promise(r => setTimeout(r, 750));
        await job.log('Applying watermark...');
        await new Promise(r => setTimeout(r, 750));
        await job.log('Uploading to storage...');
        await new Promise(r => setTimeout(r, 750));
        return { url: `https://cdn.example.com/images/${postId}.jpg` };
    },
    handleCleanup: async (job) => {
        console.log(`[TASK] Running periodic cleanup job...`);
        // e.g., delete old, inactive user accounts
        const inactiveUsers = Array.from(dataStore.users.values()).filter(u => !u.is_active).length;
        console.log(`[TASK] Found ${inactiveUsers} inactive users to prune.`);
        await new Promise(r => setTimeout(r, 2000));
        console.log('[TASK] Cleanup complete.');
    }
};

// --- (File: api/router.js) ---
const setupRoutes = (app, queues) => {
    app.post('/register', async (req, res) => {
        const { email, password } = req.body;
        const user = { id: uuidv4(), email, password, is_active: true };
        dataStore.users.set(user.id, user);

        const job = await queues.emailQueue.add('welcome-email', { email }, {
            attempts: 4,
            backoff: { type: 'exponential', delay: 3000 }
        });
        res.status(202).json({ message: 'Registration accepted.', jobId: job.id });
    });

    app.post('/publish', async (req, res) => {
        const { userId, title } = req.body;
        const post = { id: uuidv4(), userId, title };
        dataStore.posts.set(post.id, post);

        const job = await queues.imageQueue.add('process-image', { postId: post.id });
        res.status(202).json({ message: 'Post submitted for processing.', jobId: job.id });
    });

    app.get('/status/:queueName/:jobId', async (req, res) => {
        const { queueName, jobId } = req.params;
        const queue = queues[`${queueName}Queue`];
        if (!queue) return res.status(404).json({ error: 'Queue not found' });

        const job = await queue.getJob(jobId);
        if (!job) return res.status(404).json({ error: 'Job not found' });

        const logs = await queue.getJobLogs(jobId);
        res.json({
            id: job.id,
            state: await job.getState(),
            failedReason: job.failedReason,
            returnValue: job.returnvalue,
            logs: logs.logs,
        });
    });
};

// --- (File: index.js) ---
async function main() {
    // 1. Initialize Connections & App
    const app = express();
    app.use(express.json());
    const redisConn = new IORedis(config.redis);

    // 2. Create Queues
    const queues = {
        emailQueue: createQueueAndScheduler('emails', redisConn),
        imageQueue: createQueueAndScheduler('images', redisConn),
        systemQueue: createQueueAndScheduler('system', redisConn),
    };

    // 3. Create Workers
    createWorker('emails', jobHandlers.handleWelcomeEmail, redisConn);
    createWorker('images', jobHandlers.handleImageProcessing, redisConn);
    createWorker('system', jobHandlers.handleCleanup, redisConn);
    console.log('Workers are ready.');

    // 4. Setup API Routes
    setupRoutes(app, queues);

    // 5. Schedule Periodic Jobs
    await queues.systemQueue.add('periodic-cleanup', {}, {
        repeat: { pattern: '0 4 * * *' }, // Every day at 4 AM
        jobId: 'global-cleanup'
    });
    console.log('Periodic jobs scheduled.');

    // 6. Start Server
    app.listen(config.serverPort, () => {
        console.log(`Minimalist server running on http://localhost:${config.serverPort}`);
    });
}

main().catch(console.error);
</pre>