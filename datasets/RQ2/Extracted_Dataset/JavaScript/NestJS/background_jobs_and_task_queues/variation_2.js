// --- main.js ---
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3001);
  console.log(`Application is running on: ${await app.getUrl()}`);
}
bootstrap();


// --- app.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { ScheduleModule } from '@nestjs/schedule';
import { UserModule } from './user/user.module.js';
import { PostModule } from './post/post.module.js';
import { TasksModule } from './tasks/tasks.module.js';
import { AppController } from './app.controller.js';

@Module({
  imports: [
    ScheduleModule.forRoot(),
    BullModule.forRoot({
      redis: { host: 'localhost', port: 6379 },
    }),
    UserModule,
    PostModule,
    TasksModule,
  ],
  controllers: [AppController],
})
export class AppModule {}


// --- app.controller.js ---
import { Controller, Post, Body } from '@nestjs/common';
import { UserService } from './user/user.service.js';
import { PostService } from './post/post.service.js';

@Controller()
export class AppController {
  constructor(userService, postService) {
    this.userService = userService;
    this.postService = postService;
  }

  @Post('users')
  async createUser(@Body() userData) {
    return this.userService.createUser(userData);
  }

  @Post('posts')
  async createPost(@Body() postData) {
    return this.postService.createPostWithImage(postData);
  }
}


// --- user/user.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { UserService } from './user.service.js';
import { UserJobsProcessor } from './user.jobs.processor.js';
import { MockMailService } from '../mocks/mock.services.js';

@Module({
  imports: [
    BullModule.registerQueue({
      name: 'user-queue',
    }),
  ],
  providers: [UserService, UserJobsProcessor, MockMailService],
  exports: [UserService],
})
export class UserModule {}


// --- user/user.service.js ---
import { Injectable, Logger } from '@nestjs/common';
import { InjectQueue } from '@nestjs/bull';
import { v4 as uuidv4 } from 'uuid';

@Injectable()
export class UserService {
  constructor(userQueue) {
    this.userQueue = userQueue;
  }

  async createUser(userData) {
    const user = { id: uuidv4(), ...userData };
    Logger.log(`Creating user ${user.id} and queuing welcome email.`);
    await this.userQueue.add('send-welcome-email', { user });
    return user;
  }
}


// --- user/user.jobs.processor.js ---
import { Processor, Process, OnQueueCompleted } from '@nestjs/bull';
import { Logger } from '@nestjs/common';
import { MockMailService } from '../mocks/mock.services.js';

@Processor('user-queue')
export class UserJobsProcessor {
  constructor(mailService) {
    this.mailService = mailService;
  }

  @OnQueueCompleted()
  logCompletion(job) {
    Logger.log(`User job ${job.id} (${job.name}) has completed.`);
  }

  @Process('send-welcome-email')
  async processWelcomeEmail(job) {
    const { user } = job.data;
    Logger.log(`Processing 'send-welcome-email' for user ${user.email}`);
    await this.mailService.sendEmail(user.email, 'Welcome!', 'Thanks for signing up.');
    return { sent: true };
  }
}


// --- post/post.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { PostService } from './post.service.js';
import { PostJobsProcessor } from './post.jobs.processor.js';
import { MockImageService } from '../mocks/mock.services.js';

@Module({
  imports: [
    BullModule.registerQueue({
      name: 'post-queue',
    }),
  ],
  providers: [PostService, PostJobsProcessor, MockImageService],
  exports: [PostService],
})
export class PostModule {}


// --- post/post.service.js ---
import { Injectable, Logger } from '@nestjs/common';
import { InjectQueue } from '@nestjs/bull';
import { v4 as uuidv4 } from 'uuid';

@Injectable()
export class PostService {
  constructor(postQueue) {
    this.postQueue = postQueue;
  }

  async createPostWithImage(postData) {
    const post = { id: uuidv4(), ...postData };
    Logger.log(`Creating post ${post.id} and queuing image processing.`);
    await this.postQueue.add(
      'process-post-image',
      { post },
      {
        attempts: 4,
        backoff: { type: 'exponential', delay: 2000 },
      },
    );
    return post;
  }
}


// --- post/post.jobs.processor.js ---
import { Processor, Process, OnQueueFailed } from '@nestjs/bull';
import { Logger } from '@nestjs/common';
import { MockImageService } from '../mocks/mock.services.js';

@Processor('post-queue')
export class PostJobsProcessor {
  constructor(imageService) {
    this.imageService = imageService;
  }

  @OnQueueFailed()
  logFailure(job, error) {
    Logger.error(`Post job ${job.id} (${job.name}) failed with error: ${error.message}`);
  }

  @Process('process-post-image')
  async processImage(job) {
    const { post } = job.data;
    Logger.log(`Processing 'process-post-image' for post ${post.id}`);
    await job.progress(25);
    const result = await this.imageService.processImagePipeline(post.id);
    await job.progress(100);
    return { ...result };
  }
}


// --- tasks/tasks.module.js ---
import { Module } from '@nestjs/common';
import { ScheduledTasksService } from './tasks.service.js';
import { MockPostService } from '../mocks/mock.services.js';

@Module({
  providers: [ScheduledTasksService, MockPostService],
})
export class TasksModule {}


// --- tasks/tasks.service.js ---
import { Injectable, Logger } from '@nestjs/common';
import { Cron } from '@nestjs/schedule';
import { MockPostService } from '../mocks/mock.services.js';

@Injectable()
export class ScheduledTasksService {
  constructor(postService) {
    this.postService = postService;
  }

  @Cron('0 0 * * *') // Every day at midnight
  async handleCleanupDrafts() {
    Logger.log('CRON: Running job to clean up old draft posts.');
    const deletedCount = await this.postService.deleteOldDrafts();
    Logger.log(`CRON: Cleaned up ${deletedCount} old draft posts.`);
  }
}


// --- mocks/mock.services.js ---
import { Injectable, Logger } from '@nestjs/common';

@Injectable()
export class MockMailService {
  async sendEmail(to, subject, body) {
    Logger.log(`[MockMail] Sending email to ${to} with subject "${subject}"`);
    await new Promise(res => setTimeout(res, 300));
    Logger.log(`[MockMail] Email sent.`);
  }
}

@Injectable()
export class MockImageService {
  async processImagePipeline(postId) {
    Logger.log(`[MockImage] Starting pipeline for post ${postId}`);
    await new Promise(res => setTimeout(res, 1500));
    if (Math.random() > 0.6) {
      Logger.error(`[MockImage] Pipeline failed for ${postId}. Simulating error.`);
      throw new Error('Image processing service timed out');
    }
    Logger.log(`[MockImage] Pipeline finished for post ${postId}`);
    return { url: `cdn.example.com/images/${postId}.jpg` };
  }
}

@Injectable()
export class MockPostService {
    async deleteOldDrafts() {
        Logger.log(`[MockPost] Deleting drafts older than 30 days...`);
        await new Promise(res => setTimeout(res, 500));
        return 15; // 15 posts deleted
    }
}