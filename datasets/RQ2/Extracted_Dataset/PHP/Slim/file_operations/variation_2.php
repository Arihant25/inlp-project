<?php

/**
 * Variation 2: Single Action Controller ("Fat Controller") Pattern
 *
 * This developer prefers grouping related actions into a single controller class.
 * It's a common pattern in many MVC frameworks.
 * - The controller's constructor receives all dependencies needed by its methods.
 * - Each public method in the controller corresponds to a specific route.
 * - Logic is contained within the controller methods, which can become large.
 *
 * To Run This Code:
 * 1. `composer require slim/slim slim/psr7 league/csv intervention/image ramsey/uuid`
 * 2. Create directories: `mkdir -p public uploads/post_images temp`
 * 3. Place this file in `public/index.php`.
 * 4. Run `php -S localhost:8080 -t public`
 * 5. Use a tool like Postman to send requests:
 *    - POST localhost:8080/api/users/import (multipart/form-data, key: 'user_csv', value: a CSV file)
 *    - POST localhost:8080/api/posts/123e4567-e89b-12d3-a456-426614174000/image (multipart/form-data, key: 'post_image', value: an image)
 *    - GET localhost:8080/api/posts/export
 */

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Psr\Http\Message\UploadedFileInterface;
use Slim\Factory\AppFactory;
use Slim\Psr7\Stream;
use Ramsey\Uuid\Uuid;
use League\Csv\Reader;
use League\Csv\Writer;
use Intervention\Image\ImageManager;

require __DIR__ . '/../vendor/autoload.php';

// Mock Data Store
class MockPostStore {
    public static function getPosts(): array {
        return [
            ['id' => Uuid::uuid4()->toString(), 'user_id' => Uuid::uuid4()->toString(), 'title' => 'Post A', 'content' => 'Content A', 'status' => 'PUBLISHED'],
            ['id' => Uuid::uuid4()->toString(), 'user_id' => Uuid::uuid4()->toString(), 'title' => 'Post B', 'content' => 'Content B', 'status' => 'DRAFT'],
        ];
    }
}

class FileOperationsController {
    private ImageManager $image_manager;
    private string $upload_path;
    private string $temp_path;

    public function __construct(ImageManager $img_manager) {
        $this->image_manager = $img_manager;
        $this->upload_path = __DIR__ . '/../uploads';
        $this->temp_path = __DIR__ . '/../temp';
    }

    public function importUsers(Request $request, Response $response): Response {
        $uploaded_files = $request->getUploadedFiles();
        $csv_file = $uploaded_files['user_csv'] ?? null;

        if (!$csv_file || $csv_file->getError() !== UPLOAD_ERR_OK) {
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Invalid file upload.']));
            return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
        }

        // Temporary file management: move to a temp dir first
        $temp_file_path = $this->moveFileToTemp($csv_file);

        try {
            $csv = Reader::createFromPath($temp_file_path, 'r');
            $csv->setHeaderOffset(0);
            $records = iterator_to_array($csv->getRecords());
            $imported_count = 0;

            foreach ($records as $record) {
                if (!empty($record['email']) && !empty($record['role'])) {
                    // Simulate creating a User and saving to DB
                    // $user = new User($record['email'], $record['role']);
                    // $userRepository->save($user);
                    $imported_count++;
                }
            }
            
            $payload = json_encode([
                'status' => 'success',
                'message' => 'Users imported.',
                'imported_count' => $imported_count,
                'total_records' => count($records)
            ]);
            $response->getBody()->write($payload);
            return $response->withHeader('Content-Type', 'application/json');

        } catch (\Exception $e) {
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Failed to parse CSV.']));
            return $response->withStatus(500)->withHeader('Content-Type', 'application/json');
        } finally {
            // Cleanup the temporary file
            if (file_exists($temp_file_path)) {
                unlink($temp_file_path);
            }
        }
    }

    public function uploadPostImage(Request $request, Response $response, array $args): Response {
        $post_id = $args['id'];
        $uploaded_files = $request->getUploadedFiles();
        $image_file = $uploaded_files['post_image'] ?? null;

        if (!$image_file || $image_file->getError() !== UPLOAD_ERR_OK) {
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Invalid image upload.']));
            return $response->withStatus(400)->withHeader('Content-Type', 'application/json');
        }

        $image_path = $this->upload_path . '/post_images/';
        if (!is_dir($image_path)) mkdir($image_path, 0775, true);

        $file_name = $post_id . '.webp';
        $destination = $image_path . $file_name;

        try {
            $image = $this->image_manager->make($image_file->getStream()->getContents());
            $image->fit(1200, 630)->encode('webp', 80);
            $image->save($destination);

            $payload = json_encode(['status' => 'success', 'url' => '/uploads/post_images/' . $file_name]);
            $response->getBody()->write($payload);
            return $response->withHeader('Content-Type', 'application/json');
        } catch (\Exception $e) {
            $response->getBody()->write(json_encode(['status' => 'error', 'message' => 'Could not process image.']));
            return $response->withStatus(500)->withHeader('Content-Type', 'application/json');
        }
    }

    public function exportPosts(Request $request, Response $response): Response {
        $posts = MockPostStore::getPosts();
        
        $handle = fopen('php://memory', 'w');
        $csv = Writer::createFromStream($handle);
        
        $csv->insertOne(['id', 'title', 'status']);
        foreach ($posts as $post) {
            $csv->insertOne([$post['id'], $post['title'], $post['status']]);
        }
        
        rewind($handle);
        $stream = new Stream($handle);

        return $response->withHeader('Content-Type', 'text/csv')
                        ->withHeader('Content-Disposition', 'attachment; filename="posts_export.csv"')
                        ->withBody($stream);
    }

    private function moveFileToTemp(UploadedFileInterface $file): string {
        $filename = uniqid('upload_', true);
        $path = $this->temp_path . DIRECTORY_SEPARATOR . $filename;
        $file->moveTo($path);
        return $path;
    }
}

// ============== BOOTSTRAP & ROUTING ==============

$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// Instantiate dependencies
$imageManager = new ImageManager(['driver' => 'gd']);
$fileController = new FileOperationsController($imageManager);

// Group routes for this controller
$app->group('/api', function ($group) use ($fileController) {
    $group->post('/users/import', [$fileController, 'importUsers']);
    $group->post('/posts/{id}/image', [$fileController, 'uploadPostImage']);
    $group->get('/posts/export', [$fileController, 'exportPosts']);
});

$app->run();