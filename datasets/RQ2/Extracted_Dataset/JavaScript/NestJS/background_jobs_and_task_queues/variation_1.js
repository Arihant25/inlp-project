// --- main.js ---
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3000);
  console.log(`Application is running on: ${await app.getUrl()}`);
}
bootstrap();


// --- app.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { ScheduleModule } from '@nestjs/schedule';
import { JobsModule } from './jobs.module.js';

@Module({
  imports: [
    ScheduleModule.forRoot(),
    BullModule.forRoot({
      redis: {
        host: 'localhost',
        port: 6379,
      },
    }),
    JobsModule,
  ],
})
export class AppModule {}


// --- jobs.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { JobsController } from './jobs.controller.js';
import { JobsService } from './jobs.service.js';
import { JobsProcessor } from './jobs.processor.js';
import { PeriodicTasksService } from './periodic-tasks.service.js';
import { MockMailService, MockImageService, MockUserService } from './mock.services.js';

@Module({
  imports: [
    BullModule.registerQueue({
      name: 'background-tasks',
    }),
  ],
  controllers: [JobsController],
  providers: [
    JobsService,
    JobsProcessor,
    PeriodicTasksService,
    MockMailService,
    MockImageService,
    MockUserService,
  ],
})
export class JobsModule {}


// --- jobs.controller.js ---
import { Controller, Post, Body, Get, Param } from '@nestjs/common';
import { JobsService } from './jobs.service.js';

@Controller('jobs')
export class JobsController {
  constructor(jobsService) {
    this.jobsService = jobsService;
  }

  @Post('send-welcome-email')
  async sendWelcomeEmail(@Body() user) {
    return this.jobsService.sendWelcomeEmail(user);
  }

  @Post('process-post-image')
  async processPostImage(@Body() post) {
    return this.jobsService.processPostImage(post);
  }

  @Get('status/:id')
  async getJobStatus(@Param('id') id) {
    return this.jobsService.getJobStatus(id);
  }
}


// --- jobs.service.js ---
import { Injectable } from '@nestjs/common';
import { InjectQueue } from '@nestjs/bull';

@Injectable()
export class JobsService {
  constructor(taskQueue) {
    this.taskQueue = taskQueue;
  }

  async sendWelcomeEmail(user) {
    const job = await this.taskQueue.add('send-email', { user });
    return { jobId: job.id };
  }

  async processPostImage(post) {
    const job = await this.taskQueue.add(
      'process-image',
      { post },
      {
        attempts: 3,
        backoff: {
          type: 'exponential',
          delay: 1000,
        },
      },
    );
    return { jobId: job.id };
  }

  async getJobStatus(jobId) {
    const job = await this.taskQueue.getJob(jobId);
    if (!job) {
      return { status: 'not found' };
    }
    return {
      id: job.id,
      status: await job.getState(),
      progress: job.progress,
      failedReason: job.failedReason,
    };
  }
}


// --- jobs.processor.js ---
import { Processor, Process, OnQueueActive, OnQueueCompleted, OnQueueFailed } from '@nestjs/bull';
import { Logger } from '@nestjs/common';
import { MockMailService } from './mock.services.js';
import { MockImageService } from './mock.services.js';

@Processor('background-tasks')
export class JobsProcessor {
  #logger = new Logger(JobsProcessor.name);

  constructor(mailService, imageService) {
    this.mailService = mailService;
    this.imageService = imageService;
  }

  @OnQueueActive()
  onActive(job) {
    this.#logger.log(`Processing job ${job.id} of type ${job.name}. Data: ${JSON.stringify(job.data)}`);
  }

  @OnQueueCompleted()
  onCompleted(job, result) {
    this.#logger.log(`Completed job ${job.id} of type ${job.name}. Result: ${JSON.stringify(result)}`);
  }

  @OnQueueFailed()
  onFailed(job, err) {
    this.#logger.error(`Failed job ${job.id} of type ${job.name}. Error: ${err.message}`, err.stack);
  }

  @Process('send-email')
  async handleSendEmail(job) {
    const { user } = job.data;
    await this.mailService.sendWelcomeEmail(user.email);
    return { status: 'Email sent' };
  }

  @Process('process-image')
  async handleProcessImage(job) {
    const { post } = job.data;
    await job.progress(10);
    const result = await this.imageService.compressImage(post.id);
    await job.progress(50);
    await this.imageService.generateThumbnail(post.id);
    await job.progress(100);
    return { status: 'Image processed', result };
  }
}


// --- periodic-tasks.service.js ---
import { Injectable, Logger } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { MockUserService } from './mock.services.js';

@Injectable()
export class PeriodicTasksService {
  #logger = new Logger(PeriodicTasksService.name);

  constructor(userService) {
    this.userService = userService;
  }

  @Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
  async handleDeactivateInactiveUsers() {
    this.#logger.log('Running nightly job: Deactivate Inactive Users');
    const count = await this.userService.deactivateInactive();
    this.#logger.log(`Deactivated ${count} inactive users.`);
  }
}


// --- mock.services.js ---
import { Injectable, Logger } from '@nestjs/common';

// Mock User entity
export class User {
  constructor(id, email) {
    this.id = id;
    this.email = email;
  }
}

// Mock Post entity
export class Post {
  constructor(id, userId) {
    this.id = id;
    this.user_id = userId;
  }
}

@Injectable()
export class MockMailService {
  async sendWelcomeEmail(email) {
    Logger.log(`[MockMailService] Sending welcome email to ${email}...`);
    await new Promise(resolve => setTimeout(resolve, 500));
    Logger.log(`[MockMailService] Email sent to ${email}.`);
  }
}

@Injectable()
export class MockImageService {
  async compressImage(postId) {
    Logger.log(`[MockImageService] Compressing image for post ${postId}...`);
    await new Promise(resolve => setTimeout(resolve, 1000));
    // Simulate a transient failure for retry demonstration
    if (Math.random() > 0.5) {
        Logger.warn(`[MockImageService] Failed to compress image for post ${postId}. Retrying...`);
        throw new Error('Image compression service unavailable');
    }
    Logger.log(`[MockImageService] Image compressed for post ${postId}.`);
    return { compressed: true };
  }
  async generateThumbnail(postId) {
    Logger.log(`[MockImageService] Generating thumbnail for post ${postId}...`);
    await new Promise(resolve => setTimeout(resolve, 500));
    Logger.log(`[MockImageService] Thumbnail generated for post ${postId}.`);
  }
}

@Injectable()
export class MockUserService {
    async deactivateInactive() {
        Logger.log(`[MockUserService] Querying for inactive users...`);
        await new Promise(resolve => setTimeout(resolve, 200));
        Logger.log(`[MockUserService] Found 5 inactive users and deactivated them.`);
        return 5;
    }
}