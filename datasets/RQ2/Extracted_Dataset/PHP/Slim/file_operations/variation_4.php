<?php

/**
 * Variation 4: Service-Oriented with Invokable Controllers (Single Action Controllers)
 *
 * This developer builds on ADR/SOLID principles, creating small, focused, single-purpose classes.
 * - Each route maps to a single class that does one thing.
 * - The class is "invokable" because it has an `__invoke` method, making it behave like a function.
 * - This pattern leads to highly cohesive, loosely coupled, and easily testable code.
 * - A dependency injection container is essential for this pattern.
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
use Psr\Http\Message\StreamFactoryInterface;
use Slim\Factory\AppFactory;
use Ramsey\Uuid\Uuid;
use League\Csv\Reader;
use League\Csv\Writer;
use Intervention\Image\ImageManager;

require __DIR__ . '/../vendor/autoload.php';

// ============== SHARED SERVICES & MOCKS ==============

// A mock repository to simulate fetching Post data
class MockPostRepository {
    public function fetchAllForExport(): array {
        return [
            ['id' => Uuid::uuid4()->toString(), 'title' => 'Invokable Post 1', 'status' => 'PUBLISHED'],
            ['id' => Uuid::uuid4()->toString(), 'title' => 'Invokable Post 2', 'status' => 'DRAFT'],
        ];
    }
}

// A utility service for handling file uploads
class UploadService {
    private string $tempDir;
    public function __construct() {
        $this->tempDir = __DIR__ . '/../temp';
    }
    public function handle(Request $request, string $fileKey): ?string {
        $uploadedFiles = $request->getUploadedFiles();
        $uploadedFile = $uploadedFiles[$fileKey] ?? null;

        if ($uploadedFile && $uploadedFile->getError() === UPLOAD_ERR_OK) {
            $tempPath = $this->tempDir . '/' . Uuid::uuid4()->toString();
            $uploadedFile->moveTo($tempPath);
            return $tempPath;
        }
        return null;
    }
}

// ============== INVOKABLE CONTROLLERS (ACTIONS) ==============

final class ImportUsersAction {
    private UploadService $uploadService;
    public function __construct(UploadService $uploadService) {
        $this->uploadService = $uploadService;
    }

    public function __invoke(Request $request, Response $response): Response {
        $tempPath = $this->uploadService->handle($request, 'users_file');
        if ($tempPath === null) {
            $response->getBody()->write(json_encode(['error' => 'File upload is required.']));
            return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
        }

        $reader = Reader::createFromPath($tempPath, 'r');
        $reader->setHeaderOffset(0);
        $recordCount = count($reader);

        // In a real app, you'd iterate and save users to a database.
        // foreach ($reader->getRecords() as $record) { ... }

        unlink($tempPath); // Temporary file management

        $response->getBody()->write(json_encode([
            'message' => 'User import job started.',
            'records_found' => $recordCount
        ]));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

final class ProcessPostImageAction {
    private UploadService $uploadService;
    private ImageManager $imageManager;
    public function __construct(UploadService $uploadService, ImageManager $imageManager) {
        $this->uploadService = $uploadService;
        $this->imageManager = $imageManager;
    }

    public function __invoke(Request $request, Response $response, array $args): Response {
        $postId = $args['id'];
        $tempPath = $this->uploadService->handle($request, 'image_file');
        if ($tempPath === null) {
            $response->getBody()->write(json_encode(['error' => 'Image file is required.']));
            return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
        }

        $uploadDir = __DIR__ . '/../uploads/post_images';
        if (!is_dir($uploadDir)) mkdir($uploadDir, 0775, true);
        
        $finalPath = $uploadDir . '/' . $postId . '.jpg';

        $this->imageManager->make($tempPath)
            ->fit(800, 600)
            ->save($finalPath, 80);

        unlink($tempPath); // Temporary file management

        $response->getBody()->write(json_encode([
            'message' => 'Image processed and saved.',
            'path' => '/uploads/post_images/' . $postId . '.jpg'
        ]));
        return $response->withHeader('Content-Type', 'application/json');
    }
}

final class DownloadPostsReportAction {
    private MockPostRepository $postRepo;
    private StreamFactoryInterface $streamFactory;
    public function __construct(MockPostRepository $postRepo, StreamFactoryInterface $streamFactory) {
        $this->postRepo = $postRepo;
        $this->streamFactory = $streamFactory;
    }

    public function __invoke(Request $request, Response $response): Response {
        $posts = $this->postRepo->fetchAllForExport();
        
        $resource = fopen('php://temp', 'r+');
        $csv = Writer::createFromStream($resource);
        $csv->insertOne(['ID', 'Title', 'Status']);
        foreach ($posts as $post) {
            $csv->insertOne([$post['id'], $post['title'], $post['status']]);
        }
        
        $stream = $this->streamFactory->createStreamFromResource($resource);

        return $response
            ->withHeader('Content-Type', 'application/csv')
            ->withHeader('Content-Disposition', 'attachment; filename="posts-report-' . date('Y-m-d') . '.csv"')
            ->withBody($stream);
    }
}

// ============== BOOTSTRAP & ROUTING ==============

$container = new Container();
$container->set(ImageManager::class, fn() => new ImageManager(['driver' => 'gd']));

AppFactory::setContainer($container);
$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// Make the StreamFactory available for injection
$container->set(StreamFactoryInterface::class, $app->getResponseFactory()->getStreamFactory());

$app->post('/users/import', ImportUsersAction::class);
$app->post('/posts/{id}/image', ProcessPostImageAction::class);
$app->get('/posts/export', DownloadPostsReportAction::class);

$app->run();