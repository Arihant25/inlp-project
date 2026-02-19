// Variation 4: The "Minimalist & Modern" Developer
// Style: Concise, modern JS, minimal boilerplate, relies on framework defaults.
// Structure: Lean service, logic is direct and to the point.
// Dependencies: @nestjs/common, @nestjs/core, @nestjs/platform-express, multer, sharp, papaparse, uuid, reflect-metadata, rxjs

import {
  Controller, Get, Post, UploadedFile, UseInterceptors, Res, Injectable, Module,
  BadRequestException, InternalServerErrorException, Logger, StreamableFile, Param, ParseUUIDPipe
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs/promises';
import * as sharp from 'sharp';
import Papa from 'papaparse';
import { v4 as uuid } from 'uuid';
import { Response } from 'express';
import { Readable } from 'stream';

// --- Mocks & Stubs ---
enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

@Injectable()
class DbService {
  private logger = new Logger(DbService.name);
  users = [];
  posts = [
    { id: uuid(), userId: uuid(), title: 'Modern Post', content: '...', status: PostStatus.PUBLISHED },
    { id: uuid(), userId: uuid(), title: 'Another Post', content: '...', status: PostStatus.DRAFT },
  ];

  async bulkCreateUsers(users: any[]) {
    this.logger.log(`[MOCK] Creating ${users.length} users.`);
    this.users.push(...users);
    return { created: users.length, total: this.users.length };
  }
  async findPost(id: string) { return this.posts.find(p => p.id === id); }
  async getAllPosts() { return this.posts; }
}

// --- Service ---
@Injectable()
export class FilesService {
  constructor(private readonly db: DbService) {}

  async handleUserCsvUpload(file: Express.Multer.File) {
    const tempPath = path.join(os.tmpdir(), file.filename);
    try {
      await fs.writeFile(tempPath, file.buffer);
      const fileStream = require('fs').createReadStream(tempPath);
      
      const { data: users } = await new Promise(resolve => 
        Papa.parse(fileStream, { header: true, complete: resolve })
      );

      if (!users?.length) throw new BadRequestException('CSV is empty or invalid.');
      return this.db.bulkCreateUsers(users);
    } catch (err) {
      throw new InternalServerErrorException(`Failed to process CSV: ${err.message}`);
    } finally {
      await fs.unlink(tempPath).catch(() => {}); // Cleanup temp file
    }
  }

  async handlePostImageUpload(postId: string, file: Express.Multer.File) {
    if (!await this.db.findPost(postId)) throw new BadRequestException('Post not found');
    
    const filename = `${uuid()}.webp`;
    const outputPath = path.join(process.cwd(), 'public', 'images', filename);
    
    await sharp(file.buffer)
      .resize({ width: 500 })
      .webp({ quality: 75 })
      .toFile(outputPath);
      
    // In a real app, update post with `imageUrl: /images/${filename}`
    return { imageUrl: `/images/${filename}` };
  }

  async streamPostsAsCsv() {
    const posts = await this.db.getAllPosts();
    const csvString = Papa.unparse(posts);
    return Readable.from(csvString);
  }
}

// --- Controller ---
@Controller('files')
export class FilesController {
  constructor(private readonly filesSvc: FilesService) {}

  @Post('users/import')
  @UseInterceptors(FileInterceptor('file', {
    limits: { fileSize: 5 * 1024 * 1024 }, // 5MB
    fileFilter: (req, file, cb) => {
      file.mimetype === 'text/csv'
        ? cb(null, true)
        : cb(new BadRequestException('Only CSV files are allowed.'), false);
    },
  }))
  uploadUsers(@UploadedFile() file: Express.Multer.File) {
    if (!file) throw new BadRequestException('No file uploaded.');
    return this.filesSvc.handleUserCsvUpload(file);
  }

  @Post('posts/:id/image')
  @UseInterceptors(FileInterceptor('image', {
    limits: { fileSize: 10 * 1024 * 1024 }, // 10MB
    fileFilter: (req, file, cb) => {
      file.mimetype.startsWith('image/')
        ? cb(null, true)
        : cb(new BadRequestException('Only image files are allowed.'), false);
    },
  }))
  uploadPostImage(
    @Param('id', ParseUUIDPipe) id: string,
    @UploadedFile() image: Express.Multer.File
  ) {
    if (!image) throw new BadRequestException('No image uploaded.');
    return this.filesSvc.handlePostImageUpload(id, image);
  }

  @Get('posts/export')
  async exportPosts(@Res({ passthrough: true }) res: Response) {
    res.set({
      'Content-Type': 'text/csv',
      'Content-Disposition': 'attachment; filename="posts.csv"',
    });
    const stream = await this.filesSvc.streamPostsAsCsv();
    return new StreamableFile(stream);
  }
}

// --- NestJS Module Bootstrap ---
@Module({
  controllers: [FilesController],
  providers: [FilesService, DbService],
})
export class AppModule {}