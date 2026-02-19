// Variation 2: The "Custom Cache Service" Approach
// This implementation abstracts caching logic into a dedicated `AppCacheService`.
// The domain service (`PostService`) uses this abstraction instead of the raw `CACHE_MANAGER`.
// This improves separation of concerns and makes the caching strategy more pluggable.
// DEPENDENCIES: @nestjs/common, @nestjs/core, @nestjs/platform-express, @nestjs/cache-manager, cache-manager, reflect-metadata, rxjs, uuid

import {
  Injectable,
  Inject,
  Controller,
  Get,
  Post,
  Body,
  Param,
  NotFoundException,
  Module,
  OnModuleInit,
  ParseUUIDPipe,
  Delete,
} from '@nestjs/common';
import { CacheModule, CACHE_MANAGER } from '@nestjs/cache-manager';
import { Cache } from 'cache-manager';
import { v4 as uuidv4 } from 'uuid';

// --- Domain Schema ---

enum PostStatus {
  DRAFT = 'DRAFT',
  PUBLISHED = 'PUBLISHED',
}

class PostEntity {
  id: string;
  user_id: string;
  title: string;
  content: string;
  status: PostStatus;
}

// --- Mock "Database" Repository ---

@Injectable()
class MockPostRepository {
  private readonly posts: Map<string, PostEntity> = new Map();

  constructor() {
    const initialPost = {
      id: 'c4b1e9b0-5a6a-4b0e-8c1a-3e4d5f6a7b8c',
      user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
      title: 'My First Post',
      content: 'This is the content of the first post.',
      status: PostStatus.PUBLISHED,
    };
    this.posts.set(initialPost.id, initialPost);
  }

  async findById(id: string): Promise<PostEntity | null> {
    console.log(`DATABASE_ACCESS: Fetching post with id ${id}`);
    await new Promise(res => setTimeout(res, 400)); // Simulate I/O latency
    return this.posts.get(id) || null;
  }

  async save(post: PostEntity): Promise<PostEntity> {
    console.log(`DATABASE_ACCESS: Saving post with id ${post.id}`);
    this.posts.set(post.id, post);
    return post;
  }

  async delete(id: string): Promise<void> {
    console.log(`DATABASE_ACCESS: Deleting post with id ${id}`);
    this.posts.delete(id);
  }
}

// --- Dedicated Caching Service ---

@Injectable()
class AppCacheService {
  constructor(@Inject(CACHE_MANAGER) private readonly cache: Cache) {}

  private static getPostKey(postId: string): string {
    return `post_entity:${postId}`;
  }

  async getPost(postId: string): Promise<PostEntity | null> {
    const post = await this.cache.get<PostEntity>(AppCacheService.getPostKey(postId));
    if (post) console.log(`CACHE_HIT: Found post ${postId} in cache.`);
    return post;
  }

  async setPost(post: PostEntity): Promise<void> {
    console.log(`CACHE_SET: Storing post ${post.id} in cache.`);
    await this.cache.set(AppCacheService.getPostKey(post.id), post, { ttl: 120 } as any); // TTL in seconds for cache-manager v5+
  }

  async invalidatePost(postId: string): Promise<void> {
    console.log(`CACHE_INVALIDATE: Deleting post ${postId} from cache.`);
    await this.cache.del(AppCacheService.getPostKey(postId));
  }
}

// --- Domain Service ---

@Injectable()
class PostService {
  constructor(
    private readonly postRepository: MockPostRepository,
    private readonly cacheService: AppCacheService,
  ) {}

  // Implements Cache-Aside pattern using the AppCacheService
  async getPostById(id: string): Promise<PostEntity> {
    // 1. Try to get from cache
    let post = await this.cacheService.getPost(id);

    if (!post) {
      console.log(`CACHE_MISS: Post ${id} not found in cache. Fetching from DB.`);
      // 2. On miss, get from repository
      post = await this.postRepository.findById(id);
      if (!post) {
        throw new NotFoundException(`Post with ID ${id} not found.`);
      }
      // 3. Set in cache for next time
      await this.cacheService.setPost(post);
    }
    return post;
  }

  async createPost(userId: string, title: string, content: string): Promise<PostEntity> {
    const newPost: PostEntity = {
      id: uuidv4(),
      user_id: userId,
      title,
      content,
      status: PostStatus.DRAFT,
    };
    // No cache interaction on create, as it won't be cached yet.
    // Some strategies might pre-warm the cache here.
    return this.postRepository.save(newPost);
  }

  // Implements cache invalidation
  async deletePost(id: string): Promise<void> {
    await this.postRepository.delete(id);
    await this.cacheService.invalidatePost(id);
  }
}

// --- Controller and Module ---

@Controller('posts')
class PostController {
  constructor(private readonly postService: PostService) {}

  @Get(':id')
  getPost(@Param('id', ParseUUIDPipe) id: string) {
    return this.postService.getPostById(id);
  }

  @Post()
  createPost(@Body() body: { userId: string; title: string; content: string }) {
    return this.postService.createPost(body.userId, body.title, body.content);
  }

  @Delete(':id')
  deletePost(@Param('id', ParseUUIDPipe) id: string) {
    return this.postService.deletePost(id);
  }
}

@Module({
  imports: [CacheModule.register({ isGlobal: true })],
  providers: [AppCacheService],
  exports: [AppCacheService],
})
class AppCacheModule {}

@Module({
  imports: [AppCacheModule],
  controllers: [PostController],
  providers: [PostService, MockPostRepository],
})
export class PostModule {}