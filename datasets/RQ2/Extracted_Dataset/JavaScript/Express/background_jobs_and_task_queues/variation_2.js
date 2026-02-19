<pre>
// Variation 2: The "OOP Architect" Developer
// Style: Class-based, object-oriented with clear separation of concerns.
// This variation splits logic into services, controllers, and a main App class.

/*
-- package.json --
{
  "name": "variation-2-oop",
  "version": "1.0.0",
  "description": "OOP-based Express and BullMQ implementation",
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
1. Create a directory for the project.
2. Save this entire code block as `server.js` inside that directory.
3. Create a `package.json` with the content above in the same directory.
4. Run `npm install`.
5. Make sure a Redis server is running on localhost:6379.
6. Run `npm start`.
*/

const express = require('express');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- Mock Data Store ---
class MockDB {
    constructor() {
        this.users = new Map();
        this.posts = new Map();
    }
}
const db = new MockDB();

// --- Mock External Services ---
class MockEmailer {
    async send(to, subject, body) {
        console.log(`[EmailService] Preparing to send email to ${to}`);
        await new Promise(resolve => setTimeout(resolve, 1000));
        console.log(`[EmailService] Email sent to ${to} with subject "${subject}"`);
    }
}

class MockImagePipeline {
    async process(imageBuffer) {
        console.log(`[ImageService] Starting image processing pipeline...`);
        await new Promise(res => setTimeout(res, 500));
        console.log(`[ImageService] > Resized`);
        await new Promise(res => setTimeout(res, 500));
        console.log(`[ImageService] > Watermarked`);
        await new Promise(res => setTimeout(res, 500));
        console.log(`[ImageService] > Uploaded to cloud`);
        return `https://cdn.example.com/${uuidv4()}.png`;
    }
}

// --- Queue Management Service ---
class QueueService {
    constructor(connection) {
        this.connection = connection;
        this.queues = {
            email: new Queue('email', { connection }),
            image: new Queue('image', { connection }),
            system: new Queue('system', { connection }),
        };
        // Schedulers are needed for repeatable jobs
        new QueueScheduler('email', { connection });
        new QueueScheduler('image', { connection });
        new QueueScheduler('system', { connection });
    }

    getQueue(name) {
        return this.queues[name];
    }

    async addEmailJob(data) {
        return this.queues.email.add('send-welcome-email', data, {
            attempts: 3,
            backoff: { type: 'exponential', delay: 1000 },
        });
    }

    async addImageJob(data) {
        return this.queues.image.add('process-post-image', data);
    }

    async scheduleSystemReport() {
        await this.queues.system.add('daily-report', {}, {
            repeat: { cron: '0 1 * * *' }, // 1 AM daily
            jobId: 'singleton-daily-report'
        });
        console.log('System report job has been scheduled.');
    }
}

// --- Job Processing Logic ---
class JobProcessor {
    constructor() {
        this.emailer = new MockEmailer();
        this.imagePipeline = new MockImagePipeline();
    }

    processEmailJob = async (job) => {
        console.log(`Processing email job ${job.id}`);
        const { to, subject, body } = job.data;
        await this.emailer.send(to, subject, body);
    }

    processImageJob = async (job) => {
        console.log(`Processing image job ${job.id}`);
        const { postId, imageBuffer } = job.data;
        const url = await this.imagePipeline.process(imageBuffer);
        console.log(`Image for post ${postId} processed. URL: ${url}`);
    }

    processSystemReportJob = async (job) => {
        console.log(`\nProcessing system report job ${job.id}`);
        console.log(`Users count: ${db.users.size}`);
        console.log(`Posts count: ${db.posts.size}`);
        console.log('Report finished.\n');
    }
}

// --- API Controllers ---
class UserController {
    constructor(queueService) {
        this.queueService = queueService;
    }

    register = async (req, res) => {
        const { email, password } = req.body;
        const newUser = { id: uuidv4(), email, password_hash: '...' };
        db.users.set(newUser.id, newUser);

        const job = await this.queueService.addEmailJob({
            to: email,
            subject: 'Welcome!',
            body: 'Thank you for registering.'
        });

        res.status(201).json({ user: newUser, jobId: job.id });
    }
}

class PostController {
    constructor(queueService) {
        this.queueService = queueService;
    }

    create = async (req, res) => {
        const { userId, title } = req.body;
        const newPost = { id: uuidv4(), userId, title, status: 'DRAFT' };
        db.posts.set(newPost.id, newPost);

        const job = await this.queueService.addImageJob({
            postId: newPost.id,
            imageBuffer: 'mock-buffer'
        });

        res.status(201).json({ post: newPost, jobId: job.id });
    }
}

class JobStatusController {
    constructor(queueService) {
        this.queueService = queueService;
    }
    
    getStatus = async (req, res) => {
        const { queueName, jobId } = req.params;
        const queue = this.queueService.getQueue(queueName);
        if (!queue) {
            return res.status(404).send({ error: 'Queue not found' });
        }
        const job = await queue.getJob(jobId);
        if (!job) {
            return res.status(404).send({ error: 'Job not found' });
        }
        res.json({
            id: job.id,
            state: await job.getState(),
            progress: job.progress,
            failedReason: job.failedReason,
            returnValue: job.returnvalue,
        });
    }
}

// --- Main Application Class ---
class App {
    constructor() {
        this.expressApp = express();
        this.redisConnection = new IORedis({ maxRetriesPerRequest: null });
        this.queueService = new QueueService(this.redisConnection);
        this.jobProcessor = new JobProcessor();
        this.port = 3002;
    }

    _setupMiddleware() {
        this.expressApp.use(express.json());
    }

    _setupRoutes() {
        const userController = new UserController(this.queueService);
        const postController = new PostController(this.queueService);
        const jobStatusController = new JobStatusController(this.queueService);

        this.expressApp.post('/users', userController.register);
        this.expressApp.post('/posts', postController.create);
        this.expressApp.get('/jobs/:queueName/:jobId', jobStatusController.getStatus);
    }

    _setupWorkers() {
        new Worker('email', this.jobProcessor.processEmailJob, { connection: this.redisConnection });
        new Worker('image', this.jobProcessor.processImageJob, { connection: this.redisConnection });
        new Worker('system', this.jobProcessor.processSystemReportJob, { connection: this.redisConnection });
        console.log('Workers are listening for jobs.');
    }

    async start() {
        this._setupMiddleware();
        this._setupRoutes();
        this._setupWorkers();
        await this.queueService.scheduleSystemReport();

        this.expressApp.listen(this.port, () => {
            console.log(`OOP Architect server running on http://localhost:${this.port}`);
        });
    }
}

// --- Entry Point ---
const application = new App();
application.start();
</pre>