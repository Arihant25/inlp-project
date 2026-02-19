// --- main.js ---
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3002);
  console.log(`Application is running on: ${await app.getUrl()}`);
}
bootstrap();


// --- app.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { ScheduleModule } from '@nestjs/schedule';
import { EventEmitterModule } from '@nestjs/event-emitter';
import { AppController } from './app.controller.js';
import { UserService } from './user.service.js';
import { PostService } from './post.service.js';
import { JobsModule } from './jobs/jobs.module.js';

@Module({
  imports: [
    ScheduleModule.forRoot(),
    EventEmitterModule.forRoot(),
    BullModule.forRoot({
      redis: { host: 'localhost', port: 6379 },
    }),
    JobsModule,
  ],
  controllers: [AppController],
  providers: [UserService, PostService],
})
export class AppModule {}


// --- app.controller.js ---
import { Controller, Post, Body } from '@nestjs/common';
import { UserService } from './user.service.js';
import { PostService } from './post.service.js';

@Controller('v3')
export class AppController {
  constructor(userService, postService) {
    this.userService = userService;
    this.postService = postService;
  }

  @Post('user/register')
  async registerUser(@Body() userDto) {
    const user = await this.userService.register(userDto);
    return { message: 'User registration initiated.', user };
  }

  @Post('post/publish')
  async publishPost(@Body() postDto) {
    const post = await this.postService.publish(postDto);
    return { message: 'Post publication process started.', post };
  }
}


// --- user.service.js ---
import { Injectable } from '@nestjs/common';
import { EventEmitter2 } from '@nestjs/event-emitter';
import { v4 as uuidv4 } from 'uuid';

@Injectable()
export class UserService {
  constructor(eventEmitter) {
    this.eventEmitter = eventEmitter;
  }

  async register(userDto) {
    const newUser = {
      id: uuidv4(),
      email: userDto.email,
      role: 'USER',
      is_active: true,
      created_at: new Date(),
    };
    // Business logic to save user would be here...
    this.eventEmitter.emit('user.created', { user: newUser });
    return newUser;
  }
}


// --- post.service.js ---
import { Injectable } from '@nestjs/common';
import { EventEmitter2 } from '@nestjs/event-emitter';
import { v4 as uuidv4 } from 'uuid';

@Injectable()
export class PostService {
  constructor(eventEmitter) {
    this.eventEmitter = eventEmitter;
  }

  async publish(postDto) {
    const newPost = {
      id: uuidv4(),
      user_id: postDto.userId,
      title: postDto.title,
      status: 'PUBLISHED',
    };
    // Business logic to save post would be here...
    this.eventEmitter.emit('post.published', { post: newPost });
    return newPost;
  }
}


// --- jobs/jobs.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { JobEventListener } from './job.event-listener.js';
import { JobProcessor } from './job.processor.js';
import { ScheduledJobService } from './scheduled-job.service.js';
import { MockMailService, MockImageService, MockDbService } from '../mocks/mock.services.js';

@Module({
  imports: [
    BullModule.registerQueue({ name: 'event-driven-queue' }),
  ],
  providers: [
    JobEventListener,
    JobProcessor,
    ScheduledJobService,
    MockMailService,
    MockImageService,
    MockDbService,
  ],
})
export class JobsModule {}


// --- jobs/job.event-listener.js ---
import { Injectable, Logger } from '@nestjs/common';
import { OnEvent } from '@nestjs/event-emitter';
import { InjectQueue } from '@nestjs/bull';

@Injectable()
export class JobEventListener {
  constructor(queue) {
    this.queue = queue;
  }

  @OnEvent('user.created')
  async handleUserCreatedEvent(payload) {
    Logger.log(`Event 'user.created' received. Adding 'send-welcome-email' job.`);
    await this.queue.add('send-welcome-email', payload);
  }

  @OnEvent('post.published')
  async handlePostPublishedEvent(payload) {
    Logger.log(`Event 'post.published' received. Adding 'process-post-assets' job.`);
    await this.queue.add('process-post-assets', payload, {
      attempts: 3,
      backoff: 5000, // 5 second delay between retries
    });
  }
}


// --- jobs/job.processor.js ---
import { Processor, Process, OnQueueProgress, OnQueueFailed } from '@nestjs/bull';
import { Logger } from '@nestjs/common';
import { MockMailService, MockImageService } from '../mocks/mock.services.js';

@Processor('event-driven-queue')
export class JobProcessor {
  #logger = new Logger(JobProcessor.name);

  constructor(mailService, imageService) {
    this.mailService = mailService;
    this.imageService = imageService;
  }

  @OnQueueProgress()
  onProgress(job, progress) {
    this.#logger.log(`Job ${job.id} is ${progress}% complete.`);
  }

  @OnQueueFailed()
  onFailed(job, error) {
    this.#logger.error(`Job ${job.id} failed: ${error.message}`);
  }

  @Process('send-welcome-email')
  async sendWelcomeEmail(job) {
    const { user } = job.data;
    await this.mailService.sendWelcome(user.email);
    return `Email sent to ${user.email}`;
  }

  @Process('process-post-assets')
  async processPostAssets(job) {
    const { post } = job.data;
    await job.progress(0);
    await this.imageService.optimize(post.id);
    await job.progress(50);
    await this.imageService.watermark(post.id);
    await job.progress(100);
    return `Assets processed for post ${post.id}`;
  }
}


// --- jobs/scheduled-job.service.js ---
import { Injectable, Logger } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { MockDbService } from '../mocks/mock.services.js';

@Injectable()
export class ScheduledJobService {
  constructor(dbService) {
    this.dbService = dbService;
  }

  @Cron(CronExpression.EVERY_HOUR)
  async runHourlyDbBackup() {
    Logger.log('Scheduled Task: Starting hourly database backup.');
    const result = await this.dbService.backup();
    Logger.log(`Scheduled Task: Database backup completed. File: ${result.file}`);
  }
}


// --- mocks/mock.services.js ---
import { Injectable, Logger } from '@nestjs/common';

@Injectable()
export class MockMailService {
  async sendWelcome(email) {
    Logger.log(`[MockMailService] Sending welcome email to ${email}`);
    return new Promise(res => setTimeout(res, 400));
  }
}

@Injectable()
export class MockImageService {
  async optimize(id) {
    Logger.log(`[MockImageService] Optimizing image for ${id}`);
    if (Math.random() > 0.7) throw new Error('Optimization service failed');
    return new Promise(res => setTimeout(res, 800));
  }
  async watermark(id) {
    Logger.log(`[MockImageService] Watermarking image for ${id}`);
    return new Promise(res => setTimeout(res, 800));
  }
}

@Injectable()
export class MockDbService {
    async backup() {
        Logger.log(`[MockDbService] Performing backup...`);
        await new Promise(res => setTimeout(res, 2000));
        return { file: `/backups/db-${Date.now()}.sql.gz` };
    }
}