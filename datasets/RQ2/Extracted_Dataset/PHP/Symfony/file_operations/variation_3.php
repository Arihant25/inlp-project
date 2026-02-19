<?php

// Variation 3: The "Functional/Procedural" Developer with a Static Helper
// This developer abstracts file-related logic into a static utility class.
// The controller remains simple, calling static methods from the helper.
// This approach avoids dependency injection for the helper class but can make
// testing the utility functions in isolation more difficult.

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
        public function __construct() {}
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

namespace App\Util {

    use Intervention\Image\ImageManager;
    use League\Csv\Reader;
    use Symfony\Component\Filesystem\Filesystem;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\HttpFoundation\StreamedResponse;
    use Symfony\Component\String\Slugger\AsciiSlugger;

    class FileUtil
    {
        public static function handleUpload(UploadedFile $file, string $destination): string
        {
            $slugger = new AsciiSlugger();
            $originalFilename = pathinfo($file->getClientOriginalName(), PATHINFO_FILENAME);
            $safeFilename = $slugger->slug($originalFilename);
            $fileName = $safeFilename.'-'.uniqid().'.'.$file->guessExtension();

            $file->move($destination, $fileName);

            return $fileName;
        }

        public static function parseCsvToGenerator(UploadedFile $file): \Generator
        {
            $fs = new Filesystem();
            $tempPath = $fs->tempnam(sys_get_temp_dir(), 'csv_');
            $file->move(dirname($tempPath), basename($tempPath));

            try {
                $csv = Reader::createFromPath($tempPath);
                $csv->setHeaderOffset(0);
                foreach ($csv->getRecords() as $record) {
                    yield $record;
                }
            } finally {
                $fs->remove($tempPath);
            }
        }

        public static function resizeImage(string $sourcePath, string $targetPath, int $width, int $height): void
        {
            // In a real app, this might be injected or configured globally.
            $imageManager = new ImageManager();
            $image = $imageManager->make($sourcePath);
            $image->fit($width, $height);
            $image->save($targetPath);
        }

        public static function createCsvStreamResponse(array $data, array $headers, string $filename): StreamedResponse
        {
            $response = new StreamedResponse();
            $response->setCallback(function() use ($data, $headers) {
                $handle = fopen('php://output', 'r+');
                fputcsv($handle, $headers);
                foreach ($data as $row) {
                    fputcsv($handle, (array) $row);
                }
                fclose($handle);
            });

            $response->headers->set('Content-Type', 'text/csv; charset=utf-8');
            $response->headers->set('Content-Disposition', "attachment; filename=\"{$filename}\"");

            return $response;
        }
    }
}

namespace App\Controller {

    use App\Domain\Entity\Post;
    use App\Domain\Entity\PostStatus;
    use App\Util\FileUtil;
    use Ramsey\Uuid\Uuid;
    use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
    use Symfony\Component\HttpFoundation\File\UploadedFile;
    use Symfony\Component\HttpFoundation\JsonResponse;
    use Symfony\Component\HttpFoundation\Request;
    use Symfony\Component\HttpFoundation\Response;
    use Symfony\Component\Routing\Annotation\Route;

    #[Route('/functional/files')]
    class FunctionalStyleController extends AbstractController
    {
        #[Route('/users/batch-create', name: 'functional_user_create', methods: ['POST'])]
        public function handle_user_upload(Request $request): JsonResponse
        {
            /** @var UploadedFile|null $file */
            $file = $request->files->get('users');
            if (!$file) {
                return $this->json(['status' => 'error', 'message' => 'No file provided.'], 400);
            }

            $users = [];
            try {
                foreach (FileUtil::parseCsvToGenerator($file) as $record) {
                    $users[] = $record;
                    // In a real app, create and persist User entities here.
                }
            } catch (\Exception $e) {
                return $this->json(['status' => 'error', 'message' => $e->getMessage()], 500);
            }

            return $this->json(['status' => 'success', 'users_processed' => count($users)]);
        }

        #[Route('/posts/{id}/thumbnail', name: 'functional_post_thumbnail', methods: ['POST'])]
        public function handle_post_thumbnail(Request $request, string $id): JsonResponse
        {
            $uploadDir = $this->getParameter('kernel.project_dir') . '/public/uploads';
            /** @var UploadedFile|null $file */
            $file = $request->files->get('image');

            if (!$file) {
                return $this->json(['status' => 'error', 'message' => 'No image provided.'], 400);
            }
            if (!Uuid::isValid($id)) {
                 return $this->json(['error' => 'Invalid post ID.'], Response::HTTP_BAD_REQUEST);
            }

            try {
                $filename = FileUtil::handleUpload($file, $uploadDir);
                $sourcePath = $uploadDir . '/' . $filename;
                $thumbPath = $uploadDir . '/thumb-' . $filename;
                FileUtil::resizeImage($sourcePath, $thumbPath, 150, 150);
            } catch (\Exception $e) {
                return $this->json(['status' => 'error', 'message' => 'Could not process image.'], 500);
            }

            return $this->json(['status' => 'success', 'thumbnail' => 'thumb-' . $filename]);
        }

        #[Route('/posts/report', name: 'functional_posts_report', methods: ['GET'])]
        public function download_posts_csv(): Response
        {
            // Mock data
            $posts = [
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'Post A', 'Content A', PostStatus::DRAFT),
                new Post(Uuid::uuid4()->toString(), Uuid::uuid4()->toString(), 'Post B', 'Content B', PostStatus::PUBLISHED),
            ];

            $data = array_map(fn($post) => ['id' => $post->id, 'title' => $post->title, 'status' => $post->status->value], $posts);
            $headers = ['ID', 'Title', 'Status'];

            return FileUtil::createCsvStreamResponse($data, $headers, 'post_report.csv');
        }
    }
}

?>