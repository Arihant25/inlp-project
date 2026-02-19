<pre>&lt;?php
/**
 * Variation 2: Static Utility Class
 * Author: A developer who likes to group related functions into a logical, non-instantiable class.
 * Style: Uses a final class with public static methods. Adopts camelCase for methods and PascalCase for the class.
 */

// --- Domain Schema and Mocks ---

final class UserRole {
    const ADMIN = 'ADMIN';
    const USER = 'USER';
}

final class PostStatus {
    const DRAFT = 'DRAFT';
    const PUBLISHED = 'PUBLISHED';
}

class User {
    public string $id;
    public string $email;
    public string $password_hash;
    public string $role;
    public bool $is_active;
    public int $created_at;

    public function __construct(string $email, string $role) {
        $this->id = uniqid('user_');
        $this->email = $email;
        $this->password_hash = password_hash('secret', PASSWORD_DEFAULT);
        $this->role = $role;
        $this->is_active = true;
        $this->created_at = time();
    }
}

// --- Static Utility Class for File Operations ---

final class FileUtils {

    /**
     * Parses a raw multipart/form-data request body.
     * @param string $rawBody The raw request body.
     * @param string $contentTypeHeader The Content-Type header value.
     * @return array|null Parsed data as ['post' => [...], 'files' => [...]].
     */
    public static function parseMultipartRequest(string $rawBody, string $contentTypeHeader): ?array {
        $matches = [];
        if (!preg_match('/boundary=(.*)$/', $contentTypeHeader, $matches)) {
            return null;
        }
        $boundary = $matches[1];
        if (!$boundary) return null;

        $result = ['post' => [], 'files' => []];
        $blocks = preg_split("/-?-?{$boundary}/", $rawBody);
        array_pop($blocks);

        foreach ($blocks as $block) {
            if (empty($block)) continue;

            list($headerBlock, $body) = explode("\r\n\r\n", $block, 2);
            $body = substr($body, 0, strlen($body) - 2); // remove trailing \r\n

            $nameMatch = [];
            preg_match('/name="([^"]*)"/i', $headerBlock, $nameMatch);
            $fieldName = $nameMatch[1] ?? null;
            if (!$fieldName) continue;

            $filenameMatch = [];
            preg_match('/filename="([^"]*)"/i', $headerBlock, $filenameMatch);
            $fileName = $filenameMatch[1] ?? null;

            if ($fileName) {
                $contentTypeMatch = [];
                preg_match('/Content-Type: (.*)?/i', $headerBlock, $contentTypeMatch);
                $fileType = $contentTypeMatch[1] ?? 'application/octet-stream';
                
                $tmpPath = tempnam(sys_get_temp_dir(), 'util_');
                file_put_contents($tmpPath, $body);

                $result['files'][$fieldName] = [
                    'name' => $fileName,
                    'type' => $fileType,
                    'tmp_name' => $tmpPath,
                    'error' => UPLOAD_ERR_OK,
                    'size' => filesize($tmpPath)
                ];
            } else {
                $result['post'][$fieldName] = $body;
            }
        }
        return $result;
    }

    /**
     * Reads a CSV file and converts it into an array of User objects.
     * @param string $filePath The path to the CSV file.
     * @return User[] An array of User objects.
     */
    public static function importUsersFromCsv(string $filePath): array {
        $users = [];
        if (!file_exists($filePath) || !is_readable($filePath)) {
            return [];
        }
        $handle = fopen($filePath, 'r');
        fgetcsv($handle); // Assume and skip header
        while (($row = fgetcsv($handle)) !== false) {
            if (isset($row[0], $row[1])) {
                $users[] = new User(trim($row[0]), trim($row[1]));
            }
        }
        fclose($handle);
        return $users;
    }

    /**
     * Resizes an image and applies an optional grayscale filter.
     * @param string $sourcePath The source image file.
     * @param string $destinationPath The path to save the new image.
     * @param int $maxWidth The maximum width of the new image.
     * @param bool $grayscale Whether to convert the image to grayscale.
     * @return bool True on success, false on failure.
     */
    public static function processImage(string $sourcePath, string $destinationPath, int $maxWidth, bool $grayscale = false): bool {
        if (!extension_loaded('gd')) return false;
        
        list($width, $height, $type) = getimagesize($sourcePath);
        $sourceImage = match ($type) {
            IMAGETYPE_JPEG => imagecreatefromjpeg($sourcePath),
            IMAGETYPE_PNG => imagecreatefrompng($sourcePath),
            IMAGETYPE_GIF => imagecreatefromgif($sourcePath),
            default => false,
        };

        if (!$sourceImage) return false;

        $ratio = $height / $width;
        $newWidth = $maxWidth;
        $newHeight = (int)($maxWidth * $ratio);

        $thumb = imagecreatetruecolor($newWidth, $newHeight);
        imagecopyresampled($thumb, $sourceImage, 0, 0, 0, 0, $newWidth, $newHeight, $width, $height);

        if ($grayscale) {
            imagefilter($thumb, IMG_FILTER_GRAYSCALE);
        }

        $success = imagejpeg($thumb, $destinationPath, 85);

        imagedestroy($sourceImage);
        imagedestroy($thumb);
        return $success;
    }

    /**
     * Streams a file to the browser for download.
     * @param string $filePath The path of the file to stream.
     * @param string $asFilename The filename to suggest to the browser.
     * @return void
     */
    public static function streamDownload(string $filePath, string $asFilename): void {
        if (!file_exists($filePath)) {
            http_response_code(404);
            die('File not found.');
        }

        // In a real app, these headers would be sent. In CLI, they are ignored.
        header('Content-Type: application/octet-stream');
        header("Content-Transfer-Encoding: Binary");
        header("Content-disposition: attachment; filename=\"" . $asFilename . "\"");
        header('Content-Length: ' . filesize($filePath));
        
        readfile($filePath);
    }
    
    /**
     * Creates and cleans up a temporary file within a callback.
     * @param callable $callback The function to execute with the temp file handle.
     * @return mixed The return value of the callback.
     */
    public static function withTemporaryFile(callable $callback) {
        $handle = tmpfile();
        $result = $callback($handle);
        fclose($handle); // Automatically deletes the file
        return $result;
    }
}

// --- Demonstration ---
if (basename(__FILE__) === basename($_SERVER['SCRIPT_FILENAME'])) {
    echo "--- Running Static Utility Class Demo ---\n\n";

    // 1. --- Simulate and parse an upload ---
    $boundary = 'a1b2c3d4e5f6';
    $contentType = 'multipart/form-data; boundary=' . $boundary;

    $csvData = "email,role\n"
             . "carol@dev.null,USER\n"
             . "manager@corp.com,ADMIN\n";
    
    $img = imagecreatetruecolor(20, 20);
    imagefill($img, 0, 0, imagecolorallocate($img, 0, 0, 255)); // Blue square
    ob_start();
    imagepng($img);
    $imgData = ob_get_clean();
    imagedestroy($img);

    $rawBody = "--{$boundary}\r\n"
             . "Content-Disposition: form-data; name=\"user_data\"; filename=\"import.csv\"\r\n"
             . "Content-Type: text/csv\r\n\r\n"
             . $csvData . "\r\n"
             . "--{$boundary}\r\n"
             . "Content-Disposition: form-data; name=\"profile_pic\"; filename=\"avatar.png\"\r\n"
             . "Content-Type: image/png\r\n\r\n"
             . $imgData . "\r\n"
             . "--{$boundary}--\r\n";

    echo "1. Parsing multipart request...\n";
    $requestData = FileUtils::parseMultipartRequest($rawBody, $contentType);
    print_r($requestData);
    echo "\n";

    // 2. --- Process CSV file ---
    if (isset($requestData['files']['user_data'])) {
        echo "2. Importing users from CSV...\n";
        $csvPath = $requestData['files']['user_data']['tmp_name'];
        $users = FileUtils::importUsersFromCsv($csvPath);
        echo "Found " . count($users) . " users.\n";
        print_r($users);
        unlink($csvPath);
        echo "\n";
    }

    // 3. --- Process image file ---
    if (isset($requestData['files']['profile_pic'])) {
        echo "3. Processing uploaded image...\n";
        $imagePath = $requestData['files']['profile_pic']['tmp_name'];
        $processedPath = sys_get_temp_dir() . '/processed_avatar.jpg';
        if (FileUtils::processImage($imagePath, $processedPath, 150, true)) {
            echo "Image processed and saved to {$processedPath}\n";
        }
        unlink($imagePath);
        echo "\n";
    }

    // 4. --- Temporary file management ---
    echo "4. Using withTemporaryFile helper...\n";
    $tempData = FileUtils::withTemporaryFile(function($handle) {
        fwrite($handle, "Post content generated at " . date('Y-m-d H:i:s'));
        rewind($handle);
        return stream_get_contents($handle);
    });
    echo "Data from temporary file: '{$tempData}'\n\n";

    // 5. --- Download demonstration ---
    echo "5. Demonstrating file download...\n";
    if (isset($processedPath) && file_exists($processedPath)) {
        echo "Simulating download of {$processedPath} as 'user_avatar.jpg'.\n";
        // FileUtils::streamDownload($processedPath, 'user_avatar.jpg');
        unlink($processedPath);
    }
}
?&gt;</pre>