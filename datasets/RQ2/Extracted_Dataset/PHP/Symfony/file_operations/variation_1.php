<?php

// Variation 1: The "By-the-Book" Service-Oriented Developer
// This developer uses thin controllers and delegates all logic to dedicated,
// single-responsibility services. This approach is highly testable, scalable,
// and follows SOLID principles.

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

    use Symfony\Component\HttpFoundation\File\Exception\FileException;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\String\Slugger\SluggerInterface;
    use League\Csv\Reader;
    use Intervention\Image\ImageManager;
    use Symfony\Component\Filesystem\Filesystem;
    use Symfony\Component\HttpFoundation\StreamedResponse;
    use App\Domain\Entity\Post;

    class FileUploader
    {
        public function __construct(
            private readonly string $targetDirectory,
            private readonly SluggerInterface $slugger
        ) {}

        public function upload(UploadedFile $file): string
        {
            $originalFilename = pathinfo($file->getClientOriginalName(), PATHINFO_FILENAME);
            $safeFilename = $this->slugger->slug($originalFilename);
            $fileName = $safeFilename.'-'.uniqid().'.'.$file->guessExtension();

            try {
                $file->move($this->getTargetDirectory(), $fileName);
            } catch (FileException $e) {
                // handle exception
                throw new \RuntimeException('Could not move uploaded file: ' . $e->getMessage());
            }

            return $fileName;
        }

        public function getTargetDirectory(): string
        {
            return $this->targetDirectory;
        }
    }

    class CsvUserService
    {
        private Filesystem $filesystem;

        public function __construct() {
            $this->filesystem = new Filesystem();
        }

        public function parseAndImport(UploadedFile $file): array
        {
            $tempPath = $this->filesystem->tempnam(sys_get_temp_dir(), 'csv_import_');
            $file->move(dirname($tempPath), basename($tempPath));

            $csv = Reader::createFromPath($tempPath);
            $csv->setHeaderOffset(0);

            $usersData = [];
            foreach ($csv->getRecords() as $record) {
                // In a real app, you'd create User entities and persist them.
                $usersData[] = [
                    'email' => $record['email'],
                    'role' => $record['role'],
                ];
            }

            $this->filesystem->remove($tempPath);

            return $usersData;
        }
    }

    class PostImageProcessor
    {
        public const THUMBNAIL_WIDTH = 200;
        public const THUMBNAIL_HEIGHT = 200;

        public function __construct(
            private readonly FileUploader $fileUploader,
            private readonly ImageManager $imageManager
        ) {}

        public function process(UploadedFile $imageFile): array
        {
            $imageFilename = $this->fileUploader->upload($imageFile);
            $fullPath = $this->fileUploader->getTargetDirectory() . '/' . $imageFilename;
            $thumbnailFilename = 'thumb-' . $imageFilename;
            $thumbnailPath = $this->fileUploader->getTargetDirectory() . '/' . $thumbnailFilename;

            $image = $this->imageManager->make($fullPath);
            $image->fit(self::THUMBNAIL_WIDTH, self::THUMBNAIL_HEIGHT);
            $image->save($thumbnailPath);

            return [
                'original' => $imageFilename,
                'thumbnail' => $thumbnailFilename,
            ];
        }
    }

    class PostCsvExporter
    {
        /**
         * @param Post[] $posts
         */
        public function export(array $posts): StreamedResponse
        {
            $response = new StreamedResponse();
            $response->setCallback(function () use ($posts) {
                $handle = fopen('php://output', 'w+');
                fputcsv($handle, ['id', 'title', 'status']);

                foreach ($posts as $post) {
                    fputcsv($handle, [
                        $post->id,
                        $post->title,
                        $post->status->value,
                    ]);
                }

                fclose($handle);
            });

            $response->headers->set('Content-Type', 'text/csv; charset=utf-8');
            $response->headers->set('Content-Disposition', 'attachment; filename="posts.csv"');

            return $response;
        }
    }
}

namespace App\Controller {

    use App\Domain\Entity\Post;
    use App\Domain\Entity\PostStatus;
    use App\Service\CsvUserService;
    use App\Service\PostCsvExporter;
    use App\Service\PostImageProcessor;
    use Ramsey\Uuid\Uuid;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\Routing\Annotation\Route;

    #[Route('/api/v1/files')]
    class FileOperationsController extends AbstractController
    {
        public function __construct(
            private readonly CsvUserService $csvUserService,
            private readonly PostImageProcessor $postImageProcessor,
            private readonly PostCsvExporter $postCsvExporter
        ) {}

        #[Route('/users/import', name: 'user_import_csv', methods: ['POST'])]
        public function importUsersFromCsv(Request $request): JsonResponse
        {
            /** @var UploadedFile|null $file */
            $file = $request->files->get('users_csv');

            if (!$file) {
                return $this->json(['error' => 'No file uploaded.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $importedUsers = $this->csvUserService->parseAndImport($file);
                return $this->json([
                    'message' => 'Users imported successfully.',
                    'count' => count($importedUsers),
                    'data' => $importedUsers
                ], Response::HTTP_CREATED);
            } catch (\Exception $e) {
                return $this->json(['error' => $e->getMessage()], Response::HTTP_INTERNAL_SERVER_ERROR);
            }
        }

        #[Route('/posts/{id}/image', name: 'post_image_upload', methods: ['POST'])]
        public function uploadPostImage(Request $request, string $id): JsonResponse
        {
            /** @var UploadedFile|null $file */
            $file = $request->files->get('post_image');

            if (!$file) {
                return $this->json(['error' => 'No image uploaded.'], Response::HTTP_BAD_REQUEST);
            }
            // In a real app, you'd find the Post entity here.
            if (!Uuid::isValid($id)) {
                 return $this->json(['error' => 'Invalid post ID.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $paths = $this->postImageProcessor->process($file);
                return $this->json([
                    'message' => 'Image processed successfully.',
                    'postId' => $id,
                    'paths' => $paths
                ]);
            } catch (\Exception $e) {
                return $this->json(['error' => 'Image processing failed: ' . $e->getMessage()], Response::HTTP_INTERNAL_SERVER_ERROR);
            }
        }

        #[Route('/posts/export', name: 'posts_export_csv', methods: ['GET'])]
        public function exportPostsToCsv(): Response
        {
            // In a real app, you'd fetch these from a repository.
            $mockPosts = [
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'First Post', 'Content here', PostStatus::PUBLISHED),
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'Second Post', 'More content', PostStatus::DRAFT),
            ];

            return $this->postCsvExporter->export($mockPosts);
        }
    }
}

?>