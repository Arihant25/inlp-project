<script>
// Variation 1: Functional Approach with a Singleton-like Module (IIFE)

const BackgroundJobManager = (() => {
    // --- Private State ---
    const jobQueue = [];
    const jobStatusStore = new Map();
    const MAX_RETRIES = 5;
    const INITIAL_BACKOFF_MS = 100;

    // --- Mock Domain Data & API ---
    const mockUsers = new Map([
        ['a1b2c3d4-e5f6-7890-1234-567890abcdef', { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', role: 'ADMIN', is_active: true }],
        ['b2c3d4e5-f6a7-8901-2345-67890abcdef0', { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user@example.com', role: 'USER', is_active: true }],
    ]);

    const mockPosts = new Map();

    // --- Private Helper Functions ---
    const generateUUID = () => crypto.randomUUID ? crypto.randomUUID() : ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g, c => (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16));

    const updateJobStatus = (jobId, status, extraData = {}) => {
        const existingStatus = jobStatusStore.get(jobId) || {};
        jobStatusStore.set(jobId, { ...existingStatus, status, ...extraData, updatedAt: new Date().toISOString() });
        console.log(`[Status Update] Job ${jobId} is now ${status}`);
    };

    // --- Task Handlers ---
    const taskHandlers = {
        SEND_WELCOME_EMAIL: async (payload) => {
            const { userId } = payload;
            const user = mockUsers.get(userId);
            if (!user) throw new Error(`User with ID ${userId} not found.`);

            console.log(`[Email Task] Sending welcome email to ${user.email}...`);
            // Simulate network latency
            await new Promise(resolve => setTimeout(resolve, 1000));

            // Simulate a transient failure for demonstration
            if (Math.random() > 0.5) {
                throw new Error("SMTP server connection failed.");
            }

            console.log(`[Email Task] Successfully sent welcome email to ${user.email}.`);
            return { sent: true, email: user.email };
        },
        PROCESS_POST_IMAGE: async (payload) => {
            const { postId, imageUrl } = payload;
            console.log(`[Image Task] Processing image ${imageUrl} for post ${postId}...`);
            
            // Simulate image processing steps
            await new Promise(resolve => setTimeout(resolve, 500));
            console.log(`[Image Task] -> Resizing image...`);
            await new Promise(resolve => setTimeout(resolve, 500));
            console.log(`[Image Task] -> Applying watermark...`);
            await new Promise(resolve => setTimeout(resolve, 500));
            console.log(`[Image Task] -> Uploading to CDN...`);
            
            const cdnUrl = `https://cdn.example.com/images/${postId}_processed.jpg`;
            console.log(`[Image Task] Image processing complete. CDN URL: ${cdnUrl}`);
            return { cdnUrl };
        },
        GENERATE_ANALYTICS_REPORT: async (payload) => {
            console.log(`[Periodic Task] Generating analytics report for period: ${payload.period}...`);
            await new Promise(resolve => setTimeout(resolve, 2000));
            const report = { users: mockUsers.size, posts: mockPosts.size, generatedAt: new Date().toISOString() };
            console.log(`[Periodic Task] Analytics report generated.`, report);
            return { report };
        }
    };

    const processJob = async (job) => {
        updateJobStatus(job.id, 'RUNNING');
        try {
            const handler = taskHandlers[job.type];
            if (!handler) throw new Error(`No handler for job type ${job.type}`);
            
            const result = await handler(job.payload);
            updateJobStatus(job.id, 'COMPLETED', { result });
        } catch (error) {
            console.error(`[Worker] Job ${job.id} failed. Attempt ${job.attempts + 1}. Error: ${error.message}`);
            const newAttempts = job.attempts + 1;
            if (newAttempts < MAX_RETRIES) {
                const delay = INITIAL_BACKOFF_MS * Math.pow(2, newAttempts);
                updateJobStatus(job.id, 'RETRYING', { attempts: newAttempts, error: error.message });
                console.log(`[Worker] Re-queuing job ${job.id} to run in ${delay}ms.`);
                // Re-queue for a future attempt
                setTimeout(() => {
                    jobQueue.push({ ...job, attempts: newAttempts });
                }, delay);
            } else {
                updateJobStatus(job.id, 'FAILED', { attempts: newAttempts, error: error.message });
                console.error(`[Worker] Job ${job.id} has failed permanently after ${MAX_RETRIES} retries.`);
            }
        }
    };

    // --- Worker Loop ---
    const workerLoop = () => {
        if (jobQueue.length > 0) {
            const job = jobQueue.shift();
            processJob(job);
        }
        // Schedule the next check
        setTimeout(workerLoop, 500);
    };

    // --- Periodic Task Scheduler ---
    const startScheduler = () => {
        // Schedule a report to run every 10 seconds for demonstration
        setInterval(() => {
            console.log('[Scheduler] Scheduling periodic analytics report job.');
            addJob('GENERATE_ANALYTICS_REPORT', { period: 'last_10_seconds' });
        }, 10000);
    };

    // --- Public API ---
    const addJob = (type, payload) => {
        const jobId = generateUUID();
        const job = {
            id: jobId,
            type,
            payload,
            attempts: 0,
            createdAt: new Date().toISOString()
        };
        jobQueue.push(job);
        updateJobStatus(jobId, 'PENDING', { type, createdAt: job.createdAt });
        console.log(`[Queue] Added job ${jobId} of type ${type}. Queue size: ${jobQueue.length}`);
        return jobId;
    };

    const getJobStatus = (jobId) => {
        return jobStatusStore.get(jobId);
    };

    const start = () => {
        console.log("Background Job Manager started.");
        workerLoop();
        startScheduler();
    };

    return {
        start,
        addJob,
        getJobStatus
    };
})();

// --- Demo Execution ---
function runDemo() {
    console.log("--- Starting Functional/Module Demo ---");
    BackgroundJobManager.start();

    // Simulate user registration, triggering a welcome email
    const userId = 'b2c3d4e5-f6a7-8901-2345-67890abcdef0';
    const emailJobId = BackgroundJobManager.addJob('SEND_WELCOME_EMAIL', { userId });

    // Simulate a new post, triggering an image processing pipeline
    const postId = 'p1o2s3t4-i5d6-7890-1234-567890abcdef';
    const imageJobId = BackgroundJobManager.addJob('PROCESS_POST_IMAGE', { postId, imageUrl: '/uploads/my_vacation.jpg' });

    // Check job statuses after a while
    setTimeout(() => {
        console.log("\n--- Checking Job Statuses ---");
        console.log("Email Job Status:", BackgroundJobManager.getJobStatus(emailJobId));
        console.log("Image Job Status:", BackgroundJobManager.getJobStatus(imageJobId));
    }, 8000);
}

runDemo();
</script>