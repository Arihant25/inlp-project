// Variation 2: The "Functional & Composable" Developer
// Style: Prefers functional composition, async/await, and in-memory processing.
// Structure: Logic is composed of smaller, reusable utility functions.
// Dependencies: @nestjs/common, @nestjs/core, @nestjs/platform-express, multer, sharp, papaparse, uuid, reflect-metadata, rxjs

import {
  Controller,
  Get,
  Post,
  UploadedFile,
  UseInterceptors,
  Res,
  Injectable,
  Module,
  BadRequestException,
  Logger,
  StreamableFile,
  Body,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import * as sharp from 'sharp';
import * as Papa from 'papaparse';
import { v4 as uuidv4 } from 'uuid';
import { Response } from 'express';
import { Readable } from 'stream';

// --- Mocks and Domain Stubs ---

enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

@Injectable()
class MockUsersRepo {
  private readonly logger = new Logger(MockUsersRepo.name);
  async bulkInsert(users: any[]): Promise<{ created: number }> {
    this.logger.log(`[MOCK] Bulk inserting ${users.length} users.`);
    return { created: users.length };
  }
}

@Injectable()
class MockPostsRepo {
  private readonly logger = new Logger(MockPostsRepo.name);
  private posts = [
    { id: uuidv4(), userId: uuidv4(), title: 'Post A', content: 'Content A', status: PostStatus.PUBLISHED },
    { id: uuidv4(), userId: uuidv4(), title: 'Post B', content: 'Content B', status: PostStatus.DRAFT },
  ];
  async saveImageMetadata(postId: string, metadata: any) {
    this.logger.log(`[MOCK] Saving image metadata for post ${postId}: ${JSON.stringify(metadata)}`);
  }
  async *findAllStream() {
    this.logger.log('[MOCK] Streaming all posts from DB.');
    for (const post of this.posts) {
      yield post;
    }
  }
}

// --- Functional Utilities ---

const parseCsvFromBuffer = <T>(buffer: Buffer): Promise<T[]> => 
  new Promise((resolve, reject) => {
    Papa.parse(buffer.toString('utf-8'), {
      header: true,
      skipEmptyLines: true,
      complete: (results) => resolve(results.data as T[]),
      error: (error) => reject(error),
    });
  });

const resizeImageBuffer = (buffer: Buffer, width: number, height: number): Promise<Buffer> => 
  sharp(buffer)
    .resize(width, height, { fit: 'inside' })
    .jpeg({ quality: 90 })
    .toBuffer();

// --- Core Service ---

@Injectable()
export class FileOpsService {
  constructor(
    private readonly usersRepo: MockUsersRepo,
    private readonly postsRepo: MockPostsRepo,
  ) {}

  async importUsersFromCsv(fileBuffer: Buffer) {
    if (!fileBuffer) throw new BadRequestException('CSV file buffer is required.');
    
    const usersToCreate = await parseCsvFromBuffer(fileBuffer);
    // Add validation logic here (e.g., using class-validator)
    return this.usersRepo.bulkInsert(usersToCreate);
  }

  async processPostThumbnail(postId: string, imageBuffer: Buffer) {
    if (!imageBuffer) throw new BadRequestException('Image buffer is required.');

    const thumbnailBuffer = await resizeImageBuffer(imageBuffer, 150, 150);
    
    // In a real app, this would upload the buffer to S3/GCS and save the URL.
    // fs.writeFileSync(`./uploads/${postId}-thumb.jpg`, thumbnailBuffer);
    
    const metadata = { thumbnailUrl: `/uploads/${postId}-thumb.jpg`, size: thumbnailBuffer.length };
    await this.postsRepo.saveImageMetadata(postId, metadata);
    return metadata;
  }

  async streamPostsAsCsv(): Promise<Readable> {
    const postGenerator = this.postsRepo.findAllStream();
    
    const csvStream = new Readable({
      async read() {
        let first = true;
        for await (const post of postGenerator) {
          if (first) {
            this.push(Object.keys(post).join(',') + '\n');
            first = false;
          }
          const csvRow = Papa.unparse([post], { header: false });
          this.push(csvRow + '\n');
        }
        this.push(null); // End of stream
      }
    });

    return csvStream;
  }
}

// --- Controller ---

@Controller('file-ops')
export class FileOpsController {
  constructor(private readonly fileOpsSvc: FileOpsService) {}

  @Post('import/users-csv')
  @UseInterceptors(FileInterceptor('file', { storage: memoryStorage() }))
  async importUsers(@UploadedFile() file: Express.Multer.File) {
    if (!file) throw new BadRequestException('No file provided.');
    return this.fileOpsSvc.importUsersFromCsv(file.buffer);
  }

  @Post('attach/post-image')
  @UseInterceptors(FileInterceptor('image', { storage: memoryStorage() }))
  async attachImage(
    @UploadedFile() file: Express.Multer.File,
    @Body('postId') postId: string,
  ) {
    if (!file) throw new BadRequestException('No image provided.');
    if (!postId) throw new BadRequestException('postId is required.');
    return this.fileOpsSvc.processPostThumbnail(postId, file.buffer);
  }

  @Get('export/posts-csv')
  async exportPosts(@Res({ passthrough: true }) res: Response) {
    const stream = await this.fileOpsSvc.streamPostsAsCsv();
    res.set({
      'Content-Type': 'text/csv',
      'Content-Disposition': `attachment; filename="posts-export-${Date.now()}.csv"`,
    });
    return new StreamableFile(stream);
  }
}

// --- NestJS Module Bootstrap ---

@Module({
  imports: [],
  controllers: [FileOpsController],
  providers: [FileOpsService, MockUsersRepo, MockPostsRepo],
})
export class AppModule {}