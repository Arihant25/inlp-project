<?php

// Variation 4: The "Action-Domain-Responder" (ADR) / Single-Action Controller Developer
// This developer creates a separate controller class for each action (endpoint).
// This promotes the Single Responsibility Principle at the controller level,
// leading to small, focused, and easily understandable classes.

// --- Mocks and Stubs for Compilability ---

namespace League\Csv {
    class Reader {
        public static function createFromPath(string $path): self { return new self(); }
        public function setHeaderOffset(int $offset): self { return $this; }
        public function getRecords(): \Generator {
            yield ['email' => 'test1@example.com', 'role' => 'USER'];
            yield ['email' => 'test2@example.com', 'role' => 'ADMIN'];
        }
    }
}

namespace Intervention\Image {
    interface Image {}
    class ImageManager {
        public function make(string $path): Image { return new class implements Image {}; }
        public function canvas(int $w, int $h): Image { return new class implements Image {}; }
    }
}

namespace App\Domain\Entity {

    use Ramsey\Uuid\Uuid;

    enum UserRole: string { case ADMIN = 'ADMIN'; case USER = 'USER'; }
    enum PostStatus: string { case DRAFT = 'DRAFT'; case PUBLISHED = 'PUBLISHED'; }

    class User
    {
        public function __construct(
            public string $id,
            public string $email,
            public string $password_hash,
            public UserRole $role,
            public bool $is_active,
            public \DateTimeImmutable $created_at
        ) {}
    }

    class Post
    {
        public function __construct(
            public string $id,
            public string $user_id,
            public string $title,
            public string $content,
            public PostStatus $status
        ) {}
    }
}

namespace App\Service {

    use Intervention\Image\ImageManager;
    use League\Csv\Reader;
    use Symfony\Component\Filesystem\Filesystem;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\String\Slugger\SluggerInterface;

    class CsvParser
    {
        public function __construct(private readonly Filesystem $filesystem) {}

        public function parse(UploadedFile $file): iterable
        {
            $tempPath = $this->filesystem->tempnam(sys_get_temp_dir(), 'csv_');
            $file->move(dirname($tempPath), basename($tempPath));

            try {
                $csv = Reader::createFromPath($tempPath);
                $csv->setHeaderOffset(0);
                return $csv->getRecords();
            } finally {
                $this->filesystem->remove($tempPath);
            }
        }
    }

    class ImageResizer
    {
        public function __construct(
            private readonly ImageManager $imageManager,
            private readonly SluggerInterface $slugger,
            private readonly string $publicPath
        ) {}

        public function processAndSave(UploadedFile $file, int $width, int $height): string
        {
            $originalFilename = pathinfo($file->getClientOriginalName(), PATHINFO_FILENAME);
            $safeFilename = $this->slugger->slug($originalFilename);
            $newFilename = $safeFilename.'-'.uniqid().'.'.$file->guessExtension();

            $file->move($this->publicPath, $newFilename);

            $image = $this->imageManager->make($this->publicPath . '/' . $newFilename);
            $image->fit($width, $height);
            $image->save(); // Overwrites the original with the resized version

            return $newFilename;
        }
    }
}

namespace App\Controller\Action {

    use App\Domain\Entity\Post;
    use App\Domain\Entity\PostStatus;
    use App\Service\CsvParser;
    use App\Service\ImageResizer;
    use Ramsey\Uuid\Uuid;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\HttpFoundation\StreamedResponse;
    use Symfony\Component\Routing\Annotation\Route;

    #[Route('/actions/users/import', name: 'action_user_import', methods: ['POST'])]
    class UploadUserCsvAction
    {
        public function __construct(private readonly CsvParser $csvParser) {}

        public function __invoke(Request $request): JsonResponse
        {
            /** @var UploadedFile|null $file */
            $file = $request->files->get('csv_file');
            if (!$file) {
                return new JsonResponse(['error' => 'File not found.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $records = iterator_to_array($this->csvParser->parse($file));
                // In a real app, you would dispatch a message or call a user creation service.
                return new JsonResponse(['message' => 'Import successful', 'records' => count($records)], Response::HTTP_OK);
            } catch (\Exception $e) {
                return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_INTERNAL_SERVER_ERROR);
            }
        }
    }

    #[Route('/actions/posts/{id}/image', name: 'action_post_image', methods: ['POST'])]
    class UploadPostImageAction
    {
        public function __construct(private readonly ImageResizer $imageResizer) {}

        public function __invoke(Request $request, string $id): JsonResponse
        {
            /** @var UploadedFile|null $file */
            $file = $request->files->get('image');
            if (!$file) {
                return new JsonResponse(['error' => 'Image not found.'], Response::HTTP_BAD_REQUEST);
            }
            if (!Uuid::isValid($id)) {
                 return new JsonResponse(['error' => 'Invalid post ID.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $filename = $this->imageResizer->processAndSave($file, 300, 200);
                // In a real app, you would update the Post entity with the new filename.
                return new JsonResponse(['postId' => $id, 'image_path' => $filename], Response::HTTP_OK);
            } catch (\Exception $e) {
                return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_INTERNAL_SERVER_ERROR);
            }
        }
    }

    #[Route('/actions/posts/export', name: 'action_posts_export', methods: ['GET'])]
    class DownloadPostsCsvAction
    {
        public function __invoke(): StreamedResponse
        {
            // Mock fetching posts from a repository
            $posts = [
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'ADR Pattern', '...', PostStatus::PUBLISHED),
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'Invokable Controllers', '...', PostStatus::PUBLISHED),
            ];

            $response = new StreamedResponse();
            $response->setCallback(function () use ($posts) {
                $handle = fopen('php://output', 'w');
                fputcsv($handle, ['id', 'title', 'status']);
                foreach ($posts as $post) {
                    fputcsv($handle, [$post->id, $post->title, $post->status->value]);
                }
                fclose($handle);
            });

            $response->headers->set('Content-Type', 'text/csv');
            $response->headers->set('Content-Disposition', 'attachment; filename="posts.csv"');

            return $response;
        }
    }
}

?>