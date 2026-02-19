<pre>
use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::fs::{self, File, OpenOptions};
use std::path::{Path, PathBuf};
use std::env;
use std::time::{SystemTime, UNIX_EPOCH};

// --- Domain Schema ---

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Uuid([u8; 16]);

impl Uuid {
    fn new_mock(val: u8) -> Self {
        Uuid([val; 16])
    }
    fn to_string(&self) -> String {
        format!("{:x}", self.0.iter().fold(0u128, |acc, &x| (acc << 8) | x as u128))
    }
}

#[derive(Debug)]
pub enum Role { ADMIN, USER }
#[derive(Debug)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
pub struct User {
    id: Uuid,
    email: String,
    _password_hash: String,
    role: Role,
    is_active: bool,
    created_at: u64,
}

#[derive(Debug)]
pub struct Post {
    _id: Uuid,
    _user_id: Uuid,
    _title: String,
    _content: String,
    _status: PostStatus,
}

// --- Module for Multipart Parsing ---
mod multipart_parser {
    use super::*;

    pub struct ParsedPart {
        pub headers: HashMap<String, String>,
        pub data: Vec<u8>,
    }

    pub fn parse_multipart_form_data(mut stream: impl Read, boundary: &str) -> io::Result<Vec<ParsedPart>> {
        let mut buffer = Vec::new();
        stream.read_to_end(&mut buffer)?;
        
        let mut parts = Vec::new();
        let boundary_bytes = format!("--{}", boundary).into_bytes();
        
        for part_data in buffer.split(|b| *b == boundary_bytes[0] && buffer.windows(boundary_bytes.len()).any(|w| w == &boundary_bytes)) {
            if part_data.is_empty() || part_data == b"--\r\n" || part_data == b"--" {
                continue;
            }

            let mut header_end = 0;
            for i in 0..part_data.len() - 3 {
                if &part_data[i..i+4] == b"\r\n\r\n" {
                    header_end = i;
                    break;
                }
            }
            if header_end == 0 { continue; }

            let header_bytes = &part_data[2..header_end]; // Skip initial \r\n
            let mut headers = HashMap::new();
            if let Ok(header_str) = std::str::from_utf8(header_bytes) {
                for line in header_str.lines() {
                    if let Some((key, value)) = line.split_once(": ") {
                        headers.insert(key.to_lowercase().to_string(), value.trim().to_string());
                    }
                }
            }
            
            let data = part_data[header_end + 4..part_data.len()-2].to_vec(); // Skip \r\n\r\n and final \r\n
            parts.push(ParsedPart { headers, data });
        }
        Ok(parts)
    }
}

// --- Module for CSV Parsing ---
mod csv_processor {
    use super::*;

    pub fn parse_users_from_csv(data: &[u8]) -> Result<Vec<User>, String> {
        let mut users = Vec::new();
        let content = std::str::from_utf8(data).map_err(|e| e.to_string())?;
        let mut lines = content.lines();

        // Assume header: id,email,password_hash,role,is_active
        let header = lines.next().ok_or("CSV is empty")?;
        if header != "id,email,password_hash,role,is_active" {
            return Err("Invalid CSV header".to_string());
        }

        for (i, line) in lines.enumerate() {
            let cols: Vec<&str> = line.split(',').collect();
            if cols.len() != 5 {
                return Err(format!("Invalid column count at line {}", i + 2));
            }
            let role = match cols[3] {
                "ADMIN" => Role::ADMIN,
                "USER" => Role::USER,
                _ => return Err(format!("Invalid role at line {}", i + 2)),
            };
            let is_active = cols[4].parse::<bool>().map_err(|_| format!("Invalid boolean at line {}", i + 2))?;
            
            users.push(User {
                id: Uuid::new_mock(cols[0].parse::<u8>().unwrap_or(i as u8)),
                email: cols[1].to_string(),
                _password_hash: cols[2].to_string(),
                role,
                is_active,
                created_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs(),
            });
        }
        Ok(users)
    }
}

// --- Module for Image Processing Simulation ---
mod image_processor {
    // NOTE: True image resizing requires external libraries. This is a simulation.
    pub fn resize_image_mock(data: &[u8], max_bytes: usize) -> Result<Vec<u8>, &'static str> {
        if data.len() < 4 {
            return Err("Not a valid image (too small)");
        }
        // Simulate checking for a magic number, e.g., "IMG"
        if &data[0..3] != b"IMG" {
            return Err("Invalid image format mock");
        }
        let mut resized_data = data.to_vec();
        resized_data.truncate(max_bytes);
        Ok(resized_data)
    }
}

// --- Module for File I/O Operations ---
mod file_io {
    use super::*;

    pub fn stream_file_download(source_path: &Path, mut destination: impl Write) -> io::Result<u64> {
        let mut source_file = File::open(source_path)?;
        io::copy(&mut source_file, &mut destination)
    }

    pub fn manage_temp_file<F, R>(file_name: &str, content: &[u8], operation: F) -> io::Result<R>
    where
        F: FnOnce(&Path) -> io::Result<R>,
    {
        let temp_dir = env::temp_dir();
        let temp_file_path = temp_dir.join(file_name);
        
        let mut file = File::create(&temp_file_path)?;
        file.write_all(content)?;

        let result = operation(&temp_file_path);

        fs::remove_file(&temp_file_path)?;
        
        result
    }
}

// --- Main Application Logic ---
fn main() {
    println!("--- Variation 1: Functional/Procedural Approach ---");

    // 1. Simulate a file upload (multipart form data)
    let boundary = "boundary123";
    let mock_request_body = [
        format!("--{}\r\n", boundary),
        "Content-Disposition: form-data; name=\"user_csv\"; filename=\"users.csv\"\r\n",
        "Content-Type: text/csv\r\n\r\n",
        "id,email,password_hash,role,is_active\r\n",
        "1,admin@test.com,hash1,ADMIN,true\r\n",
        "2,user@test.com,hash2,USER,false\r\n",
        format!("\r\n--{}\r\n", boundary),
        "Content-Disposition: form-data; name=\"profile_pic\"; filename=\"pic.img\"\r\n",
        "Content-Type: application/octet-stream\r\n\r\n",
        "IMG_some_raw_image_byte_data_that_is_very_long\r\n",
        format!("\r\n--{}--\r\n", boundary),
    ].join("");

    let parts = multipart_parser::parse_multipart_form_data(mock_request_body.as_bytes(), boundary).expect("Failed to parse multipart");

    for part in parts {
        if let Some(disposition) = part.headers.get("content-disposition") {
            if disposition.contains("users.csv") {
                // 2. Parse CSV data
                println!("\nProcessing CSV file...");
                let users = csv_processor::parse_users_from_csv(&part.data).expect("CSV parsing failed");
                println!("Parsed Users: {:?}", users);
            } else if disposition.contains("pic.img") {
                // 3. Process an image file
                println!("\nProcessing image file...");
                let resized_image = image_processor::resize_image_mock(&part.data, 10).expect("Image resize failed");
                println!("Resized image data (mock): {:?}", std::str::from_utf8(&resized_image).unwrap());

                // 4. & 5. Use a temporary file and stream it for download
                println!("\nSimulating download via temporary file...");
                let download_op = |temp_path: &Path| {
                    println!("Temp file created at: {:?}", temp_path);
                    let mut download_buffer: Vec<u8> = Vec::new();
                    let bytes_streamed = file_io::stream_file_download(temp_path, &mut download_buffer)?;
                    println!("Streamed {} bytes for download.", bytes_streamed);
                    println!("Downloaded content: {:?}", std::str::from_utf8(&download_buffer).unwrap());
                    Ok(())
                };
                
                file_io::manage_temp_file("download.img", &resized_image, download_op).expect("Temp file management failed");
            }
        }
    }
}
</pre>