<pre>
use std::io::{self, Read, Write, BufReader, BufRead};
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::env;
use std::fmt;
use std::error::Error;
use std::time::{SystemTime, UNIX_EPOCH};
use std::collections::HashMap;

// --- Domain Schema ---
#[derive(Debug, Clone)]
pub struct Uuid([u8; 16]);
impl Uuid { fn new_mock(val: u8) -> Self { Uuid([val; 16]) } }

#[derive(Debug)]
pub enum Role { ADMIN, USER }
#[derive(Debug)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
pub struct User { id: Uuid, email: String, role: Role, is_active: bool }
#[derive(Debug)]
pub struct Post { _id: Uuid, _user_id: Uuid }

// --- Custom Error Type for the Service ---
#[derive(Debug)]
pub enum FileServiceError {
    Io(io::Error),
    Utf8(std::str::Utf8Error),
    Parse(String),
    Multipart(String),
    ImageProcessing(String),
    NotFound,
}

impl fmt::Display for FileServiceError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            FileServiceError::Io(e) => write!(f, "I/O Error: {}", e),
            FileServiceError::Utf8(e) => write!(f, "UTF-8 Conversion Error: {}", e),
            FileServiceError::Parse(s) => write!(f, "Parsing Error: {}", s),
            FileServiceError::Multipart(s) => write!(f, "Multipart Error: {}", s),
            FileServiceError::ImageProcessing(s) => write!(f, "Image Processing Error: {}", s),
            FileServiceError::NotFound => write!(f, "File not found"),
        }
    }
}

impl Error for FileServiceError {}

impl From<io::Error> for FileServiceError {
    fn from(err: io::Error) -> Self { FileServiceError::Io(err) }
}
impl From<std::str::Utf8Error> for FileServiceError {
    fn from(err: std::str::Utf8Error) -> Self { FileServiceError::Utf8(err) }
}

// --- Service Trait and Implementation ---

pub struct UploadedPart {
    pub filename: Option<String>,
    pub content_type: String,
    pub data: Vec<u8>,
}

/// Defines the contract for file operations.
trait FileOperationService {
    fn process_upload_stream(&self, request_body: impl Read, boundary: &str) -> Result<Vec<UploadedPart>, FileServiceError>;
    fn parse_csv_to_users(&self, csv_data: &[u8]) -> Result<Vec<User>, FileServiceError>;
    fn resize_image_data(&self, image_data: &[u8]) -> Result<Vec<u8>, FileServiceError>;
    fn stream_file_for_download(&self, file_path: &Path, output_stream: &mut impl Write) -> Result<u64, FileServiceError>;
    fn create_temp_file(&self, data: &[u8], prefix: &str) -> Result<TempFileGuard, FileServiceError>;
}

/// A RAII guard to ensure temporary files are deleted.
#[derive(Debug)]
pub struct TempFileGuard {
    path: PathBuf,
}
impl TempFileGuard {
    pub fn path(&self) -> &Path { &self.path }
}
impl Drop for TempFileGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

/// Concrete implementation of the file service.
struct StandardFileService;

impl FileOperationService for StandardFileService {
    fn process_upload_stream(&self, request_body: impl Read, boundary: &str) -> Result<Vec<UploadedPart>, FileServiceError> {
        // A very basic, manual multipart parser.
        let mut reader = BufReader::new(request_body);
        let mut buffer = Vec::new();
        let full_boundary = format!("--{}", boundary);
        let mut parts = Vec::new();

        loop {
            let bytes_read = reader.read_until(b'\n', &mut buffer)?;
            if bytes_read == 0 { break; } // EOF

            if buffer.starts_with(full_boundary.as_bytes()) {
                if buffer.starts_with(format!("{}--", full_boundary).as_bytes()) { break; } // End boundary
                
                let mut headers = HashMap::new();
                loop {
                    buffer.clear();
                    reader.read_until(b'\n', &mut buffer)?;
                    let line = std::str::from_utf8(&buffer)?.trim();
                    if line.is_empty() { break; } // End of headers
                    if let Some((key, value)) = line.split_once(": ") {
                        headers.insert(key.to_lowercase(), value.to_string());
                    }
                }
                
                let disposition = headers.get("content-disposition").ok_or_else(|| FileServiceError::Multipart("Missing Content-Disposition".to_string()))?;
                let filename = disposition.split(';').find(|s| s.trim().starts_with("filename=")).map(|s| s.split('=').nth(1).unwrap_or("").trim_matches('"').to_string());
                let content_type = headers.get("content-type").cloned().unwrap_or_else(|| "application/octet-stream".to_string());

                let mut part_data = Vec::new();
                // This is a simplified data read; a real one would need to handle boundaries within data.
                reader.read_to_end(&mut part_data)?; // Read rest of stream for simplicity
                
                // In a real scenario, we'd read until the next boundary.
                // For this example, we assume one part per stream for simplicity of the parser.
                if let Some(end_idx) = part_data.windows(full_boundary.len()).position(|w| w == full_boundary.as_bytes()) {
                    part_data.truncate(end_idx - 2); // -2 for \r\n
                }

                parts.push(UploadedPart { filename, content_type, data: part_data });
                break; // Simplified: only parse one part
            }
            buffer.clear();
        }
        Ok(parts)
    }

    fn parse_csv_to_users(&self, csv_data: &[u8]) -> Result<Vec<User>, FileServiceError> {
        let content = std::str::from_utf8(csv_data)?;
        content.lines().skip(1).map(|line| {
            let cols: Vec<&str> = line.split(',').collect();
            if cols.len() != 3 { return Err(FileServiceError::Parse("Invalid column count".to_string())); }
            Ok(User {
                id: Uuid::new_mock(0),
                email: cols[0].to_string(),
                role: if cols[1] == "ADMIN" { Role::ADMIN } else { Role::USER },
                is_active: cols[2].parse().map_err(|e| FileServiceError::Parse(format!("Bool parse error: {}", e)))?,
            })
        }).collect()
    }

    fn resize_image_data(&self, image_data: &[u8]) -> Result<Vec<u8>, FileServiceError> {
        // Simulation of image resizing
        if image_data.len() < 10 { return Err(FileServiceError::ImageProcessing("Image too small".to_string())); }
        Ok(image_data[0..10].to_vec())
    }

    fn stream_file_for_download(&self, file_path: &Path, output_stream: &mut impl Write) -> Result<u64, FileServiceError> {
        let mut file = File::open(file_path).map_err(|_| FileServiceError::NotFound)?;
        Ok(io::copy(&mut file, output_stream)?)
    }

    fn create_temp_file(&self, data: &[u8], prefix: &str) -> Result<TempFileGuard, FileServiceError> {
        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let temp_path = env::temp_dir().join(format!("{}_{}", prefix, timestamp));
        fs::write(&temp_path, data)?;
        Ok(TempFileGuard { path: temp_path })
    }
}

fn main() {
    println!("--- Variation 3: Service/Handler Pattern with Error Handling ---");
    let file_service = StandardFileService;

    // --- Scenario 1: Successful CSV Upload and Processing ---
    println!("\n--- Scenario 1: CSV Upload ---");
    let boundary = "abc";
    let csv_body = format!("--{0}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"users.csv\"\r\n\r\nemail,role,is_active\r\nuser1@example.com,USER,true\r\nadmin1@example.com,ADMIN,true\r\n\r\n--{0}--\r\n", boundary);
    
    match file_service.process_upload_stream(csv_body.as_bytes(), boundary) {
        Ok(parts) => {
            if let Some(part) = parts.first() {
                match file_service.parse_csv_to_users(&part.data) {
                    Ok(users) => println!("Successfully parsed {} users: {:?}", users.len(), users),
                    Err(e) => println!("CSV parsing failed: {}", e),
                }
            }
        },
        Err(e) => println!("Upload processing failed: {}", e),
    }

    // --- Scenario 2: Image Processing and Download ---
    println!("\n--- Scenario 2: Image Processing & Download ---");
    let original_image_data = b"RAW_IMAGE_BYTE_STREAM_LONGER_THAN_TEN_BYTES".to_vec();
    match file_service.resize_image_data(&original_image_data) {
        Ok(resized_data) => {
            println!("Image resized successfully. Data: {:?}", resized_data);
            match file_service.create_temp_file(&resized_data, "resized_img") {
                Ok(temp_file_guard) => {
                    println!("Temp file created at: {:?}", temp_file_guard.path());
                    let mut download_buffer = Vec::new();
                    match file_service.stream_file_for_download(temp_file_guard.path(), &mut download_buffer) {
                        Ok(bytes) => println!("Streamed {} bytes for download.", bytes),
                        Err(e) => println!("Download failed: {}", e),
                    }
                }, // temp_file_guard is dropped here, file is deleted
                Err(e) => println!("Temp file creation failed: {}", e),
            }
        },
        Err(e) => println!("Image processing failed: {}", e),
    }
    
    // --- Scenario 3: Error case ---
    println!("\n--- Scenario 3: Invalid CSV ---");
    let bad_csv_data = b"email,role\r\ninvalid@row";
    let result = file_service.parse_csv_to_users(bad_csv_data);
    if let Err(e) = result {
        println!("Correctly caught an error: {}", e);
    }
}
</pre>