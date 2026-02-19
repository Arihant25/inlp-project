// Variation 3: The "LRU-Focused" Approach
// This implementation integrates a custom LRU (Least Recently Used) cache.
// It uses a custom provider to inject an `lru-cache` instance into the service.
// This demonstrates how to use caching mechanisms other than the default NestJS cache manager.
// DEPENDENCIES: @nestjs/common, @nestjs/core, @nestjs/platform-express, lru-cache, reflect-metadata, rxjs, uuid

import {
  Injectable,
  Inject,
  Controller,
  Get,
  Param,
  NotFoundException,
  Module,
  Provider,
  Put,
  Body,
  ParseUUIDPipe,
} from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';
import { LRUCache } from 'lru-cache';

// --- Domain Schema ---

enum PostStatus {
  DRAFT = 'DRAFT',
  PUBLISHED = 'PUBLISHED',
}

class Post {
  id: string;
  user_id: string;
  title: string;
  content: string;
  status: PostStatus;
}

// --- Mock Database ---

const MOCK_POST_DB: Map<string, Post> = new Map();
const initialPost: Post = {
  id: 'c4b1e9b0-5a6a-4b0e-8c1a-3e4d5f6a7b8c',
  user_id: 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
  title: 'LRU Cached Post',
  content: 'This content is managed by an LRU cache.',
  status: PostStatus.PUBLISHED,
};
MOCK_POST_DB.set(initialPost.id, initialPost);

@Injectable()
class MockPostDbClient {
  async findPost(id: string): Promise<Post | null> {
    console.log(`-- DB-ACCESS: Querying for post ${id}`);
    await new Promise(res => setTimeout(res, 600)); // Simulate network latency
    const post = MOCK_POST_DB.get(id);
    return post ? { ...post } : null; // Return a copy
  }
  async updatePost(id: string, data: Partial<Post>): Promise<Post | null> {
    console.log(`-- DB-ACCESS: Updating post ${id}`);
    const post = MOCK_POST_DB.get(id);
    if (!post) return null;
    const updated = { ...post, ...data };
    MOCK_POST_DB.set(id, updated);
    return updated;
  }
}

// --- Custom LRU Cache Provider ---

export const LRU_CACHE = 'LRU_CACHE';

const lruCacheProvider: Provider = {
  provide: LRU_CACHE,
  useFactory: () => {
    // LRU cache that holds up to 50 items for 5 minutes
    const options = {
      max: 50,
      ttl: 1000 * 60 * 5, 
    };
    console.log('LRU Cache Initialized');
    return new LRUCache<string, Post>(options);
  },
};

// --- Service Layer ---

@Injectable()
class PostServiceWithLru {
  constructor(
    @Inject(LRU_CACHE) private readonly lruCache: LRUCache<string, Post>,
    private readonly dbClient: MockPostDbClient,
  ) {}

  // Cache-Aside with LRU
  async findPostById(id: string): Promise<Post> {
    // 1. Check LRU cache
    if (this.lruCache.has(id)) {
      console.log(`## LRU-CACHE-HIT: Post ${id}`);
      return this.lruCache.get(id)!;
    }
    console.log(`## LRU-CACHE-MISS: Post ${id}`);

    // 2. On miss, fetch from DB
    const postFromDb = await this.dbClient.findPost(id);
    if (!postFromDb) {
      throw new NotFoundException(`Post ${id} not found`);
    }

    // 3. Store in LRU cache
    this.lruCache.set(id, postFromDb);
    console.log(`## LRU-CACHE-SET: Post ${id}`);
    return postFromDb;
  }

  // Cache Invalidation (Write-through invalidation)
  async updatePostTitle(id: string, title: string): Promise<Post> {
    const updatedPost = await this.dbClient.updatePost(id, { title });
    if (!updatedPost) {
      throw new NotFoundException(`Post ${id} not found`);
    }

    // Invalidate by deleting the old entry.
    // A more advanced strategy could be to update the cache (write-through).
    if (this.lruCache.has(id)) {
      this.lruCache.delete(id);
      console.log(`## LRU-CACHE-INVALIDATE: Post ${id}`);
    }
    
    // Optionally, re-cache the new version
    this.lruCache.set(id, updatedPost);
    console.log(`## LRU-CACHE-SET (after update): Post ${id}`);

    return updatedPost;
  }
}

// --- Controller and Module ---

@Controller('lru-posts')
class PostController {
  constructor(private readonly postService: PostServiceWithLru) {}

  @Get(':id')
  getPost(@Param('id', ParseUUIDPipe) id: string) {
    return this.postService.findPostById(id);
  }

  @Put(':id/title')
  updateTitle(@Param('id', ParseUUIDPipe) id: string, @Body('title') title: string) {
    return this.postService.updatePostTitle(id, title);
  }
}

@Module({
  controllers: [PostController],
  providers: [PostServiceWithLru, MockPostDbClient, lruCacheProvider],
})
export class LruPostModule {}