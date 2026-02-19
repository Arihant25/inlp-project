<pre>&lt;?php
/**
 * Variation 4: Single Responsibility Principle (SRP) OOP
 * Author: An "architect" developer who prefers breaking down logic into small, focused classes.
 * Style: Multiple classes, each with a single, well-defined purpose. Promotes composition over inheritance.
 */

// --- Domain Schema and Mocks ---

namespace FileOpsArch {

    enum UserRole { case ADMIN; case USER; }
    enum PostStatus { case DRAFT; case PUBLISHED; }

    class User {
        public readonly string $id;
        public function __construct(
            public string $email,
            public UserRole $role
        ) {
            $this->id = 'user-' . bin2hex(random_bytes(8));
        }
    }

    class UploadedFile {
        public function __construct(
            public readonly string $fieldName,
            public readonly string $clientFilename,
            public readonly string $mediaType,
            public readonly string $tempPath,
            public readonly int $size
        ) {}

        public function __destruct() {
            // Ensure temp file is removed when object is destroyed
            if (file_exists($this->tempPath)) {
                unlink($this->tempPath);
            }
        }
    }

    // --- SRP Classes ---

    /**
     * Parses a multipart/form-data stream into a structured request object.
     */
    class MultipartParser {
        public function parse(string $body, string $contentType): array {
            if (!preg_match('/boundary=(.*)$/', $contentType, $matches)) {
                throw new \InvalidArgumentException("Boundary not found in Content-Type header.");
            }
            $boundary = $matches[1];
            $parts = explode('--' . $boundary, $body);
            array_pop($parts); // Remove last empty part

            $fields = [];
            $files = [];

            foreach ($parts as $part) {
                if (empty(trim($part))) continue;
                $part = ltrim($part, "\r\n");
                list($header, $content) = explode("\r\n\r\n", $part, 2);
                $content = substr($content, 0, -2); // remove trailing \r\n

                preg_match('/name="([^"]+)"/', $header, $nameMatch);
                $name = $nameMatch[1] ?? null;
                if (!$name) continue;

                if (preg_match('/filename="([^"]+)"/', $header, $filenameMatch)) {
                    $filename = $filenameMatch[1];
                    preg_match('/Content-Type: (.*)/', $header, $typeMatch);
                    $type = $typeMatch[1] ?? 'application/octet-stream';
                    
                    $tempPath = tempnam(sys_get_temp_dir(), 'srp_');
                    file_put_contents($tempPath, $content);
                    
                    $files[$name] = new UploadedFile($name, $filename, $type, $tempPath, strlen($content));
                } else {
                    $fields[$name] = $content;
                }
            }
            return ['fields' => $fields, 'files' => $files];
        }
    }

    /**
     * Imports User objects from a CSV file.
     */
    class CsvUserImporter {
        /**
         * @param string $filePath
         * @return User[]
         */
        public function import(string $filePath): array {
            $users = [];
            $handle = fopen($filePath, 'r');
            if (!$handle) {
                throw new \RuntimeException("Could not open file: {$filePath}");
            }
            fgetcsv($handle); // Skip header
            while (($data = fgetcsv($handle)) !== false) {
                $email = $data[0] ?? null;
                $roleStr = strtoupper($data[1] ?? 'USER');
                $role = UserRole::tryFrom($roleStr) ?? UserRole::USER;
                if ($email) {
                    $users[] = new User($email, $role);
                }
            }
            fclose($handle);
            return $users;
        }
    }

    /**
     * Processes images (e.g., resizing).
     */
    class ImageProcessor {
        public function resize(string $sourcePath, string $destPath, int $maxWidth): void {
            if (!file_exists($sourcePath)) {
                throw new \InvalidArgumentException("Source file does not exist: {$sourcePath}");
            }
            $sizeInfo = getimagesize($sourcePath);
            if (!$sizeInfo) {
                throw new \RuntimeException("Could not get image size for: {$sourcePath}");
            }
            list($width, $height, $type) = $sizeInfo;
            
            $sourceImage = match ($type) {
                IMAGETYPE_JPEG => imagecreatefromjpeg($sourcePath),
                IMAGETYPE_PNG => imagecreatefrompng($sourcePath),
                default => throw new \UnsupportedOperationException("Unsupported image type."),
            };

            $ratio = $height / $width;
            $newWidth = $maxWidth;
            $newHeight = (int)($newWidth * $ratio);

            $newImage = imagecreatetruecolor($newWidth, $newHeight);
            imagecopyresampled($newImage, $sourceImage, 0, 0, 0, 0, $newWidth, $newHeight, $width, $height);
            
            $ext = strtolower(pathinfo($destPath, PATHINFO_EXTENSION));
            $success = match ($ext) {
                'jpg', 'jpeg' => imagejpeg($newImage, $destPath),
                'png' => imagepng($newImage, $destPath),
                default => throw new \UnsupportedOperationException("Unsupported destination format."),
            };

            imagedestroy($sourceImage);
            imagedestroy($newImage);

            if (!$success) {
                throw new \RuntimeException("Failed to save resized image to: {$destPath}");
            }
        }
    }

    /**
     * Streams a file to the client for download.
     */
    class FileStreamer {
        public function stream(string $filePath, string $clientFilename): void {
            if (!is_readable($filePath)) {
                // In a web context, you'd send a 404 header.
                throw new \RuntimeException("File not found or is not readable.");
            }
            // In CLI, headers are not sent, but this shows the logic.
            if (php_sapi_name() !== 'cli') {
                header('Content-Type: application/octet-stream');
                header('Content-Disposition: attachment; filename="' . $clientFilename . '"');
                header('Content-Length: ' . filesize($filePath));
            }
            $handle = fopen($filePath, 'rb');
            fpassthru($handle);
            fclose($handle);
        }
    }
    
    /**
     * Manages temporary files using RAII (Resource Acquisition Is Initialization) principle.
     */
    class TempFile {
        private $handle;
        public readonly string $path;
        public function __construct(string $prefix = 'tmp_') {
            $this->path = tempnam(sys_get_temp_dir(), $prefix);
            $this->handle = fopen($this->path, 'w+');
        }
        public function getHandle() { return $this->handle; }
        public function __destruct() {
            if ($this->handle) {
                fclose($this->handle);
            }
            if (file_exists($this->path)) {
                unlink($this->path);
            }
        }
    }
}

// --- Demonstration ---
namespace {
    // This block will only run when the script is executed directly.
    if (basename(__FILE__) === basename($_SERVER['SCRIPT_FILENAME'])) {
        echo "--- Running SRP Architecture Demo ---\n\n";
        
        // Instantiate our service objects
        $parser = new FileOpsArch\MultipartParser();
        $importer = new FileOpsArch\CsvUserImporter();
        $processor = new FileOpsArch\ImageProcessor();
        $streamer = new FileOpsArch\FileStreamer();

        // 1. --- Simulate a request and parse it ---
        $boundary = 'boundary-string-for-srp-demo';
        $contentType = 'multipart/form-data; boundary=' . $boundary;

        $csv = "email,role\n"
             . "alice@example.com,ADMIN\n"
             . "dave@example.com,USER\n";
        
        ob_start();
        $img = imagecreatetruecolor(50, 50);
        imagefill($img, 0, 0, imagecolorallocate($img, 100, 200, 100));
        imagepng($img);
        imagedestroy($img);
        $imgData = ob_get_clean();

        $body = "--{$boundary}\r\n"
              . "Content-Disposition: form-data; name=\"post_id\"\r\n\r\n"
              . "post-abc-123\r\n"
              . "--{$boundary}\r\n"
              . "Content-Disposition: form-data; name=\"user_list\"; filename=\"staff.csv\"\r\n"
              . "Content-Type: text/csv\r\n\r\n"
              . $csv . "\r\n"
              . "--{$boundary}\r\n"
              . "Content-Disposition: form-data; name=\"header_image\"; filename=\"banner.png\"\r\n"
              . "Content-Type: image/png\r\n\r\n"
              . $imgData . "\r\n"
              . "--{$boundary}--\r\n";

        try {
            echo "1. Parsing request with MultipartParser...\n";
            $request = $parser->parse($body, $contentType);
            echo "Parsed fields: " . implode(', ', array_keys($request['fields'])) . "\n";
            echo "Parsed files: " . implode(', ', array_keys($request['files'])) . "\n\n";

            // 2. --- Use CsvUserImporter on the uploaded file ---
            /** @var FileOpsArch\UploadedFile $userListFile */
            $userListFile = $request['files']['user_list'];
            echo "2. Importing users with CsvUserImporter from {$userListFile->clientFilename}...\n";
            $users = $importer->import($userListFile->tempPath);
            echo "Imported " . count($users) . " users.\n";
            print_r($users);
            echo "\n";

            // 3. --- Use ImageProcessor on the uploaded image ---
            /** @var FileOpsArch\UploadedFile $headerImageFile */
            $headerImageFile = $request['files']['header_image'];
            $resizedPath = sys_get_temp_dir() . '/resized_banner.jpg';
            echo "3. Resizing image with ImageProcessor to {$resizedPath}...\n";
            $processor->resize($headerImageFile->tempPath, $resizedPath, 25);
            echo "Image resized successfully.\n\n";

            // 4. --- Use FileStreamer to download the result ---
            echo "4. Streaming resized file with FileStreamer...\n";
            echo "(In a real app, this would trigger a download of 'banner-small.jpg')\n";
            // $streamer->stream($resizedPath, 'banner-small.jpg');
            if (file_exists($resizedPath)) unlink($resizedPath);
            echo "\n";
            
            // 5. --- Use TempFile for safe temporary file handling ---
            echo "5. Demonstrating TempFile for safe temporary data...\n";
            $tempFile = new FileOpsArch\TempFile('post_content_');
            fwrite($tempFile->getHandle(), "This is content for a new post.");
            echo "Wrote to temporary file: {$tempFile->path}\n";
            // The file will be automatically deleted when $tempFile goes out of scope.
            unset($tempFile);
            echo "TempFile object destroyed, file automatically cleaned up.\n";

        } catch (Exception $e) {
            echo "An error occurred: " . $e->getMessage() . "\n";
        }
        // UploadedFile objects will be destructed here, cleaning up their temp files.
    }
}
?&gt;</pre>