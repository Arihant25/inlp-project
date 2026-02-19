<script>
// Variation 3: Event-Driven Architecture

// A simple, dependency-free event emitter
const event_emitter = {
    _events: {},
    on(event, listener) {
        if (!this._events[event]) {
            this._events[event] = [];
        }
        this._events[event].push(listener);
    },
    emit(event, ...args) {
        if (!this._events[event]) return;
        for (const listener of this._events[event]) {
            listener(...args);
        }
    }
};

// --- Mock Domain Data ---
const mock_db = {
    users: new Map([
        ['a1b2c3d4-e5f6-7890-1234-567890abcdef', { id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef', email: 'admin@example.com', role: 'ADMIN', is_active: true }],
        ['b2c3d4e5-f6a7-8901-2345-67890abcdef0', { id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0', email: 'user@example.com', role: 'USER', is_active: true }],
    ]),
    posts: new Map()
};

// --- Component: Job Status Tracker ---
const job_status_tracker = {
    statuses: new Map(),
    init() {
        event_emitter.on('job:new', (job) => {
            this.statuses.set(job.id, { ...job, status: 'PENDING', history: [{ status: 'PENDING', at: new Date() }] });
            console.log(`[Tracker] New job ${job.id} registered.`);
        });
        event_emitter.on('job:update', (job_id, status, data) => {
            const record = this.statuses.get(job_id);
            if (record) {
                Object.assign(record, { status, ...data });
                record.history.push({ status, at: new Date() });
            }
        });
    },
    get_status(job_id) {
        return this.statuses.get(job_id);
    }
};

// --- Component: Task Handlers ---
const task_handlers = {
    async SEND_PASSWORD_RESET_EMAIL(payload) {
        const user = mock_db.users.get(payload.user_id);
        if (!user) throw new Error(`User not found: ${payload.user_id}`);
        console.log(`   [Handler] Sending password reset to ${user.email}...`);
        await new Promise(res => setTimeout(res, 1200));
        if (Math.random() > 0.5) throw new Error("Mail server timeout");
        return { message_id: `msg_${Date.now()}` };
    },
    async PROCESS_POST_IMAGE(payload) {
        console.log(`   [Handler] Processing image for post ${payload.post_id}...`);
        await new Promise(res => setTimeout(res, 2000));
        return { processed_url: `cdn.com/${payload.post_id}.jpg` };
    },
    async AUDIT_LOG_CLEANUP(payload) {
        console.log(`   [Handler] Periodic task: Cleaning audit logs older than ${payload.days} days.`);
        await new Promise(res => setTimeout(res, 800));
        return { logs_deleted: Math.floor(Math.random() * 100) };
    }
};

// --- Component: Job Queue & Worker ---
const job_worker = {
    queue: [],
    max_retries: 3,
    base_backoff_ms: 500,
    
    init() {
        event_emitter.on('job:enqueue', (job) => {
            this.queue.push(job);
            event_emitter.emit('job:new', job);
        });
        this._start_worker_loop();
        this._start_scheduler();
    },

    async _process_next_job() {
        if (this.queue.length === 0) return;
        
        const job = this.queue.shift();
        event_emitter.emit('job:update', job.id, 'RUNNING', { attempts: job.attempts });
        
        try {
            const handler = task_handlers[job.type];
            if (!handler) throw new Error(`Unknown job type: ${job.type}`);
            
            const result = await handler(job.payload);
            event_emitter.emit('job:update', job.id, 'COMPLETED', { result });
            event_emitter.emit('job:success', job.id, result);
        } catch (error) {
            const new_attempts = job.attempts + 1;
            event_emitter.emit('job:failure', job.id, error.message, new_attempts);

            if (new_attempts < this.max_retries) {
                const delay = this.base_backoff_ms * (2 ** new_attempts);
                event_emitter.emit('job:update', job.id, 'RETRYING', { attempts: new_attempts, error: error.message });
                setTimeout(() => {
                    this.queue.push({ ...job, attempts: new_attempts });
                }, delay);
            } else {
                event_emitter.emit('job:update', job.id, 'FAILED', { error: error.message });
            }
        }
    },

    _start_worker_loop() {
        setInterval(() => this._process_next_job(), 1000);
    },

    _start_scheduler() {
        setInterval(() => {
            console.log('[Scheduler] Enqueuing periodic audit log cleanup.');
            const job = {
                id: crypto.randomUUID(),
                type: 'AUDIT_LOG_CLEANUP',
                payload: { days: 90 },
                attempts: 0
            };
            event_emitter.emit('job:enqueue', job);
        }, 15000);
    }
};

// --- Main Application Logic ---
function create_job(type, payload) {
    const job = {
        id: crypto.randomUUID(),
        type,
        payload,
        attempts: 0
    };
    event_emitter.emit('job:enqueue', job);
    return job.id;
}

function run_demo() {
    console.log("--- Starting Event-Driven Demo ---");
    
    // Initialize components
    job_status_tracker.init();
    job_worker.init();

    // Setup a simple logger that listens to events
    event_emitter.on('job:success', (id, res) => console.log(`[Logger] Job ${id} succeeded. Result:`, res));
    event_emitter.on('job:failure', (id, err, att) => console.error(`[Logger] Job ${id} failed on attempt ${att}. Error: ${err}`));

    // Create some jobs
    const email_job_id = create_job('SEND_PASSWORD_RESET_EMAIL', { user_id: 'b2c3d4e5-f6a7-8901-2345-67890abcdef0' });
    const image_job_id = create_job('PROCESS_POST_IMAGE', { post_id: 'post-abc-123' });

    // Check status after a few seconds
    setTimeout(() => {
        console.log("\n--- Checking Job Statuses via Tracker ---");
        console.log("Email Job:", job_status_tracker.get_status(email_job_id));
        console.log("Image Job:", job_status_tracker.get_status(image_job_id));
    }, 10000);
}

run_demo();
</script>