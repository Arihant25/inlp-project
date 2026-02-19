<pre>
// Variation 1: The "Pragmatic Monolith" Developer
// Style: Functional, all-in-one file. Logic is co-located with routes.
// All necessary code, including package.json, is in this single block.

/*
-- package.json --
{
  "name": "variation-1-monolith",
  "version": "1.0.0",
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
1. Save the content of this file as `server.js`.
2. Create a `package.json` with the content above.
3. Run `npm install`.
4. Make sure a Redis server is running on localhost:6379.
5. Run `npm start`.
*/

const express = require('express');
const { Queue, Worker, QueueScheduler } = require('bullmq');
const IORedis = require('ioredis');
const { v4: uuidv4 } = require('uuid');

// --- Configuration ---
const PORT = 3001;
const REDIS_CONNECTION_OPTS = {
  host: 'localhost',
  port: 6379,
  maxRetriesPerRequest: null
};

// --- Mock Database & Services ---
const db = {
  users: new Map(),
  posts: new Map(),
};

const mockEmailService = {
  send: async (to, subject, body) => {
    console.log(`--- Sending Email ---`);
    console.log(`To: ${to}`);
    console.log(`Subject: ${subject}`);
    // Simulate a network delay and potential failure
    await new Promise(resolve => setTimeout(resolve, 1500));
    if (Math.random() > 0.8) { // 20% chance of failure
        throw new Error("Failed to connect to SMTP server");
    }
    console.log(`Email to ${to} sent successfully.`);
    console.log(`---------------------`);
  },
};

const mockImageProcessor = {
    resize: async (buffer) => {
        console.log('[Image Pipeline] Resizing image...');
        await new Promise(res => setTimeout(res, 1000));
        return 'resized_buffer';
    },
    watermark: async (buffer) => {
        console.log('[Image Pipeline] Adding watermark...');
        await new Promise(res => setTimeout(res, 1000));
        return 'watermarked_buffer';
    },
    upload: async (buffer) => {
        console.log('[Image Pipeline] Uploading to S3...');
        await new Promise(res => setTimeout(res, 1000));
        const url = `https://s3.amazonaws.com/bucket/${uuidv4()}.jpg`;
        console.log(`[Image Pipeline] Upload complete: ${url}`);
        return url;
    }
};


// --- Redis Connection & Queue Setup ---
const redisConnection = new IORedis(REDIS_CONNECTION_OPTS);

// It's a good practice to have a scheduler for repeatable jobs and delayed jobs.
new QueueScheduler('emailQueue', { connection: redisConnection });
new QueueScheduler('imageProcessingQueue', { connection: redisConnection });
new QueueScheduler('reportingQueue', { connection: redisConnection });

const emailQueue = new Queue('emailQueue', { connection: redisConnection });
const imageProcessingQueue = new Queue('imageProcessingQueue', { connection: redisConnection });
const reportingQueue = new Queue('reportingQueue', { connection: redisConnection });

// --- Worker Definitions ---
const emailWorker = new Worker('emailQueue', async job => {
  const { to, subject, body } = job.data;
  console.log(`Processing job ${job.id} from emailQueue: Sending email to ${to}`);
  await mockEmailService.send(to, subject, body);
}, { connection: redisConnection });

const imageWorker = new Worker('imageProcessingQueue', async job => {
    const { postId, imageBuffer } = job.data;
    console.log(`Processing job ${job.id} from imageProcessingQueue for post ${postId}`);
    const resized = await mockImageProcessor.resize(imageBuffer);
    const watermarked = await mockImageProcessor.watermark(resized);
    const finalUrl = await mockImageProcessor.upload(watermarked);
    
    // In a real app, you'd update the post in the DB with the finalUrl
    console.log(`Image processing for post ${postId} complete. URL: ${finalUrl}`);
}, { connection: redisConnection });

const reportingWorker = new Worker('reportingQueue', async job => {
    console.log(`\n--- Generating Daily Report (Job ${job.id}) ---`);
    console.log(`Timestamp: ${new Date().toISOString()}`);
    console.log(`Total Users: ${db.users.size}`);
    console.log(`Total Posts: ${db.posts.size}`);
    console.log(`--- Report Generation Complete ---\n`);
}, { connection: redisConnection });

// --- Schedule Periodic Task ---
const scheduleDailyReport = async () => {
    await reportingQueue.add('daily-report', {}, {
        repeat: {
            cron: '*/1 * * * *' // Every minute for demonstration
        },
        jobId: 'daily-system-report' // Prevents duplicate cron jobs on restart
    });
    console.log('Daily reporting job scheduled.');
};

// --- Express App ---
const app = express();
app.use(express.json());

// --- API Routes ---
app.post('/users/register', async (req, res) => {
  const { email, password } = req.body;
  if (!email || !password) {
    return res.status(400).json({ error: 'Email and password are required.' });
  }

  const newUser = {
    id: uuidv4(),
    email,
    password_hash: `hashed_${password}`,
    role: 'USER',
    is_active: true,
    created_at: new Date()
  };
  db.users.set(newUser.id, newUser);

  // Add email job to the queue with retry logic
  const job = await emailQueue.add('send-welcome-email', {
    to: newUser.email,
    subject: 'Welcome to Our Platform!',
    body: 'Thanks for signing up.'
  }, {
    attempts: 3,
    backoff: {
      type: 'exponential',
      delay: 5000, // 5s, 10s, 20s
    }
  });

  res.status(201).json({ 
      message: 'User registered successfully. Welcome email is being sent.',
      userId: newUser.id,
      jobId: job.id
  });
});

app.post('/posts', async (req, res) => {
    const { userId, title, content } = req.body;
    if (!db.users.has(userId)) {
        return res.status(404).json({ error: 'User not found.' });
    }
    const newPost = {
        id: uuidv4(),
        user_id: userId,
        title,
        content,
        status: 'DRAFT'
    };
    db.posts.set(newPost.id, newPost);

    // Add image processing job to the queue
    const job = await imageProcessingQueue.add('process-post-image', {
        postId: newPost.id,
        imageBuffer: 'mock-image-data'
    });

    res.status(201).json({
        message: 'Post created. Associated image is being processed.',
        post: newPost,
        jobId: job.id
    });
});

app.get('/jobs/:queueName/:jobId', async (req, res) => {
    const { queueName, jobId } = req.params;
    let queue;
    switch(queueName) {
        case 'email': queue = emailQueue; break;
        case 'image': queue = imageProcessingQueue; break;
        case 'reporting': queue = reportingQueue; break;
        default: return res.status(404).json({ error: 'Queue not found' });
    }

    const job = await queue.getJob(jobId);
    if (!job) {
        return res.status(404).json({ error: 'Job not found' });
    }

    const state = await job.getState();
    const progress = job.progress;
    const returnValue = job.returnvalue;
    const failedReason = job.failedReason;

    res.json({
        id: job.id,
        name: job.name,
        data: job.data,
        state,
        progress,
        returnValue,
        failedReason,
        timestamp: new Date(job.timestamp).toISOString(),
    });
});

// --- Worker Event Listeners for Logging ---
emailWorker.on('completed', job => {
  console.log(`[emailQueue] Job ${job.id} completed successfully.`);
});
emailWorker.on('failed', (job, err) => {
  console.error(`[emailQueue] Job ${job.id} failed with error: ${err.message}`);
});
imageWorker.on('completed', job => {
  console.log(`[imageProcessingQueue] Job ${job.id} completed successfully.`);
});

// --- Start Server ---
app.listen(PORT, async () => {
  console.log(`Server running on http://localhost:${PORT}`);
  await scheduleDailyReport();
});
</pre>