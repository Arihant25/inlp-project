<pre>
// Variation 3: The "Service-Oriented" Developer
// Style: Functional, with strong separation of concerns by feature/domain.
// This variation uses a directory-like structure simulated in a single file.

/*
-- package.json --
{
  "name": "variation-3-service-oriented",
  "version": "1.0.0",
  "description": "Service-oriented Express and BullMQ implementation",
  "main": "server.js",
  "scripts": {
    "start": "node server.js"
  },
  "dependencies": {
    "express": "^4.18.2",
    "bullmq": "^4.12.3",
    "ioredis": "^5.3.2",
    "uuid": "^9.0.0"
  }
}

-- How to run --
1. Save this file as `server.js`.
2. Create `package.json` with the content above.
3. Run `npm install`.
4. Ensure Redis is running on localhost:6379.
5. Run `npm start`.
*/

const express = require('express');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- (File: config/redis.js) ---
const redisConnection = new IORedis({ host: 'localhost', port: 6379, maxRetriesPerRequest: null });

// --- (File: lib/queue.js) ---
const createQueue = (name) => {
    // A scheduler is required for repeatable jobs
    new QueueScheduler(name, { connection: redisConnection });
    return new Queue(name, { connection: redisConnection });
};
const emailQueue = createQueue('email_service');
const imageQueue = createQueue('image_service');
const reportQueue = createQueue('report_service');

// --- (File: lib/db.js) ---
const db = {
    users: new Map(),
    posts: new Map(),
};

// --- (File: features/users/user.service.js) ---
const userService = {
    createUser: ({ email, password }) => {
        const newUser = {
            id: uuidv4(),
            email,
            password_hash: `hash(${password})`,
            role: 'USER',
            is_active: false,
            created_at: new Date().toISOString()
        };
        db.users.set(newUser.id, newUser);
        return newUser;
    }
};

// --- (File: features/users/user.jobs.js) ---
const userJobs = {
    addWelcomeEmailJob: (user) => {
        return emailQueue.add('send-welcome-email', { userId: user.id, email: user.email }, {
            attempts: 5,
            backoff: { type: 'exponential', delay: 2000 }
        });
    },
    welcomeEmailProcessor: async (job) => {
        const { email } = job.data;
        console.log(`[Job Processor] Sending welcome email to ${email} (Job ID: ${job.id})`);
        await new Promise(res => setTimeout(res, 1200)); // Simulate email sending
        console.log(`[Job Processor] Welcome email for ${email} sent.`);
    }
};

// --- (File: features/users/user.controller.js) ---
const userController = {
    register: async (req, res) => {
        try {
            const user = userService.createUser(req.body);
            const job = await userJobs.addWelcomeEmailJob(user);
            res.status(201).json({
                message: "User created. A welcome email has been queued.",
                userId: user.id,
                jobId: job.id
            });
        } catch (error) {
            res.status(500).json({ error: "Failed to register user." });
        }
    }
};

// --- (File: features/posts/post.service.js) ---
const postService = {
    createPost: ({ userId, title, content }) => {
        if (!db.users.has(userId)) throw new Error("User not found");
        const newPost = {
            id: uuidv4(),
            user_id: userId,
            title,
            content,
            status: 'DRAFT'
        };
        db.posts.set(newPost.id, newPost);
        return newPost;
    }
};

// --- (File: features/posts/post.jobs.js) ---
const postJobs = {
    addImageProcessingJob: (post) => {
        return imageQueue.add('process-post-image', { postId: post.id, imageUrl: 'path/to/image.jpg' });
    },
    imageProcessor: async (job) => {
        const { postId } = job.data;
        console.log(`[Job Processor] Starting image pipeline for post ${postId} (Job ID: ${job.id})`);
        await job.updateProgress(25);
        await new Promise(res => setTimeout(res, 800)); // Simulate resize
        console.log(`[Job Processor] Image resized for post ${postId}`);
        await job.updateProgress(50);
        await new Promise(res => setTimeout(res, 800)); // Simulate watermark
        console.log(`[Job Processor] Watermark added for post ${postId}`);
        await job.updateProgress(100);
        console.log(`[Job Processor] Image pipeline for post ${postId} complete.`);
    }
};

// --- (File: features/posts/post.controller.js) ---
const postController = {
    create: async (req, res) => {
        try {
            const post = postService.createPost(req.body);
            const job = await postJobs.addImageProcessingJob(post);
            res.status(201).json({
                message: "Post created. Image processing job queued.",
                postId: post.id,
                jobId: job.id
            });
        } catch (error) {
            res.status(400).json({ error: error.message });
        }
    }
};

// --- (File: features/reporting/reporting.jobs.js) ---
const reportingJobs = {
    schedulePeriodicReports: () => {
        reportQueue.add('generate-monthly-summary', {}, {
            repeat: { cron: '0 0 1 * *' }, // 1st day of every month
            jobId: 'monthly-summary-report'
        });
        console.log('Monthly reporting job scheduled.');
    },
    reportProcessor: async (job) => {
        console.log(`\n[Job Processor] Generating monthly report (Job ID: ${job.id})`);
        // Simulate complex aggregation
        await new Promise(res => setTimeout(res, 3000));
        console.log(`[Job Processor] Report complete. Users: ${db.users.size}, Posts: ${db.posts.size}\n`);
    }
};

// --- (File: features/jobs/job.controller.js) ---
const jobStatusController = {
    getJobStatus: async (req, res) => {
        const { queueName, jobId } = req.params;
        const queues = { 'email': emailQueue, 'image': imageQueue, 'report': reportQueue };
        const queue = queues[queueName];
        if (!queue) return res.status(404).json({ error: "Queue not found" });

        const job = await queue.getJob(jobId);
        if (!job) return res.status(404).json({ error: "Job not found" });

        res.json({
            id: job.id,
            name: job.name,
            state: await job.getState(),
            progress: job.progress,
            failedReason: job.failedReason,
            finishedOn: job.finishedOn ? new Date(job.finishedOn) : null
        });
    }
};

// --- (File: workers.js) ---
const initializeWorkers = () => {
    new Worker('email_service', userJobs.welcomeEmailProcessor, { connection: redisConnection });
    new Worker('image_service', postJobs.imageProcessor, { connection: redisConnection });
    new Worker('report_service', reportingJobs.reportProcessor, { connection: redisConnection });
    console.log('All workers have been initialized.');
};

// --- (File: app.js) ---
const createApp = () => {
    const app = express();
    app.use(express.json());

    const userRouter = express.Router();
    userRouter.post('/register', userController.register);
    app.use('/users', userRouter);

    const postRouter = express.Router();
    postRouter.post('/', postController.create);
    app.use('/posts', postRouter);
    
    const jobRouter = express.Router();
    jobRouter.get('/:queueName/:jobId', jobStatusController.getJobStatus);
    app.use('/jobs', jobRouter);

    return app;
};

// --- (File: server.js) ---
const main = () => {
    const app = createApp();
    initializeWorkers();
    reportingJobs.schedulePeriodicReports();

    const PORT = 3003;
    app.listen(PORT, () => {
        console.log(`Service-Oriented server running on http://localhost:${PORT}`);
    });
};

main();
</pre>