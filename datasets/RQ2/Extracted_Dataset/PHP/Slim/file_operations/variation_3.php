<?php

/**
 * Variation 3: Functional / Procedural Style
 *
 * This developer prefers simplicity and avoids classes where possible.
 * - All logic is placed directly within route closures.
 * - Dependencies are often instantiated directly within the closure.
 * - Helper functions may be defined in the global scope to reduce duplication.
 * - This style is fast for prototyping but can be harder to maintain and test.
 *
 * To Run This Code:
 * 1. `composer require slim/slim slim/psr7 league/csv intervention/image ramsey/uuid`
 * 2. Create directories: `mkdir -p public uploads/post_images temp`
 * 3. Place this file in `public/index.php`.
 * 4. Run `php -S localhost:8080 -t public`
 * 5. Use a tool like Postman to send requests:
 *    - POST localhost:8080/import-users-csv (multipart/form-data, key: 'csv_file', value: a CSV file)
 *    - POST localhost:8080/post/123e4567-e89b-12d3-a456-426614174000/upload-image (multipart/form-data, key: 'picture', value: an image)
 *    - GET localhost:8080/export-posts-csv
 */

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;
use Ramsey\Uuid\Uuid;
use League\Csv\Reader;
use League\Csv\Writer;
use Intervention\Image\ImageManager;

require __DIR__ . '/../vendor/autoload.php';

// ============== HELPER FUNCTIONS ==============

function getMockPosts(): array {
    return [
        ['id' => Uuid::uuid4()->toString(), 'user_id' => Uuid::uuid4()->toString(), 'title' => 'Functional Post One', 'status' => 'PUBLISHED'],
        ['id' => Uuid::uuid4()->toString(), 'user_id' => Uuid::uuid4()->toString(), 'title' => 'Functional Post Two', 'status' => 'PUBLISHED'],
        ['id' => Uuid::uuid4()->toString(), 'user_id' => Uuid::uuid4()->toString(), 'title' => 'Draft Post', 'status' => 'DRAFT'],
    ];
}

function createJsonResponse(Response $response, array $data, int $status = 200): Response {
    $response->getBody()->write(json_encode($data));
    return $response->withHeader('Content-Type', 'application/json')->withStatus($status);
}

// ============== BOOTSTRAP & ROUTING ==============

$app = AppFactory::create();
$app->addErrorMiddleware(true, true, true);

// Endpoint 1: CSV User Import
$app->post('/import-users-csv', function (Request $request, Response $response) {
    $uploadedFiles = $request->getUploadedFiles();
    $csvUpload = $uploadedFiles['csv_file'] ?? null;

    if (empty($csvUpload) || $csvUpload->getError() !== UPLOAD_ERR_OK) {
        return createJsonResponse($response, ['error' => 'File upload failed or no file provided.'], 400);
    }

    // Temporary file management: create a temp file from the stream
    $tempPath = tempnam(sys_get_temp_dir(), 'user_csv');
    $csvUpload->moveTo($tempPath);

    try {
        $csvReader = Reader::createFromPath($tempPath, 'r');
        $csvReader->setHeaderOffset(0);
        
        $imported = 0;
        foreach ($csvReader->getRecords() as $record) {
            // Simulate creating a User model and saving it
            if (filter_var($record['email'], FILTER_VALIDATE_EMAIL)) {
                // echo "Importing user: {$record['email']}\n";
                $imported++;
            }
        }
        return createJsonResponse($response, ['message' => "Processed file.", 'users_imported' => $imported]);
    } catch (\Exception $e) {
        return createJsonResponse($response, ['error' => 'Could not parse CSV file.', 'details' => $e->getMessage()], 500);
    } finally {
        // Clean up the temporary file
        unlink($tempPath);
    }
});

// Endpoint 2: Post Image Processing
$app->post('/post/{id}/upload-image', function (Request $request, Response $response, $args) {
    $postId = $args['id'];
    $uploads = $request->getUploadedFiles();
    $imageUpload = $uploads['picture'] ?? null;

    if (empty($imageUpload) || $imageUpload->getError() !== UPLOAD_ERR_OK) {
        return createJsonResponse($response, ['error' => 'Image upload failed.'], 400);
    }

    // Check if it's a valid image type
    $allowedTypes = ['image/jpeg', 'image/png', 'image/gif'];
    if (!in_array($imageUpload->getClientMediaType(), $allowedTypes)) {
        return createJsonResponse($response, ['error' => 'Invalid image type.'], 415);
    }

    try {
        $imageManager = new ImageManager(['driver' => 'gd']);
        $image = $imageManager->make($imageUpload->getStream()->getContents());

        // Resize image
        $image->widen(1024, function ($constraint) {
            $constraint->upsize();
        });

        $uploadDir = __DIR__ . '/../uploads/post_images';
        if (!is_dir($uploadDir)) {
            mkdir($uploadDir, 0775, true);
        }
        $filename = $postId . '-' . time() . '.jpg';
        $image->save($uploadDir . '/' . $filename, 90, 'jpg');

        return createJsonResponse($response, ['success' => true, 'image_path' => '/uploads/post_images/' . $filename]);
    } catch (\Exception $e) {
        return createJsonResponse($response, ['error' => 'Failed to process image.', 'details' => $e->getMessage()], 500);
    }
});

// Endpoint 3: Streaming CSV Download
$app->get('/export-posts-csv', function (Request $request, Response $response) {
    $posts = getMockPosts();
    
    $response = $response
        ->withHeader('Content-Type', 'text/csv')
        ->withHeader('Content-Disposition', 'attachment; filename="posts.csv"');

    $body = $response->getBody();
    
    // Use a callback with the streaming body
    $streamBody = new \Slim\Psr7\Stream(fopen('php://temp', 'r+'));
    $csvWriter = Writer::createFromStream($streamBody->getResource());
    
    // Header
    $csvWriter->insertOne(['id', 'user_id', 'title', 'status']);
    
    // Rows
    foreach ($posts as $post) {
        $csvWriter->insertOne([$post['id'], $post['user_id'], $post['title'], $post['status']]);
    }

    return $response->withBody($streamBody);
});

$app->run();