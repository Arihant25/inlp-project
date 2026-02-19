// --- main.js ---
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3003);
  console.log(`Application is running on: ${await app.getUrl()}`);
}
bootstrap();


// --- app.module.js ---
import { Module } from '@nestjs/common';
import { BullModule } from '@nestjs/bull';
import { ScheduleModule } from '@nestjs/schedule';
import { ConfigModule } from './config/config.module.js';
import { ConfigService } from './config/config.service.js';
import { AppController } from './app.controller.js';
import { JobSchedulerService } from './job-scheduler.service.js';
import { AllInOneProcessor } from './all-in-one.processor.js';
import { JOB_QUEUE } from './constants.js';
import { MockServicesModule } from './mock-services.module.js';

@Module({
  imports: [
    ConfigModule,
    ScheduleModule.forRoot(),
    BullModule.forRootAsync({
      imports: [ConfigModule],
      useFactory: async (configSvc) => ({
        redis: configSvc.get('redis'),
      }),
      inject: [ConfigService],
    }),
    BullModule.registerQueue({
      name: JOB_QUEUE,
    }),
    MockServicesModule,
  ],
  controllers: [AppController],
  providers: [JobSchedulerService, AllInOneProcessor],
})
export class AppModule {}


// --- constants.js ---
export const JOB_QUEUE = 'master_queue';

export const JOB_TYPES = {
  SEND_ACTIVATION_EMAIL: 'SEND_ACTIVATION_EMAIL',
  PROCESS_POST_IMAGE_PIPELINE: 'PROCESS_POST_IMAGE_PIPELINE',
};


// --- config/config.module.js ---
import { Module } from '@nestjs/common';
import { ConfigService } from './config.service.js';

@Module({
  providers: [ConfigService],
  exports: [ConfigService],
})
export class ConfigModule {}


// --- config/config.service.js ---
import { Injectable } from '@nestjs/common';

@Injectable()
export class ConfigService {
  #config = {
    redis: {
      host: 'localhost',
      port: 6379,
    },
  };

  get(key) {
    return this.#config[key];
  }
}


// --- app.controller.js ---
import { Controller, Post, Body, Get, Param } from '@nestjs/common';
import { JobSchedulerService } from './job-scheduler.service.js';

@Controller('schedule')
export class AppController {
  constructor(scheduler) {
    this.scheduler = scheduler;
  }

  @Post('email')
  async scheduleEmail(@Body() payload) {
    const job = await this.scheduler.scheduleEmail(payload.user);
    return { message: 'Email job scheduled', jobId: job.id };
  }

  @Post('image')
  async scheduleImageProcessing(@Body() payload) {
    const job = await this.scheduler.scheduleImageProcessing(payload.post);
    return { message: 'Image processing job scheduled', jobId: job.id };
  }

  @Get('job/:id')
  async getJob(@Param('id') id) {
    return this.scheduler.getJobDetails(id);
  }
}


// --- job-scheduler.service.js ---
import { Injectable } from '@nestjs/common';
import { InjectQueue } from '@nestjs/bull';
import { JOB_QUEUE, JOB_TYPES } from './constants.js';

@Injectable()
export class JobSchedulerService {
  constructor(masterQueue) {
    this.masterQueue = masterQueue;
  }

  async scheduleEmail(user) {
    return this.masterQueue.add(JOB_TYPES.SEND_ACTIVATION_EMAIL, { user });
  }

  async scheduleImageProcessing(post) {
    const jobOptions = {
      attempts: 5,
      backoff: {
        type: 'exponential',
        delay: 500,
      },
      removeOnComplete: true,
      removeOnFail: false,
    };
    return this.masterQueue.add(JOB_TYPES.PROCESS_POST_IMAGE_PIPELINE, { post }, jobOptions);
  }

  async getJobDetails(jobId) {
    const job = await this.masterQueue.getJob(jobId);
    if (!job) return { error: 'Job not found' };
    return {
      id: job.id,
      name: job.name,
      data: job.data,
      state: await job.getState(),
      finishedOn: job.finishedOn,
      failedReason: job.failedReason,
    };
  }
}


// --- all-in-one.processor.js ---
import { Processor, Process, OnQueueActive, OnQueueCompleted, OnQueueFailed } from '@nestjs/bull';
import { Inject, Logger } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { JOB_QUEUE, JOB_TYPES } from './constants.js';
import { MOCK_MAIL_SVC_TOKEN, MOCK_IMG_SVC_TOKEN, MOCK_USER_SVC_TOKEN } from './mock-services.module.js';

@Processor(JOB_QUEUE)
export class AllInOneProcessor {
  #logger = new Logger(AllInOneProcessor.name);

  constructor(
    @Inject(MOCK_MAIL_SVC_TOKEN) mailer,
    @Inject(MOCK_IMG_SVC_TOKEN) imageProcessor,
    @Inject(MOCK_USER_SVC_TOKEN) userRepo,
  ) {
    this.mailer = mailer;
    this.imageProcessor = imageProcessor;
    this.userRepo = userRepo;
  }

  @OnQueueActive()
  handleActive(job) {
    this.#logger.log(`Job ${job.id} [${job.name}] started.`);
  }

  @OnQueueCompleted()
  handleCompletion(job, result) {
    this.#logger.log(`Job ${job.id} [${job.name}] completed. Result: ${JSON.stringify(result)}`);
  }

  @OnQueueFailed()
  handleFailure(job, error) {
    this.#logger.error(`Job ${job.id} [${job.name}] failed. Reason: ${error.message}`);
  }

  @Process(JOB_TYPES.SEND_ACTIVATION_EMAIL)
  async processActivationEmail(job) {
    const { user } = job.data;
    await this.mailer.sendActivationEmail(user.email);
    return { status: 'ok' };
  }

  @Process(JOB_TYPES.PROCESS_POST_IMAGE_PIPELINE)
  async processImagePipeline(job) {
    const { post } = job.data;
    await job.log('Starting image pipeline...');
    await job.progress(20);
    const optimized = await this.imageProcessor.optimize(post.id);
    if (!optimized) {
      throw new Error('Optimization step failed');
    }
    await job.log('Image optimized. Generating formats...');
    await job.progress(60);
    await this.imageProcessor.generateWebp(post.id);
    await job.progress(80);
    await this.imageProcessor.generateAvif(post.id);
    await job.progress(100);
    return { formats: ['webp', 'avif'] };
  }

  @Cron(CronExpression.EVERY_SUNDAY)
  async weeklyUserReport() {
    this.#logger.log('CRON: Generating weekly user report.');
    const stats = await this.userRepo.getWeeklyStats();
    this.#logger.log(`CRON: Report generated. New users: ${stats.newUsers}`);
  }
}


// --- mock-services.module.js ---
import { Module, Logger } from '@nestjs/common';

export const MOCK_MAIL_SVC_TOKEN = 'MOCK_MAIL_SERVICE';
export const MOCK_IMG_SVC_TOKEN = 'MOCK_IMAGE_SERVICE';
export const MOCK_USER_SVC_TOKEN = 'MOCK_USER_SERVICE';

const mockMailProvider = {
  provide: MOCK_MAIL_SVC_TOKEN,
  useValue: {
    sendActivationEmail: async (email) => {
      Logger.log(`[MockMail] Sending activation to ${email}`);
      await new Promise(r => setTimeout(r, 250));
    },
  },
};

const mockImageProvider = {
  provide: MOCK_IMG_SVC_TOKEN,
  useValue: {
    optimize: async (id) => {
      Logger.log(`[MockImg] Optimizing ${id}`);
      await new Promise(r => setTimeout(r, 700));
      if (Math.random() > 0.5) return true;
      Logger.warn(`[MockImg] Optimization failed for ${id}, will retry.`);
      return false;
    },
    generateWebp: async (id) => {
      Logger.log(`[MockImg] Generating WebP for ${id}`);
      await new Promise(r => setTimeout(r, 400));
    },
    generateAvif: async (id) => {
      Logger.log(`[MockImg] Generating AVIF for ${id}`);
      await new Promise(r => setTimeout(r, 600));
    },
  },
};

const mockUserProvider = {
    provide: MOCK_USER_SVC_TOKEN,
    useValue: {
        getWeeklyStats: async () => {
            Logger.log(`[MockUser] Calculating weekly stats...`);
            await new Promise(r => setTimeout(r, 100));
            return { newUsers: Math.floor(Math.random() * 100) };
        }
    }
}

@Module({
  providers: [mockMailProvider, mockImageProvider, mockUserProvider],
  exports: [mockMailProvider, mockImageProvider, mockUserProvider],
})
export class MockServicesModule {}