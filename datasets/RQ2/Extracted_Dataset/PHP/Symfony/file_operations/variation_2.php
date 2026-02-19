<?php

// Variation 2: The "Pragmatic" All-in-One Controller Developer
// This developer prefers to keep related logic together within the controller,
// using private methods for organization. This can be faster for smaller projects
// but may become harder to test and maintain as complexity grows.

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

namespace App\Controller {

    use App\Domain\Entity\Post;
    use App\Domain\Entity\PostStatus;
    use Intervention\Image\ImageManager;
    use League\Csv\Reader;
    use Ramsey\Uuid\Uuid;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\Filesystem\Filesystem;
    use Symfony\Component\HttpFoundation\File\Exception\FileException;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\HttpFoundation\StreamedResponse;
    use Symfony\Component\Routing\Annotation\Route;
    use Symfony\Component\String\Slugger\SluggerInterface;

    #[Route('/pragmatic/files')]
    class PragmaticFileController extends AbstractController
    {
        private const THUMB_WIDTH = 250;

        public function __construct(
            private readonly string $uploadPath,
            private readonly SluggerInterface $slugger,
            private readonly Filesystem $fs,
            private readonly ImageManager $imgManager
        ) {}

        #[Route('/upload-users', name: 'pragmatic_upload_users', methods: ['POST'])]
        public function uploadUsers(Request $request): JsonResponse
        {
            /** @var UploadedFile|null $csvFile */
            $csvFile = $request->files->get('user_data');
            if (!$csvFile) {
                return new JsonResponse(['error' => 'Missing user_data file.'], Response::HTTP_BAD_REQUEST);
            }

            $tempFilePath = $this->fs->tempnam(sys_get_temp_dir(), 'import_');
            try {
                $csvFile->move(dirname($tempFilePath), basename($tempFilePath));
                $userData = $this->parseCsvFile($tempFilePath);
                // Here you would typically create User entities and persist them.
            } catch (\Exception $e) {
                return new JsonResponse(['error' => 'Processing failed: ' . $e->getMessage()], Response::HTTP_UNPROCESSABLE_ENTITY);
            } finally {
                $this->fs->remove($tempFilePath);
            }

            return new JsonResponse([
                'message' => 'User data processed.',
                'user_count' => count($userData),
            ], Response::HTTP_OK);
        }

        #[Route('/posts/{id}/process-image', name: 'pragmatic_process_image', methods: ['POST'])]
        public function processImage(Request $request, string $id): JsonResponse
        {
            /** @var UploadedFile|null $imageFile */
            $imageFile = $request->files->get('image');
            if (!$imageFile || !str_starts_with($imageFile->getMimeType(), 'image/')) {
                return new JsonResponse(['error' => 'Valid image file is required.'], Response::HTTP_BAD_REQUEST);
            }
            // Mock finding a post
            if (!Uuid::isValid($id)) {
                 return new JsonResponse(['error' => 'Invalid post ID.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $originalFilename = $this->saveUploadedFile($imageFile);
                $thumbnailFilename = $this->createThumbnail($originalFilename);
            } catch (FileException $e) {
                return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_INTERNAL_SERVER_ERROR);
            }

            return new JsonResponse([
                'postId' => $id,
                'original' => $originalFilename,
                'thumbnail' => $thumbnailFilename,
            ]);
        }

        #[Route('/download-posts', name: 'pragmatic_download_posts', methods: ['GET'])]
        public function downloadPosts(): StreamedResponse
        {
            // Mock data from a repository
            $posts = [
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'Symfony is great', '...', PostStatus::PUBLISHED),
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'File Operations', '...', PostStatus::PUBLISHED),
            ];

            $response = new StreamedResponse(function () use ($posts) {
                $output = fopen('php://output', 'wb');
                fputcsv($output, ['ID', 'Title', 'Content', 'Status']);
                foreach ($posts as $post) {
                    fputcsv($output, [$post->id, $post->title, $post->content, $post->status->value]);
                }
                fclose($output);
            });

            $response->headers->set('Content-Type', 'text/csv');
            $response->headers->set('Content-Disposition', 'attachment; filename="posts_export.csv"');

            return $response;
        }

        private function parseCsvFile(string $path): array
        {
            $reader = Reader::createFromPath($path);
            $reader->setHeaderOffset(0);
            return iterator_to_array($reader->getRecords());
        }

        private function saveUploadedFile(UploadedFile $file): string
        {
            $safeFilename = $this->slugger->slug(pathinfo($file->getClientOriginalName(), PATHINFO_FILENAME));
            $newFilename = $safeFilename . '-' . uniqid() . '.' . $file->guessExtension();

            $file->move($this->uploadPath, $newFilename);

            return $newFilename;
        }

        private function createThumbnail(string $originalFilename): string
        {
            $sourcePath = $this->uploadPath . '/' . $originalFilename;
            $thumbFilename = 'thumb_' . $originalFilename;
            $destPath = $this->uploadPath . '/' . $thumbFilename;

            $image = $this->imgManager->make($sourcePath);
            $image->fit(self::THUMB_WIDTH, self::THUMB_WIDTH);
            $image->save($destPath);

            return $thumbFilename;
        }
    }
}

?>