// Variation 1: The "By-the-Book" Developer
// Style: Classic, explicit, well-commented, follows official docs closely.
// Structure: Clear separation of concerns between Controller and Service.
// Dependencies: @nestjs/common, @nestjs/core, @nestjs/platform-express, multer, sharp, csv-parser, xlsx, uuid, reflect-metadata, rxjs

import {
  Controller,
  Get,
  Post,
  Param,
  UploadedFile,
  UseInterceptors,
  StreamableFile,
  Res,
  ParseUUIDPipe,
  Injectable,
  Module,
  BadRequestException,
  InternalServerErrorException,
  Logger,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { diskStorage } from 'multer';
import { extname, join } from 'path';
import * as fs from 'fs';
import * as csv from 'csv-parser';
import * as sharp from 'sharp';
import * as xlsx from 'xlsx';
import { v4 as uuidv4 } from 'uuid';
import { Response } from 'express';
import { Readable } from 'stream';

// --- Mocks and Domain Stubs ---

enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

// Mock User Service to simulate database interaction
@Injectable()
class MockUserService {
  private readonly logger = new Logger(MockUserService.name);
  async createManyUsers(users: any[]): Promise<{ count: number }> {
    this.logger.log(`Simulating bulk creation of ${users.length} users.`);
    // In a real app, this would interact with a database.
    return { count: users.length };
  }
}

// Mock Post Service
@Injectable()
class MockPostService {
  private readonly logger = new Logger(MockPostService.name);
  private posts = [
    { id: uuidv4(), user_id: uuidv4(), title: 'First Post', content: 'Content 1', status: PostStatus.PUBLISHED },
    { id: uuidv4(), user_id: uuidv4(), title: 'Second Post', content: 'Content 2', status: PostStatus.DRAFT },
  ];
  async findPostById(id: string): Promise<any> {
    this.logger.log(`Finding post with id: ${id}`);
    return this.posts[0]; // Return a mock post
  }
  async findAllPosts(): Promise<any[]> {
    this.logger.log('Finding all posts.');
    return this.posts;
  }
}

// --- File Operations Service ---

@Injectable()
export class FileService {
  private readonly logger = new Logger(FileService.name);
  private readonly tempPath = './temp_files';

  constructor(
    private readonly userService: MockUserService,
    private readonly postService: MockPostService,
  ) {
    // Ensure temporary directory exists
    if (!fs.existsSync(this.tempPath)) {
      fs.mkdirSync(this.tempPath, { recursive: true });
    }
  }

  /**
   * Processes an uploaded CSV or Excel file to bulk-create users.
   * @param file The uploaded file object from Multer.
   * @returns The number of users created.
   */
  async processUsersUpload(file: Express.Multer.File): Promise<{ count: number }> {
    this.logger.log(`Processing user upload file: ${file.originalname}`);
    const records = [];
    
    try {
      if (extname(file.originalname).toLowerCase() === '.csv') {
        await new Promise<void>((resolve, reject) => {
          fs.createReadStream(file.path)
            .pipe(csv())
            .on('data', (data) => records.push(data))
            .on('end', resolve)
            .on('error', reject);
        });
      } else if (['.xlsx', '.xls'].includes(extname(file.originalname).toLowerCase())) {
        const workbook = xlsx.readFile(file.path);
        const sheetName = workbook.SheetNames[0];
        const sheet = workbook.Sheets[sheetName];
        const jsonSheet = xlsx.utils.sheet_to_json(sheet);
        records.push(...jsonSheet);
      } else {
        throw new BadRequestException('Unsupported file type. Please upload a CSV or Excel file.');
      }

      // Here you would map `records` to your User entity DTO and validate
      const createdUsers = await this.userService.createManyUsers(records);
      this.logger.log(`Successfully created ${createdUsers.count} users.`);
      return createdUsers;

    } catch (error) {
      this.logger.error('Failed to process uploaded file', error.stack);
      throw new InternalServerErrorException('Could not process file.');
    } finally {
      // Temporary file cleanup
      this.logger.log(`Cleaning up temporary file: ${file.path}`);
      fs.unlink(file.path, (err) => {
        if (err) this.logger.error(`Failed to delete temp file: ${file.path}`, err.stack);
      });
    }
  }

  /**
   * Resizes an uploaded image for a post and saves it.
   * @param postId The ID of the post.
   * @param file The uploaded image file.
   * @returns The path to the saved thumbnail.
   */
  async resizeAndSavePostImage(postId: string, file: Express.Multer.File): Promise<{ thumbnailUrl: string }> {
    const post = await this.postService.findPostById(postId);
    if (!post) {
      throw new BadRequestException(`Post with ID ${postId} not found.`);
    }

    const outputPath = join('./uploads/post_images', postId);
    const thumbnailFilename = `${uuidv4()}-thumb.webp`;
    const thumbnailPath = join(outputPath, thumbnailFilename);

    try {
      if (!fs.existsSync(outputPath)) {
        fs.mkdirSync(outputPath, { recursive: true });
      }

      await sharp(file.path)
        .resize(200, 200, { fit: 'cover' })
        .webp({ quality: 80 })
        .toFile(thumbnailPath);

      this.logger.log(`Thumbnail created for post ${postId} at ${thumbnailPath}`);
      // In a real app, you would save `thumbnailPath` to the Post entity.
      return { thumbnailUrl: thumbnailPath };
    } catch (error) {
      this.logger.error(`Failed to process image for post ${postId}`, error.stack);
      throw new InternalServerErrorException('Could not process image.');
    } finally {
      // Cleanup original uploaded file
      fs.unlink(file.path, (err) => {
        if (err) this.logger.error(`Failed to delete temp file: ${file.path}`, err.stack);
      });
    }
  }

  /**
   * Generates a CSV report of all posts and returns it as a readable stream.
   * @returns A readable stream of the CSV data.
   */
  async getPostsCsvStream(): Promise<Readable> {
    const posts = await this.postService.findAllPosts();
    if (posts.length === 0) {
      return Readable.from('');
    }

    // Define CSV header
    const header = 'id,user_id,title,status,content\n';
    
    // Convert post data to CSV rows
    const csvRows = posts.map(post => 
      `${post.id},${post.user_id},"${post.title.replace(/"/g, '""')}","${post.status}","${post.content.replace(/"/g, '""').replace(/\n/g, ' ')}"`
    ).join('\n');

    const csvContent = header + csvRows;
    return Readable.from(csvContent);
  }
}

// --- File Operations Controller ---

@Controller('files')
export class FilesController {
  constructor(private readonly fileService: FileService) {}

  /**
   * Endpoint for uploading a CSV or Excel file of users.
   */
  @Post('upload/users')
  @UseInterceptors(FileInterceptor('file', {
    storage: diskStorage({
      destination: './temp_files',
      filename: (req, file, cb) => {
        const randomName = Array(32).fill(null).map(() => (Math.round(Math.random() * 16)).toString(16)).join('');
        cb(null, `${randomName}${extname(file.originalname)}`);
      },
    }),
    fileFilter: (req, file, cb) => {
      if (!file.originalname.match(/\.(csv|xlsx|xls)$/)) {
        return cb(new BadRequestException('Only CSV and Excel files are allowed!'), false);
      }
      cb(null, true);
    },
  }))
  async uploadUsersFile(@UploadedFile() file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('No file uploaded.');
    }
    return this.fileService.processUsersUpload(file);
  }

  /**
   * Endpoint for uploading and processing a post image.
   */
  @Post('upload/post-image/:postId')
  @UseInterceptors(FileInterceptor('image', {
    dest: './temp_files',
    limits: { fileSize: 1024 * 1024 * 5 }, // 5MB limit
    fileFilter: (req, file, cb) => {
      if (!file.mimetype.startsWith('image/')) {
        return cb(new BadRequestException('Only image files are allowed!'), false);
      }
      cb(null, true);
    },
  }))
  async uploadPostImage(
    @Param('postId', ParseUUIDPipe) postId: string,
    @UploadedFile() file: Express.Multer.File,
  ) {
    if (!file) {
      throw new BadRequestException('No image uploaded.');
    }
    return this.fileService.resizeAndSavePostImage(postId, file);
  }

  /**
   * Endpoint for downloading a CSV report of all posts.
   */
  @Get('download/posts-report.csv')
  async downloadPostsReport(@Res({ passthrough: true }) res: Response) {
    const stream = await this.fileService.getPostsCsvStream();
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'attachment; filename=posts-report.csv');
    return new StreamableFile(stream);
  }
}

// --- NestJS Module Bootstrap ---

@Module({
  imports: [],
  controllers: [FilesController],
  providers: [FileService, MockUserService, MockPostService],
})
export class AppModule {}