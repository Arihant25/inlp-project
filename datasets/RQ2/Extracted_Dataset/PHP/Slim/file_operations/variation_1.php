<?php

/**
 * Variation 1: Action-Domain-Responder (ADR) Pattern
 *
 * This developer prefers a strict separation of concerns.
 * - Action: Thin layer to extract data from the request and call the domain.
 * - Domain (Service): Contains all business logic, completely decoupled from HTTP.
 * - Responder: Formats the data from the Domain into an HTTP response.
 * - Dependency Injection is used heavily to wire everything together.
 *
 * To Run This Code:
 * 1. `composer require slim/slim slim/psr7 php-di/php-di league/csv intervention/image ramsey/uuid`
 * 2. Create directories: `mkdir -p public uploads/post_images temp`
 * 3. Place this file in `public/index.php`.
 * 4. Run `php -S localhost:8080 -t public`
 * 5. Use a tool like Postman to send requests:
 *    - POST localhost:8080/users/import (multipart/form-data, key: 'users_file', value: a CSV file)
 *    - POST localhost:8080/posts/123e4567-e89b-12d3-a456-426614174000/image (multipart/form-data, key: 'image_file', value: an image)
 *    - GET localhost:8080/posts/export
 */

use DI\Container;
use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Message\UploadedFileInterface;
use Psr\Http\Message\StreamFactoryInterface;
use Slim\Factory\AppFactory;
use Ramsey\Uuid\Uuid;
use League\Csv\Reader;
use League\Csv\Writer;
use Intervention\Image\ImageManager;

require __DIR__ . '/../vendor/autoload.php';

// ============== DOMAIN LAYER (Services, Repositories, Entities) ==============

// Mock User Entity
class User {
    public string $id;
    public string $email;
    public string $role;
    public function __construct(string $email, string $role) {
        $this->id = Uuid::uuid4()->toString();
        $this->email = $email;
        $this->role = $role;
    }
}

// Mock Post Entity
class Post {
    public string $id;
    public string $user_id;
    public string $title;
    public string $content;
    public string $status;
}

// Mock Repository to simulate database access
class PostRepository {
    public function findAll(): array {
        // In a real app, this would query a database.
        $post1 = new Post();
        $post1->id = Uuid::uuid4()->toString();
        $post1->user_id = Uuid::uuid4()->toString();
        $post1->title = 'First Post';
        $post1->content = 'This is the content of the first post.';
        $post1->status = 'PUBLISHED';

        $post2 = new Post();
        $post2->id = Uuid::uuid4()->toString();
        $post2->user_id = Uuid::uuid4()->toString();
        $post2->title = 'Second Post';
        $post2->content = 'This is the content of the second post.';
        $post2->status = 'DRAFT';

        return [$post1, $post2];
    }
}

// Domain Service for all file-related business logic
class FileService {
    private ImageManager $imageManager;
    private PostRepository $postRepo;
    private string $uploadDir;
    private string $tempDir;

    public function __construct(ImageManager $imageManager, PostRepository $postRepo) {
        $this->imageManager = $imageManager;
        $this->postRepo = $postRepo;
        $this->uploadDir = __DIR__ . '/../uploads';
        $this->tempDir = __DIR__ . '/../temp';
    }

    public function importUsersFromCsv(UploadedFileInterface $uploadedFile): array {
        // Move to a temporary location for processing
        $tempPath = $this->moveUploadedFile($this->tempDir, $uploadedFile);
        
        $csv = Reader::createFromPath($tempPath, 'r');
        $csv->setHeaderOffset(0);
        
        $records = $csv->getRecords();
        $importedCount = 0;
        $failedRecords = [];

        foreach ($records as $record) {
            if (isset($record['email']) && isset($record['role'])) {
                // In a real app, you would create a User entity and persist it.
                // new User($record['email'], $record['role']);
                $importedCount++;
            } else {
                $failedRecords[] = $record;
            }
        }

        // Temporary file management: Clean up the temp file
        unlink($tempPath);

        return ['imported' => $importedCount, 'failures' => count($failedRecords)];
    }

    public function processPostImage(string $postId, UploadedFileInterface $uploadedFile): string {
        $imagePath = $this->uploadDir . '/post_images/';
        $filename = sprintf('%s.%s', $postId, 'jpg');
        $destination = $imagePath . $filename;

        $image = $this->imageManager->make($uploadedFile->getStream()->getContents());
        $image->resize(800, null, function ($constraint) {
            $constraint->aspectRatio();
            $constraint->upsize();
        });
        $image->save($destination, 85, 'jpg');

        return '/uploads/post_images/' . $filename;
    }

    public function exportPostsToCsvStream() {
        $posts = $this->postRepo->findAll();
        $stream = fopen('php://temp', 'r+');
        $csv = Writer::createFromStream($stream);

        $csv->insertOne(['id', 'user_id', 'title', 'status']);
        foreach ($posts as $post) {
            $csv->insertOne([$post->id, $post->user_id, $post->title, $post->status]);
        }
        
        rewind($stream);
        return $stream;
    }

    private function moveUploadedFile(string $directory, UploadedFileInterface $uploadedFile): string {
        $extension = pathinfo($uploadedFile->getClientFilename(), PATHINFO_EXTENSION);
        $basename = bin2hex(random_bytes(8));
        $filename = sprintf('%s.%0.8s', $basename, $extension);
        $path = $directory . DIRECTORY_SEPARATOR . $filename;
        $uploadedFile->moveTo($path);
        return $path;
    }
}

// ============== APPLICATION LAYER (Actions & Responders) ==============

class JsonResponder {
    public function respond(Response $response, $data, int $statusCode = 200): Response {
        $response->getBody()->write(json_encode($data));
        return $response->withHeader('Content-Type', 'application/json')->withStatus($statusCode);
    }
}

abstract class Action {
    protected FileService $fileService;
    protected JsonResponder $responder;

    public function __construct(FileService $fileService, JsonResponder $responder) {
        $this->fileService = $fileService;
        $this->responder = $responder;
    }
}

class UserImportAction extends Action {
    public function __invoke(Request $request, Response $response): Response {
        $uploadedFiles = $request->getUploadedFiles();
        $usersFile = $uploadedFiles['users_file'] ?? null;

        if (!$usersFile || $usersFile->getError() !== UPLOAD_ERR_OK) {
            return $this->responder->respond($response, ['error' => 'File upload failed'], 400);
        }

        $result = $this->fileService->importUsersFromCsv($usersFile);
        return $this->responder->respond($response, ['message' => 'CSV processed successfully', 'data' => $result]);
    }
}

class PostImageUploadAction extends Action {
    public function __invoke(Request $request, Response $response, array $args): Response {
        $postId = $args['id'];
        $uploadedFiles = $request->getUploadedFiles();
        $imageFile = $uploadedFiles['image_file'] ?? null;

        if (!$imageFile || $imageFile->getError() !== UPLOAD_ERR_OK) {
            return $this->responder->respond($response, ['error' => 'Image upload failed'], 400);
        }

        $path = $this->fileService->processPostImage($postId, $imageFile);
        return $this->responder->respond($response, ['message' => 'Image processed', 'path' => $path]);
    }
}

class PostExportAction {
    private FileService $fileService;
    private StreamFactoryInterface $streamFactory;

    public function __construct(FileService $fileService, StreamFactoryInterface $streamFactory) {
        $this->fileService = $fileService;
        $this->streamFactory = $streamFactory;
    }

    public function __invoke(Request $request, Response $response): Response {
        $streamHandle = $this->fileService->exportPostsToCsvStream();
        $stream = $this->streamFactory->createStreamFromResource($streamHandle);

        return $response
            ->withHeader('Content-Type', 'text/csv')
            ->withHeader('Content-Disposition', 'attachment; filename="posts.csv"')
            ->withBody($stream);
    }
}

// ============== BOOTSTRAP & ROUTING ==============

$container = new Container();

// Add dependencies to the container
$container->set(ImageManager::class, function () {
    return new ImageManager(['driver' => 'gd']);
});
$container->set(PostRepository::class, \DI\create(PostRepository::class));
$container->set(FileService::class, \DI\create(FileService::class)
    ->constructor(\DI\get(ImageManager::class), \DI\get(PostRepository::class)));
$container->set(JsonResponder::class, \DI\create(JsonResponder::class));

AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// Add StreamFactory to container for injection
$container->set(StreamFactoryInterface::class, $app->getResponseFactory()->getStreamFactory());

$app->post('/users/import', UserImportAction::class);
$app->post('/posts/{id}/image', PostImageUploadAction::class);
$app->get('/posts/export', PostExportAction::class);

$app->run();