<pre>&lt;?php
/**
 * Variation 3: Object-Oriented Service
 * Author: A developer who prefers classic OOP with stateful services.
 * Style: A service class that is instantiated and can hold state about the file operations.
 *        Uses camelCase for methods/properties and PascalCase for classes.
 */

// --- Domain Schema and Mocks ---

class Domain {
    public static $UserRole = ['ADMIN' => 'ADMIN', 'USER' => 'USER'];
    public static $PostStatus = ['DRAFT' => 'DRAFT', 'PUBLISHED' => 'PUBLISHED'];
}

class UserEntity {
    public string $id;
    public string $email;
    public function __construct(string $email) { $this->id = bin2hex(random_bytes(16)); $this->email = $email; }
}

class PostEntity {
    public string $id;
    public string $title;
    public function __construct(string $title) { $this->id = bin2hex(random_bytes(16)); $this->title = $title; }
}

// --- Stateful Service Class ---

class FileOperationService {
    private ?array $lastUploadedFiles = null;
    private ?array $lastPostData = null;
    private ?string $lastError = null;

    /**
     * Handles a file upload by manually parsing the input stream.
     * @param resource $inputStream The raw input stream (e.g., from fopen('php://input', 'r')).
     * @param string $contentTypeHeader The 'Content-Type' header.
     * @return bool True if parsing was successful, false otherwise.
     */
    public function handleUpload($inputStream, string $contentTypeHeader): bool {
        $this->resetState();
        if (!str_starts_with($contentTypeHeader, 'multipart/form-data')) {
            $this->lastError = "Invalid Content-Type header.";
            return false;
        }

        preg_match('/boundary="?([^"]+)"?/', $contentTypeHeader, $matches);
        $boundary = $matches[1] ?? null;
        if (!$boundary) {
            $this->lastError = "Boundary not found in Content-Type header.";
            return false;
        }

        $postData = [];
        $files = [];
        $streamContents = stream_get_contents($inputStream);
        $parts = explode('--' . $boundary, $streamContents);
        array_pop($parts);

        foreach ($parts as $part) {
            if (empty(trim($part))) continue;
            
            $part = ltrim($part, "\r\n");
            @list($headerContent, $body) = explode("\r\n\r\n", $part, 2);

            if (empty($headerContent) || $body === null) continue;

            $disposition = $this->parseHeaderValue($headerContent, 'Content-Disposition');
            if (!$disposition) continue;

            $name = $this->parseHeaderAttribute($disposition, 'name');
            $filename = $this->parseHeaderAttribute($disposition, 'filename');

            if ($filename !== null) {
                $fileType = $this->parseHeaderValue($headerContent, 'Content-Type') ?? 'application/octet-stream';
                $tmpFile = tmpfile();
                fwrite($tmpFile, $body);
                $meta = stream_get_meta_data($tmpFile);
                
                $files[$name] = [
                    'name' => $filename,
                    'type' => $fileType,
                    'tmp_name' => $meta['uri'],
                    'size' => strlen($body),
                    'error' => 0,
                    '_handle' => $tmpFile // Keep handle to prevent auto-deletion
                ];
            } else if ($name !== null) {
                $postData[$name] = rtrim($body, "\r\n");
            }
        }

        $this->lastUploadedFiles = $files;
        $this->lastPostData = $postData;
        return true;
    }

    /**
     * Retrieves the last parsed non-file POST data.
     * @return array|null
     */
    public function getPostData(): ?array {
        return $this->lastPostData;
    }

    /**
     * Retrieves the last parsed file data.
     * @return array|null
     */
    public function getUploadedFiles(): ?array {
        return $this->lastUploadedFiles;
    }
    
    /**
     * Cleans up temporary files from the last upload.
     */
    public function cleanup() {
        if ($this->lastUploadedFiles) {
            foreach ($this->lastUploadedFiles as $file) {
                if (isset($file['_handle'])) {
                    fclose($file['_handle']);
                } else if (file_exists($file['tmp_name'])) {
                    unlink($file['tmp_name']);
                }
            }
        }
        $this->resetState();
    }

    /**
     * Parses a CSV file from the last upload into User entities.
     * @param string $fileKey The key of the file in the upload data.
     * @return UserEntity[]|null An array of UserEntity objects or null on failure.
     */
    public function getUsersFromUploadedCsv(string $fileKey): ?array {
        if (!isset($this->lastUploadedFiles[$fileKey])) {
            $this->lastError = "File key '{$fileKey}' not found in last upload.";
            return null;
        }
        
        $filePath = $this->lastUploadedFiles[$fileKey]['tmp_name'];
        $users = [];
        $handle = fopen($filePath, 'r');
        fgetcsv($handle); // Skip header
        while (($data = fgetcsv($handle)) !== false) {
            if (!empty($data[0])) {
                $users[] = new UserEntity($data[0]);
            }
        }
        // Don't close handle if it's from tmpfile(), let cleanup() do it.
        if (!isset($this->lastUploadedFiles[$fileKey]['_handle'])) {
            fclose($handle);
        } else {
            rewind($this->lastUploadedFiles[$fileKey]['_handle']);
        }
        
        return $users;
    }

    /**
     * Creates a thumbnail for an image from the last upload.
     * @param string $fileKey The key of the image file.
     * @param string $destinationPath The path to save the thumbnail.
     * @param int $width The thumbnail width.
     * @return bool Success or failure.
     */
    public function createThumbnail(string $fileKey, string $destinationPath, int $width): bool {
        if (!isset($this->lastUploadedFiles[$fileKey])) {
            $this->lastError = "File key '{$fileKey}' not found.";
            return false;
        }
        $sourcePath = $this->lastUploadedFiles[$fileKey]['tmp_name'];
        
        list($w, $h) = getimagesize($sourcePath);
        $src = imagecreatefromstring(file_get_contents($sourcePath));
        $height = (int) (($width / $w) * $h);
        $dst = imagecreatetruecolor($width, $height);
        imagecopyresampled($dst, $src, 0, 0, 0, 0, $width, $height, $w, $h);
        $result = imagejpeg($dst, $destinationPath);
        imagedestroy($src);
        imagedestroy($dst);
        return $result;
    }

    /**
     * Streams a file for download.
     * @param string $path The file path.
     * @param string $name The download name.
     */
    public function downloadFile(string $path, string $name): void {
        if (!file_exists($path)) {
            header("HTTP/1.1 404 Not Found");
            exit;
        }
        header("Content-Type: application/octet-stream");
        header("Content-Disposition: attachment; filename=\"$name\"");
        header("Content-Length: " . filesize($path));
        fpassthru(fopen($path, 'rb'));
    }

    public function getLastError(): ?string { return $this->lastError; }
    private function resetState() { $this->lastUploadedFiles = null; $this->lastPostData = null; $this->lastError = null; }
    private function parseHeaderValue(string $headerContent, string $headerName): ?string {
        if (preg_match('/' . preg_quote($headerName) . ':\s*([^\r\n]+)/i', $headerContent, $matches)) {
            return $matches[1];
        }
        return null;
    }
    private function parseHeaderAttribute(string $headerValue, string $attributeName): ?string {
        if (preg_match('/' . preg_quote($attributeName) . '="([^"]+)"/', $headerValue, $matches)) {
            return $matches[1];
        }
        return null;
    }
}

// --- Demonstration ---
if (basename(__FILE__) === basename($_SERVER['SCRIPT_FILENAME'])) {
    echo "--- Running Object-Oriented Service Demo ---\n\n";

    // 1. --- Simulate an upload and handle it with the service ---
    $boundary = '----Boundary' . bin2hex(random_bytes(8));
    $contentType = 'multipart/form-data; boundary="' . $boundary . '"';

    $csvContent = "email\nbob@builder.com\nalice@architect.com\n";
    $imgContent = file_get_contents(tempnam(sys_get_temp_dir(), 'img')); // dummy content

    $body = "--{$boundary}\r\n"
          . "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
          . "New Project Plan\r\n"
          . "--{$boundary}\r\n"
          . "Content-Disposition: form-data; name=\"users\"; filename=\"team.csv\"\r\n"
          . "Content-Type: text/csv\r\n\r\n"
          . $csvContent . "\r\n"
          . "--{$boundary}\r\n"
          . "Content-Disposition: form-data; name=\"attachment\"; filename=\"diagram.png\"\r\n"
          . "Content-Type: image/png\r\n\r\n"
          . $imgContent . "\r\n"
          . "--{$boundary}--\r\n";

    $stream = fopen('php://memory', 'r+');
    fwrite($stream, $body);
    rewind($stream);

    $fileService = new FileOperationService();
    echo "1. Handling simulated upload...\n";
    if ($fileService->handleUpload($stream, $contentType)) {
        echo "Upload parsed successfully.\n";
        echo "POST data: "; print_r($fileService->getPostData());
        echo "FILES data: "; print_r($fileService->getUploadedFiles());
    } else {
        echo "Upload failed: " . $fileService->getLastError() . "\n";
        exit;
    }
    fclose($stream);
    echo "\n";

    // 2. --- Use service methods to process uploaded data ---
    echo "2. Processing uploaded CSV...\n";
    $users = $fileService->getUsersFromUploadedCsv('users');
    if ($users) {
        echo "Successfully parsed " . count($users) . " users.\n";
    } else {
        echo "Failed to parse users: " . $fileService->getLastError() . "\n";
    }
    echo "\n";

    // 3. --- Create a thumbnail ---
    echo "3. Creating thumbnail from attachment...\n";
    // We need a real image for this part. Let's create one.
    $uploadedFiles = $fileService->getUploadedFiles();
    $imagePath = $uploadedFiles['attachment']['tmp_name'];
    $im = imagecreatetruecolor(200, 150);
    $bgColor = imagecolorallocate($im, 200, 200, 255);
    $textColor = imagecolorallocate($im, 0, 0, 100);
    imagefill($im, 0, 0, $bgColor);
    imagestring($im, 5, 10, 60, 'Diagram Placeholder', $textColor);
    imagepng($im, $imagePath);
    imagedestroy($im);

    $thumbPath = sys_get_temp_dir() . '/thumb.jpg';
    if ($fileService->createThumbnail('attachment', $thumbPath, 50)) {
        echo "Thumbnail created at {$thumbPath}\n";
    } else {
        echo "Thumbnail creation failed: " . $fileService->getLastError() . "\n";
    }
    echo "\n";

    // 4. --- Demonstrate download ---
    echo "4. Demonstrating download...\n";
    echo "Service would stream {$thumbPath} to browser.\n";
    // $fileService->downloadFile($thumbPath, 'project-diagram-thumb.jpg');
    if (file_exists($thumbPath)) unlink($thumbPath);
    echo "\n";
    
    // 5. --- Clean up all temporary files ---
    echo "5. Cleaning up service resources...\n";
    $fileService->cleanup();
    echo "Cleanup complete.\n";
}
?&gt;</pre>