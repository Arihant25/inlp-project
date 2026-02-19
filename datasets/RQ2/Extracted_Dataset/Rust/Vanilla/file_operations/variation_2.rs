<pre>
use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::env;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Uuid([u8; 16]);

impl Uuid {
    fn new_mock(val: u8) -> Self { Uuid([val; 16]) }
    fn to_string(&self) -> String { format!("{:x}", self.0.iter().fold(0u128, |acc, &x| (acc << 8) | x as u128)) }
}

#[derive(Debug)]
pub enum UserRole { Admin, StandardUser }
#[derive(Debug)]
pub enum PublicationStatus { Draft, Published }

#[derive(Debug)]
pub struct User {
    id: Uuid,
    email: String,
    _password_hash: String,
    role: UserRole,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug)]
pub struct Post {
    _id: Uuid,
    _user_id: Uuid,
    _title: String,
    _content: String,
    _status: PublicationStatus,
}

// --- Object-Oriented Implementation ---

/// Represents a single part of a multipart form payload.
struct MultipartPart {
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

impl MultipartPart {
    fn get_filename(&self) -> Option<String> {
        self.headers.get("content-disposition")
            .and_then(|v| v.split(';').find(|s| s.trim().starts_with("filename=")))
            .map(|s| s.split('=').nth(1).unwrap_or("").trim_matches('"').to_string())
    }
}

/// A handler for processing different file types.
enum ProcessedFile {
    UserCsv(Vec<User>),
    ProcessedImage(Vec<u8>),
    GenericFile(PathBuf),
}

/// Manages all file-related operations.
struct FileManager {
    temp_dir: PathBuf,
}

impl FileManager {
    /// Creates a new FileManager, ensuring the temp directory exists.
    pub fn new() -> io::Result<Self> {
        let temp_dir = env::temp_dir().join("app_temp");
        fs::create_dir_all(&temp_dir)?;
        Ok(Self { temp_dir })
    }

    /// Parses a multipart stream and processes each part.
    pub fn handle_upload(&self, mut stream: impl Read, boundary: &str) -> io::Result<Vec<ProcessedFile>> {
        let parts = self.parse_multipart(stream, boundary)?;
        let mut processed_files = Vec::new();

        for part in parts {
            let filename = part.get_filename().unwrap_or_else(|| "unknown".to_string());
            
            if filename.ends_with(".csv") {
                let users = self.parse_csv_users(&part.body)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                processed_files.push(ProcessedFile::UserCsv(users));
            } else if filename.ends_with(".img") {
                let resized_data = self.process_image(&part.body)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                processed_files.push(ProcessedFile::ProcessedImage(resized_data));
            } else {
                let temp_path = self.temp_dir.join(&filename);
                let mut temp_file = File::create(&temp_path)?;
                temp_file.write_all(&part.body)?;
                processed_files.push(ProcessedFile::GenericFile(temp_path));
            }
        }
        Ok(processed_files)
    }

    /// Streams a file to a given writer.
    pub fn stream_download(&self, file_path: &Path, mut writer: impl Write) -> io::Result<u64> {
        let mut file = File::open(file_path)?;
        io::copy(&mut file, &mut writer)
    }

    // --- Private Helper Methods ---

    fn parse_multipart(&self, mut stream: impl Read, boundary: &str) -> io::Result<Vec<MultipartPart>> {
        // This is a simplified parser for demonstration.
        let mut buffer = Vec::new();
        stream.read_to_end(&mut buffer)?;
        let boundary_bytes = format!("--{}", boundary).into_bytes();
        let mut result_parts = Vec::new();

        for part_data in buffer.split(|b| *b == boundary_bytes[0] && buffer.windows(boundary_bytes.len()).any(|w| w == &boundary_bytes)) {
            if part_data.is_empty() || part_data.len() < 4 { continue; }
            
            let mut headers_end_pos = 0;
            for i in 0..part_data.len() - 3 {
                if &part_data[i..i+4] == b"\r\n\r\n" {
                    headers_end_pos = i;
                    break;
                }
            }
            if headers_end_pos == 0 { continue; }

            let headers_bytes = &part_data[2..headers_end_pos]; // Skip initial \r\n
            let body = part_data[headers_end_pos + 4..part_data.len()-2].to_vec(); // Skip \r\n\r\n and final \r\n
            
            let mut headers = HashMap::new();
            if let Ok(headers_str) = std::str::from_utf8(headers_bytes) {
                for line in headers_str.lines() {
                    if let Some((key, value)) = line.split_once(": ") {
                        headers.insert(key.to_lowercase().to_string(), value.to_string());
                    }
                }
            }
            result_parts.push(MultipartPart { headers, body });
        }
        Ok(result_parts)
    }

    fn parse_csv_users(&self, data: &[u8]) -> Result<Vec<User>, String> {
        let content = std::str::from_utf8(data).map_err(|e| e.to_string())?;
        let mut users = Vec::new();
        for (i, line) in content.lines().skip(1).enumerate() {
            let cols: Vec<&str> = line.split(',').collect();
            if cols.len() != 4 { return Err(format!("Bad CSV line at {}", i+1)); }
            users.push(User {
                id: Uuid::new_mock(i as u8),
                email: cols[0].to_string(),
                _password_hash: cols[1].to_string(),
                role: if cols[2] == "Admin" { UserRole::Admin } else { UserRole::StandardUser },
                is_active: cols[3].parse().unwrap_or(false),
                created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
            });
        }
        Ok(users)
    }

    fn process_image(&self, data: &[u8]) -> Result<Vec<u8>, &'static str> {
        // NOTE: Simulating image processing.
        if !data.starts_with(b"IMAGE_DATA") { return Err("Invalid image signature"); }
        let mut processed = data.to_vec();
        processed.truncate(20); // "Resize" by truncating
        Ok(processed)
    }
}

impl Drop for FileManager {
    fn drop(&mut self) {
        // RAII for temporary directory cleanup
        let _ = fs::remove_dir_all(&self.temp_dir);
    }
}

fn main() {
    println!("--- Variation 2: Object-Oriented Approach ---");

    let file_manager = FileManager::new().expect("Failed to create FileManager");

    // 1. Simulate upload
    let boundary = "boundaryXYZ";
    let mock_http_body = format!(
        "--{0}\r\nContent-Disposition: form-data; name=\"csv_upload\"; filename=\"staff.csv\"\r\n\r\nemail,hash,role,active\r\nstaff1@corp.com,h1,StandardUser,true\r\n\r\n--{0}\r\nContent-Disposition: form-data; name=\"avatar\"; filename=\"avatar.img\"\r\n\r\nIMAGE_DATA_VERY_LONG_BYTE_STREAM\r\n\r\n--{0}--\r\n",
        boundary
    );

    println!("Handling upload...");
    let processed_results = file_manager.handle_upload(mock_http_body.as_bytes(), boundary).unwrap();

    for result in processed_results {
        match result {
            ProcessedFile::UserCsv(users) => {
                // 2. CSV result
                println!("\nSuccessfully parsed CSV. Users found: {}", users.len());
                println!("{:?}", users);
            },
            ProcessedFile::ProcessedImage(img_data) => {
                // 3. Image result
                println!("\nSuccessfully processed image. Resized data: {:?}", std::str::from_utf8(&img_data).unwrap());
                
                // 4. & 5. Create a temp file for download simulation
                let temp_img_path = file_manager.temp_dir.join("downloadable_avatar.img");
                let mut temp_file = File::create(&temp_img_path).unwrap();
                temp_file.write_all(&img_data).unwrap();

                println!("\nSimulating download of processed image...");
                let mut download_destination = Vec::new();
                let bytes_count = file_manager.stream_download(&temp_img_path, &mut download_destination).unwrap();
                println!("Streamed {} bytes. Content: {:?}", bytes_count, std::str::from_utf8(&download_destination).unwrap());
            },
            ProcessedFile::GenericFile(path) => {
                println!("\nSaved a generic file to temporary location: {:?}", path);
            }
        }
    }
    println!("\nFileManager is going out of scope, temporary directory will be cleaned up.");
}
</pre>