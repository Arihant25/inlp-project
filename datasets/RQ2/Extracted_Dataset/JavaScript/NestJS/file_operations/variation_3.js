// Variation 3: The "Pragmatic & Class-based" Developer
// Style: OOP-heavy, uses Strategy pattern with dedicated processor classes.
// Structure: Service acts as a facade, delegating to injectable processor classes.
// Dependencies: @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/config, multer, sharp, csv-parser, uuid, reflect-metadata, rxjs

import {
  Controller,
  Get,
  Post,
  Query,
  UploadedFile,
  UseInterceptors,
  Res,
  Injectable,
  Module,
  BadRequestException,
  InternalServerErrorException,
  Logger,
  Inject,
  Scope,
  StreamableFile,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { diskStorage } from 'multer';
import { extname, join } from 'path';
import * as fs from 'fs';
import * as fsp from 'fs/promises';
import * as csv from 'csv-parser';
import * as sharp from 'sharp';
import { v4 as uuidv4 } from 'uuid';
import { Response } from 'express';
import { Readable } from 'stream';

// --- Mocks and Domain Stubs ---
enum UserRole { ADMIN = 'ADMIN', USER = 'USER' }
enum PostStatus { DRAFT = 'DRAFT', PUBLISHED = 'PUBLISHED' }

@Injectable()
class MockUserService {
  createMany = async (data) => ({ createdCount: data.length });
}
@Injectable()
class MockPostService {
  findAll = async () => [
    { id: uuidv4(), user_id: uuidv4(), title: 'Post 1', content: 'Content 1', status: PostStatus.PUBLISHED },
    { id: uuidv4(), user_id: uuidv4(), title: 'Post 2', content: 'Content 2', status: PostStatus.DRAFT },
  ];
  update = async (id, data) => ({ ...data, id });
}

// --- Configuration Mock ---
const CONFIG_TOKEN = 'APP_CONFIG';
const appConfig = {
  paths: {
    temp: './temp_uploads',
    images: './public/images',
  },
};
const ConfigProvider = { provide: CONFIG_TOKEN, useValue: appConfig };

// --- Processor Classes (Strategy Pattern) ---

interface IFileProcessor {
  process(file: Express.Multer.File, options?: any): Promise<any>;
}

@Injectable({ scope: Scope.TRANSIENT })
class CsvUserProcessor implements IFileProcessor {
  private readonly logger = new Logger(CsvUserProcessor.name);
  constructor(private readonly userService: MockUserService) {}

  async process(file: Express.Multer.File): Promise<any> {
    const users = [];
    try {
      await new Promise<void>((resolve, reject) => {
        fs.createReadStream(file.path)
          .pipe(csv({ mapHeaders: ({ header }) => header.toLowerCase().trim() }))
          .on('data', (data) => users.push(data))
          .on('end', resolve)
          .on('error', reject);
      });
      this.logger.log(`Parsed ${users.length} records from ${file.originalname}`);
      return this.userService.createMany(users);
    } finally {
      await fsp.unlink(file.path).catch(err => this.logger.error(`Cleanup failed for ${file.path}`, err));
    }
  }
}

@Injectable({ scope: Scope.TRANSIENT })
class ImagePostProcessor implements IFileProcessor {
  private readonly logger = new Logger(ImagePostProcessor.name);
  constructor(
    private readonly postService: MockPostService,
    @Inject(CONFIG_TOKEN) private readonly config,
  ) {}

  async process(file: Express.Multer.File, options: { postId: string }): Promise<any> {
    const { postId } = options;
    const finalPath = join(this.config.paths.images, postId);
    const fileName = `${uuidv4()}.webp`;
    const finalFilePath = join(finalPath, fileName);

    try {
      await fsp.mkdir(finalPath, { recursive: true });
      await sharp(file.path)
        .resize(800)
        .webp()
        .toFile(finalFilePath);
      
      this.logger.log(`Processed image for post ${postId} saved to ${finalFilePath}`);
      return this.postService.update(postId, { imageUrl: `/images/${postId}/${fileName}` });
    } finally {
      await fsp.unlink(file.path).catch(err => this.logger.error(`Cleanup failed for ${file.path}`, err));
    }
  }
}

@Injectable()
class ReportGenerator {
  constructor(private readonly postService: MockPostService) {}
  async generatePostsCsvStream(): Promise<Readable> {
    const posts = await this.postService.findAll();
    const header = 'id,user_id,title,status\n';
    const rows = posts.map(p => `${p.id},${p.user_id},"${p.title}",${p.status}`).join('\n');
    return Readable.from(header + rows);
  }
}

// --- Facade Service ---

@Injectable()
export class FileManagerService {
  constructor(
    private readonly csvProcessor: CsvUserProcessor,
    private readonly imageProcessor: ImagePostProcessor,
    private readonly reportGenerator: ReportGenerator,
  ) {}

  dispatchFileProcessing(type: string, file: Express.Multer.File, options: any) {
    switch (type) {
      case 'users-csv':
        return this.csvProcessor.process(file);
      case 'post-image':
        if (!options?.postId) throw new BadRequestException('postId is required for post-image type.');
        return this.imageProcessor.process(file, options);
      default:
        // Also unlink the file if the type is unknown
        fsp.unlink(file.path);
        throw new BadRequestException(`Unknown file processing type: ${type}`);
    }
  }

  getPostsReport() {
    return this.reportGenerator.generatePostsCsvStream();
  }
}

// --- Controller ---

@Controller('file-manager')
export class FileManagerController {
  constructor(private readonly fileManager: FileManagerService) {}

  @Post('upload')
  @UseInterceptors(FileInterceptor('file', {
    storage: diskStorage({
      destination: (req, file, cb) => cb(null, appConfig.paths.temp),
      filename: (req, file, cb) => cb(null, `${uuidv4()}${extname(file.originalname)}`),
    }),
  }))
  uploadFile(
    @UploadedFile() file: Express.Multer.File,
    @Query('type') type: string,
    @Query('postId') postId?: string,
  ) {
    if (!file) throw new BadRequestException('File is missing.');
    return this.fileManager.dispatchFileProcessing(type, file, { postId });
  }

  @Get('download/posts-report')
  async downloadReport(@Res({ passthrough: true }) res: Response) {
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'attachment; filename="posts.csv"');
    const stream = await this.fileManager.getPostsReport();
    return new StreamableFile(stream);
  }
}

// --- NestJS Module Bootstrap ---

@Module({
  imports: [],
  controllers: [FileManagerController],
  providers: [
    FileManagerService,
    CsvUserProcessor,
    ImagePostProcessor,
    ReportGenerator,
    MockUserService,
    MockPostService,
    ConfigProvider,
  ],
})
export class AppModule {
    constructor(@Inject(CONFIG_TOKEN) private readonly config) {
        // Ensure directories exist on startup
        Object.values(this.config.paths).forEach((path: string) => {
            if (!fs.existsSync(path)) fs.mkdirSync(path, { recursive: true });
        });
    }
}