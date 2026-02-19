<script>
// Variation 2: Object-Oriented (OOP) Approach with Classes

class Job {
    constructor(type, payload) {
        this.id = crypto.randomUUID ? crypto.randomUUID() : ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g, c => (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16));
        this.type = type;
        this.payload = payload;
        this.status = 'PENDING'; // PENDING, RUNNING, COMPLETED, FAILED, RETRYING
        this.attempts = 0;
        this.createdAt = new Date();
        this.updatedAt = new Date();
        this.result = null;
        this.error = null;
    }

    updateStatus(status, data = {}) {
        this.status = status;
        this.updatedAt = new Date();
        Object.assign(this, data);
        console.log(`[Job ${this.id}] Status changed to ${this.status}`);
    }

    incrementAttempts() {
        this.attempts += 1;
    }
}

class TaskQueue {
    // --- Mock Domain Data ---
    static mockUsers = new Map([
        ['a1b2c3d4-e5f6-7890-1234-567890abcdef', { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', role: 'ADMIN', is_active: true }],
        ['b2c3d4e5-f6a7-8901-2345-67890abcdef0', { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user@example.com', role: 'USER', is_active: true }],
    ]);
    static mockPosts = new Map();

    constructor(options = {}) {
        this.queue = [];
        this.jobStore = new Map();
        this.taskHandlers = {};
        this.maxRetries = options.maxRetries || 5;
        this.backoffBase = options.backoffBase || 200;
        this.workerIntervalId = null;
        this.schedulerIntervalId = null;
    }

    registerTask(type, handler) {
        this.taskHandlers[type] = handler;
    }

    enqueue(type, payload) {
        const job = new Job(type, payload);
        this.queue.push(job);
        this.jobStore.set(job.id, job);
        console.log(`[TaskQueue] Enqueued job ${job.id} (${type}). Queue size: ${this.queue.length}`);
        return job.id;
    }

    getJobStatus(jobId) {
        const job = this.jobStore.get(jobId);
        if (!job) return null;
        // Return a plain object to prevent mutation of the original job object
        return { ...job };
    }

    async _processQueue() {
        if (this.queue.length === 0) {
            return;
        }

        const job = this.queue.shift();
        job.updateStatus('RUNNING');

        try {
            const handler = this.taskHandlers[job.type];
            if (!handler) {
                throw new Error(`Handler for type "${job.type}" not registered.`);
            }
            const result = await handler(job.payload);
            job.updateStatus('COMPLETED', { result });
        } catch (error) {
            job.incrementAttempts();
            console.error(`[Worker] Job ${job.id} failed on attempt ${job.attempts}. Error: ${error.message}`);

            if (job.attempts < this.maxRetries) {
                const delay = this.backoffBase * Math.pow(2, job.attempts);
                job.updateStatus('RETRYING', { error: error.message });
                console.log(`[Worker] Retrying job ${job.id} in ${delay}ms.`);
                setTimeout(() => this.queue.unshift(job), delay); // Add back to the front for priority retry
            } else {
                job.updateStatus('FAILED', { error: error.message });
                console.error(`[Worker] Job ${job.id} reached max retries and failed permanently.`);
            }
        }
    }

    start() {
        if (this.workerIntervalId) return;
        console.log("TaskQueue worker started.");
        this.workerIntervalId = setInterval(() => this._processQueue(), 1000);
        this._startPeriodicScheduler();
    }

    stop() {
        if (!this.workerIntervalId) return;
        clearInterval(this.workerIntervalId);
        clearInterval(this.schedulerIntervalId);
        this.workerIntervalId = null;
        this.schedulerIntervalId = null;
        console.log("TaskQueue worker stopped.");
    }

    _startPeriodicScheduler() {
        // Schedule a task every 12 seconds for demonstration
        this.schedulerIntervalId = setInterval(() => {
            console.log('[Scheduler] Adding periodic cleanup job to the queue.');
            this.enqueue('CLEANUP_OLD_DRAFTS', { olderThanDays: 30 });
        }, 12000);
    }
}

// --- Demo Execution ---
async function runDemo() {
    console.log("--- Starting OOP/Class-based Demo ---");
    const taskQueue = new TaskQueue();

    // Register task handlers
    taskQueue.registerTask('SEND_CONFIRMATION_EMAIL', async (payload) => {
        const user = TaskQueue.mockUsers.get(payload.userId);
        if (!user) throw new Error(`User ${payload.userId} not found.`);
        console.log(`[Handler] Sending confirmation email to ${user.email}`);
        await new Promise(resolve => setTimeout(resolve, 800));
        // Simulate a transient error
        if (Math.random() > 0.6) throw new Error("Email service unavailable");
        return { deliveryStatus: 'OK' };
    });

    taskQueue.registerTask('PROCESS_POST_IMAGE', async (payload) => {
        console.log(`[Handler] Processing image for post ${payload.postId}`);
        await new Promise(resolve => setTimeout(resolve, 1500));
        console.log(`[Handler] -> Resized and watermarked.`);
        return { cdnUrl: `https://cdn.example.com/processed/${payload.postId}.webp` };
    });

    taskQueue.registerTask('CLEANUP_OLD_DRAFTS', async (payload) => {
        console.log(`[Handler] Cleaning up drafts older than ${payload.olderThanDays} days.`);
        await new Promise(resolve => setTimeout(resolve, 500));
        return { cleanedCount: 0 };
    });

    // Start the queue processor
    taskQueue.start();

    // Enqueue some jobs
    const emailJobId = taskQueue.enqueue('SEND_CONFIRMATION_EMAIL', { userId: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0' });
    const imageJobId = taskQueue.enqueue('PROCESS_POST_IMAGE', { postId: 'post-uuid-1234' });
    const invalidJobId = taskQueue.enqueue('NON_EXISTENT_TASK', {});

    // Periodically check statuses
    const statusChecker = setInterval(() => {
        console.log("\n--- Checking Job Statuses ---");
        const emailJob = taskQueue.getJobStatus(emailJobId);
        const imageJob = taskQueue.getJobStatus(imageJobId);
        
        console.log(`Email Job: ${emailJob.status}, Attempts: ${emailJob.attempts}`);
        console.log(`Image Job: ${imageJob.status}, Attempts: ${imageJob.attempts}`);

        if ((emailJob.status === 'COMPLETED' || emailJob.status === 'FAILED') && imageJob.status === 'COMPLETED') {
            console.log("\nAll initial jobs have finished. Stopping demo.");
            taskQueue.stop();
            clearInterval(statusChecker);
        }
    }, 4000);
}

runDemo();
</script>