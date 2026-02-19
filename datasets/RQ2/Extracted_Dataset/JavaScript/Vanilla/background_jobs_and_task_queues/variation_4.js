<script>
// Variation 4: Promise-based, Modern Async/Await Syntax

const JobService = {
    // --- State ---
    JOB_QUEUE: [],
    JOB_STATUS_STORE: new Map(),
    JOB_COMPLETION_PROMISES: new Map(),
    IS_WORKER_RUNNING: false,
    MAX_RETRIES: 4,
    BASE_BACKOFF_MS: 250,

    // --- Mock Data ---
    mockData: {
        users: new Map([
            ['user-uuid-001', { id: 'user-uuid-001', email: 'test.user@example.com', role: 'USER', is_active: true }],
        ]),
        posts: new Map([
            ['post-uuid-123', { id: 'post-uuid-123', user_id: 'user-uuid-001', title: 'My First Post', status: 'DRAFT' }],
        ]),
    },

    // --- Core Methods ---
    async addJob(type, payload, { awaitCompletion = false } = {}) {
        const jobId = crypto.randomUUID();
        const job = {
            id: jobId,
            type,
            payload,
            status: 'PENDING',
            attempts: 0,
            createdAt: new Date().toISOString(),
        };

        this.JOB_QUEUE.push(job);
        this.JOB_STATUS_STORE.set(jobId, job);
        console.log(`[API] Job ${jobId} (${type}) has been enqueued.`);

        if (awaitCompletion) {
            const completionPromise = new Promise((resolve, reject) => {
                this.JOB_COMPLETION_PROMISES.set(jobId, { resolve, reject });
            });
            return completionPromise;
        }

        return jobId;
    },

    getJobStatus(jobId) {
        return this.JOB_STATUS_STORE.get(jobId);
    },

    // --- Worker Logic ---
    async start() {
        if (this.IS_WORKER_RUNNING) {
            console.warn("Worker is already running.");
            return;
        }
        this.IS_WORKER_RUNNING = true;
        console.log("Background worker started.");
        this._schedulePeriodicTasks();
        this._workerLoop();
    },

    stop() {
        this.IS_WORKER_RUNNING = false;
        console.log("Background worker stopped.");
    },

    async _workerLoop() {
        while (this.IS_WORKER_RUNNING) {
            const job = this.JOB_QUEUE.shift();
            if (job) {
                await this._executeJob(job);
            } else {
                // Sleep for a bit if the queue is empty
                await new Promise(resolve => setTimeout(resolve, 1000));
            }
        }
    },

    async _executeJob(job) {
        this._updateJobStatus(job, 'RUNNING');
        
        try {
            const handler = this.taskHandlers[job.type];
            if (!handler) throw new Error(`No handler found for type: ${job.type}`);
            
            const result = await handler(job.payload);
            this._updateJobStatus(job, 'COMPLETED', { result });
            this._resolveJobPromise(job.id, result);

        } catch (error) {
            console.error(`[Worker] Job ${job.id} failed on attempt ${job.attempts + 1}: ${error.message}`);
            job.attempts += 1;

            if (job.attempts < this.MAX_RETRIES) {
                const delay = this.BASE_BACKOFF_MS * Math.pow(2, job.attempts);
                this._updateJobStatus(job, 'RETRYING', { error: error.message });
                // Re-queue after a delay
                setTimeout(() => this.JOB_QUEUE.push(job), delay);
            } else {
                this._updateJobStatus(job, 'FAILED', { error: error.message });
                this._rejectJobPromise(job.id, new Error(`Job failed after ${this.MAX_RETRIES} attempts.`));
            }
        }
    },

    // --- Helper Methods ---
    _updateJobStatus(job, status, data = {}) {
        job.status = status;
        job.updatedAt = new Date().toISOString();
        Object.assign(job, data);
        console.log(`[Status] Job ${job.id} is now ${status}.`);
    },

    _resolveJobPromise(jobId, result) {
        if (this.JOB_COMPLETION_PROMISES.has(jobId)) {
            this.JOB_COMPLETION_PROMISES.get(jobId).resolve(result);
            this.JOB_COMPLETION_PROMISES.delete(jobId);
        }
    },

    _rejectJobPromise(jobId, error) {
        if (this.JOB_COMPLETION_PROMISES.has(jobId)) {
            this.JOB_COMPLETION_PROMISES.get(jobId).reject(error);
            this.JOB_COMPLETION_PROMISES.delete(jobId);
        }
    },

    _schedulePeriodicTasks() {
        setInterval(() => {
            this.addJob('PUBLISH_DRAFT_POSTS', { schedule: 'every_15_seconds' });
        }, 15000);
    },

    // --- Task Handlers ---
    taskHandlers: {
        SEND_WELCOME_EMAIL: async (payload) => {
            const user = JobService.mockData.users.get(payload.userId);
            if (!user) throw new Error("User not found");
            console.log(`   [Task] Sending welcome email to ${user.email}...`);
            await new Promise(r => setTimeout(r, 700));
            // Simulate a possible failure
            if (Math.random() > 0.5) throw new Error("SMTP server is busy");
            return { status: 'sent', to: user.email };
        },
        PROCESS_POST_IMAGE: async (payload) => {
            console.log(`   [Task] Processing image for post ${payload.postId}...`);
            await new Promise(r => setTimeout(r, 1500));
            console.log(`   [Task] -> Image resized, watermarked, and compressed.`);
            return { cdnUrl: `https://cdn.example.com/${payload.postId}/image.jpg` };
        },
        PUBLISH_DRAFT_POSTS: async (payload) => {
            console.log(`   [Task] Running periodic job: ${payload.schedule}`);
            let publishedCount = 0;
            JobService.mockData.posts.forEach(post => {
                if (post.status === 'DRAFT') {
                    post.status = 'PUBLISHED';
                    publishedCount++;
                }
            });
            console.log(`   [Task] -> Published ${publishedCount} draft posts.`);
            return { publishedCount };
        }
    }
};

// --- Demo Execution ---
async function runDemo() {
    console.log("--- Starting Async/Await Promise-based Demo ---");
    JobService.start();

    // --- Scenario 1: Fire-and-forget job ---
    console.log("\n[Scenario 1] Adding a fire-and-forget email job.");
    const emailJobId = await JobService.addJob('SEND_WELCOME_EMAIL', { userId: 'user-uuid-001' });

    // --- Scenario 2: Await job completion ---
    console.log("\n[Scenario 2] Adding an image processing job and awaiting its result.");
    try {
        const imageResult = await JobService.addJob('PROCESS_POST_IMAGE', { postId: 'post-uuid-123' }, { awaitCompletion: true });
        console.log(`[Main] Awaited job completed! Result:`, imageResult);
    } catch (error) {
        console.error(`[Main] Awaited job failed:`, error.message);
    }

    // Check status of the fire-and-forget job after a while
    setTimeout(() => {
        console.log("\n--- Checking status of fire-and-forget job ---");
        const status = JobService.getJobStatus(emailJobId);
        console.log(`Email Job Status:`, status);
    }, 8000);
    
    // The periodic job will run on its own.
    // Stop the worker after some time to end the demo.
    setTimeout(() => {
        JobService.stop();
    }, 20000);
}

runDemo();
</script>