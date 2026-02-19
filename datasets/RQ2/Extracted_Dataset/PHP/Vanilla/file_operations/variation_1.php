<pre>&lt;?php
/**
 * Variation 1: Procedural/Functional Approach
 * Author: A pragmatic developer who prefers straightforward, functional code.
 * Style: Uses snake_case for functions and variables. Minimal abstraction.
 */

// --- Domain Schema and Mocks ---

// Using constants to simulate Enums
const ROLE_ADMIN = 'ADMIN';
const ROLE_USER = 'USER';
const STATUS_DRAFT = 'DRAFT';
const STATUS_PUBLISHED = 'PUBLISHED';

// Mock User and Post structures (using associative arrays)
function create_user(string $id, string $email, string $role): array {
    return [
        'id' => $id,
        'email' => $email,
        'password_hash' => password_hash('password123', PASSWORD_DEFAULT),
        'role' => $role,
        'is_active' => true,
        'created_at' => time()
    ];
}

// --- Core File Operation Functions ---

/**
 * Manually parses a multipart/form-data stream.
 * @param resource $stream The input stream (e.g., from php://input).
 * @param string $boundary The boundary string from the Content-Type header.
 * @return array An associative array with 'post' and 'files' keys.
 */
function parse_multipart_formdata($stream, string $boundary): array {
    if (!$stream || !$boundary) {
        return ['post' => [], 'files' => []];
    }

    $result = ['post' => [], 'files' => []];
    $stream_contents = stream_get_contents($stream);
    $parts = explode('--' . $boundary, $stream_contents);
    array_pop($parts); // Last part is empty

    foreach ($parts as $part) {
        if (empty($part)) continue;

        // Separate headers from content
        $part = ltrim($part, "\r\n");
        list($raw_headers, $body) = explode("\r\n\r\n", $part, 2);
        $raw_headers = explode("\r\n", $raw_headers);
        
        $headers = [];
        foreach ($raw_headers as $header) {
            list($name, $value) = explode(':', $header, 2);
            $headers[strtolower($name)] = ltrim($value, ' ');
        }

        if (isset($headers['content-disposition'])) {
            preg_match('/name="([^"]+)"/', $headers['content-disposition'], $name_match);
            preg_match('/filename="([^"]+)"/', $headers['content-disposition'], $filename_match);
            
            $name = $name_match[1] ?? null;
            $filename = $filename_match[1] ?? null;

            if ($name && $filename) {
                // It's a file
                $tmp_path = tempnam(sys_get_temp_dir(), 'upload_');
                file_put_contents($tmp_path, $body);
                $result['files'][$name] = [
                    'name' => $filename,
                    'type' => $headers['content-type'] ?? 'application/octet-stream',
                    'tmp_name' => $tmp_path,
                    'error' => 0,
                    'size' => strlen($body)
                ];
            } elseif ($name) {
                // It's a POST field
                $result['post'][$name] = substr($body, 0, -2); // Remove trailing \r\n
            }
        }
    }
    return $result;
}

/**
 * Parses a CSV file containing user data.
 * @param string $file_path Path to the CSV file.
 * @return array A list of User-like associative arrays.
 */
function parse_users_from_csv(string $file_path): array {
    $users = [];
    if (($handle = fopen($file_path, "r")) !== FALSE) {
        fgetcsv($handle); // Skip header row
        while (($data = fgetcsv($handle, 1000, ",")) !== FALSE) {
            if (count($data) >= 2) {
                $users[] = create_user(uniqid(), trim($data[0]), trim($data[1]));
            }
        }
        fclose($handle);
    }
    return $users;
}

/**
 * Resizes an image to a specified width, maintaining aspect ratio.
 * @param string $source_path Path to the source image.
 * @param string $dest_path Path to save the resized image.
 * @param int $target_width The target width.
 * @return bool True on success, false on failure.
 */
function resize_image(string $source_path, string $dest_path, int $target_width): bool {
    $image_info = getimagesize($source_path);
    if (!$image_info) return false;

    list($width, $height, $type) = $image_info;
    $source_image = null;
    switch ($type) {
        case IMAGETYPE_JPEG: $source_image = imagecreatefromjpeg($source_path); break;
        case IMAGETYPE_PNG: $source_image = imagecreatefrompng($source_path); break;
        case IMAGETYPE_GIF: $source_image = imagecreatefromgif($source_path); break;
        default: return false;
    }

    if (!$source_image) return false;

    $aspect_ratio = $height / $width;
    $target_height = (int)($target_width * $aspect_ratio);

    $dest_image = imagecreatetruecolor($target_width, $target_height);
    imagecopyresampled($dest_image, $source_image, 0, 0, 0, 0, $target_width, $target_height, $width, $height);

    $success = false;
    $dest_ext = strtolower(pathinfo($dest_path, PATHINFO_EXTENSION));
    switch ($dest_ext) {
        case 'jpg':
        case 'jpeg': $success = imagejpeg($dest_image, $dest_path, 90); break;
        case 'png': $success = imagepng($dest_image, $dest_path, 9); break;
        case 'gif': $success = imagegif($dest_image, $dest_path); break;
    }

    imagedestroy($source_image);
    imagedestroy($dest_image);
    return $success;
}

/**
 * Streams a file to the client for download.
 * @param string $file_path The path to the file to download.
 * @param string $download_name The name the file should have when downloaded.
 */
function stream_file_download(string $file_path, string $download_name): void {
    if (!file_exists($file_path) || !is_readable($file_path)) {
        header("HTTP/1.1 404 Not Found");
        echo "File not found.";
        return;
    }

    header('Content-Description: File Transfer');
    header('Content-Type: application/octet-stream');
    header('Content-Disposition: attachment; filename="' . basename($download_name) . '"');
    header('Expires: 0');
    header('Cache-Control: must-revalidate');
    header('Pragma: public');
    header('Content-Length: ' . filesize($file_path));
    
    $file_handle = fopen($file_path, 'rb');
    while (!feof($file_handle)) {
        echo fread($file_handle, 4096);
        flush();
    }
    fclose($file_handle);
}

/**
 * Manages a temporary file for some processing.
 * @return string The content written to the temporary file.
 */
function manage_temporary_file(): string {
    // tmpfile() creates a file that is automatically deleted when closed.
    $temp_handle = tmpfile();
    $metadata = stream_get_meta_data($temp_handle);
    $temp_filename = $metadata['uri'];
    
    echo "Created temporary file: {$temp_filename}\n";
    
    fwrite($temp_handle, "This is some temporary data for a post.");
    
    // Rewind and read the content back
    fseek($temp_handle, 0);
    $content = fread($temp_handle, 1024);
    
    // The file is automatically removed when fclose is called.
    fclose($temp_handle);
    echo "Temporary file has been closed and removed.\n";
    
    return $content;
}


// --- Demonstration ---
// This block will only run when the script is executed directly.
if (basename(__FILE__) === basename($_SERVER['SCRIPT_FILENAME'])) {
    echo "--- Running Procedural File Operations Demo ---\n\n";

    // 1. --- Simulate a multipart/form-data upload ---
    $boundary = '----WebKitFormBoundary7MA4YWxkTrZu0gW';
    $_SERVER['CONTENT_TYPE'] = 'multipart/form-data; boundary=' . $boundary;

    // Mock CSV data
    $csv_content = "email,role\n"
                 . "test1@example.com,USER\n"
                 . "admin@example.com,ADMIN\n";

    // Mock image data (create a tiny 10x10 red PNG)
    $im = imagecreatetruecolor(10, 10);
    imagefill($im, 0, 0, imagecolorallocate($im, 255, 0, 0));
    ob_start();
    imagepng($im);
    $image_content = ob_get_clean();
    imagedestroy($im);

    // Construct the raw HTTP body
    $mock_body = "--{$boundary}\r\n"
               . "Content-Disposition: form-data; name=\"post_title\"\r\n\r\n"
               . "My Awesome Post\r\n"
               . "--{$boundary}\r\n"
               . "Content-Disposition: form-data; name=\"user_import\"; filename=\"users.csv\"\r\n"
               . "Content-Type: text/csv\r\n\r\n"
               . $csv_content . "\r\n"
               . "--{$boundary}\r\n"
               . "Content-Disposition: form-data; name=\"post_image\"; filename=\"red_square.png\"\r\n"
               . "Content-Type: image/png\r\n\r\n"
               . $image_content . "\r\n"
               . "--{$boundary}--\r\n";

    // Use a memory stream to simulate php://input
    $input_stream = fopen('php://memory', 'r+');
    fwrite($input_stream, $mock_body);
    rewind($input_stream);

    echo "1. Parsing simulated multipart request...\n";
    $parsed_request = parse_multipart_formdata($input_stream, $boundary);
    fclose($input_stream);

    print_r($parsed_request['post']);
    print_r($parsed_request['files']);
    echo "\n";

    // 2. --- Process the uploaded CSV ---
    if (isset($parsed_request['files']['user_import'])) {
        echo "2. Parsing uploaded CSV file...\n";
        $csv_file = $parsed_request['files']['user_import']['tmp_name'];
        $imported_users = parse_users_from_csv($csv_file);
        echo "Imported " . count($imported_users) . " users:\n";
        print_r($imported_users);
        unlink($csv_file); // Clean up temp file
        echo "\n";
    }

    // 3. --- Process the uploaded image ---
    if (isset($parsed_request['files']['post_image'])) {
        echo "3. Resizing uploaded image...\n";
        $image_file = $parsed_request['files']['post_image']['tmp_name'];
        $resized_image_path = sys_get_temp_dir() . '/resized_image.jpg';
        if (resize_image($image_file, $resized_image_path, 100)) {
            echo "Image resized successfully to {$resized_image_path}\n";
            // In a real app, you would move this file to permanent storage.
            // For the demo, we'll use it for the download part.
        } else {
            echo "Image resizing failed.\n";
        }
        unlink($image_file); // Clean up original temp file
        echo "\n";
    }

    // 4. --- Demonstrate Temporary File Management ---
    echo "4. Demonstrating temporary file management...\n";
    $temp_content = manage_temporary_file();
    echo "Content from temp file: '{$temp_content}'\n\n";

    // 5. --- Demonstrate File Download Streaming ---
    echo "5. Demonstrating file download...\n";
    echo "(Headers would be sent to the browser if not in CLI mode)\n";
    if (isset($resized_image_path) && file_exists($resized_image_path)) {
        // In a real web context, the function call below would trigger a download.
        // stream_file_download($resized_image_path, 'my-downloaded-image.jpg');
        echo "Would stream file '{$resized_image_path}' as 'my-downloaded-image.jpg'.\n";
        unlink($resized_image_path); // Clean up
    } else {
        echo "Resized image not available for download demo.\n";
    }
}
?&gt;</pre>