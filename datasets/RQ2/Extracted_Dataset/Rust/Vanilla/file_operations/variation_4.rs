<pre>
use std::io::{self, Read, Write};
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::env;
use std::time::{SystemTime, UNIX_EPOCH};
use std::marker::PhantomData;

// --- Domain Schema ---
#[derive(Debug, Clone)]
pub struct Uuid([u8; 16]);
impl Uuid { fn new_mock(val: u8) -> Self { Uuid([val; 16]) } }

#[derive(Debug)]
pub enum Role { ADMIN, USER }
#[derive(Debug)]
pub enum PostStatus { DRAFT, PUBLISHED }

#[derive(Debug)]
pub struct User { id: Uuid, email: String, role: Role }
#[derive(Debug)]
pub struct Post { _id: Uuid, _user_id: Uuid }

// --- Builder & Fluent API Pattern ---

#[derive(Debug)]
pub enum OperationError {
    Io(io::Error),
    InvalidInput(&'static str),
    ProcessingFailed(&'static str),
}

impl From<io::Error> for OperationError {
    fn from(err: io::Error) -> Self { OperationError::Io(err) }
}

/// Represents the source of the file data.
pub enum FileSource<'a> {
    Reader(&'a mut dyn Read),
    Path(&'a Path),
}

/// Represents the state of the builder.
pub struct Raw;
pub struct Processed;

/// A fluent builder for file processing operations.
pub struct FileProcessor<'a, State = Raw> {
    source: FileSource<'a>,
    temp_path: Option<PathBuf>,
    data: Option<Vec<u8>>,
    _state: PhantomData<State>,
}

// Methods for the initial "Raw" state
impl<'a> FileProcessor<'a, Raw> {
    /// Start a new file processing operation from a source.
    pub fn new(source: FileSource<'a>) -> Self {
        FileProcessor {
            source,
            temp_path: None,
            data: None,
            _state: PhantomData,
        }
    }

    /// Read the source into a temporary file.
    pub fn into_temp_file(mut self, name_hint: &str) -> Result<Self, OperationError> {
        let temp_dir = env::temp_dir();
        let unique_name = format!("{}_{}", name_hint, SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_micros());
        let temp_path = temp_dir.join(unique_name);
        let mut temp_file = File::create(&temp_path)?;

        let bytes_written = match self.source {
            FileSource::Reader(reader) => io::copy(reader, &mut temp_file)?,
            FileSource::Path(path) => {
                let mut source_file = File::open(path)?;
                io::copy(&mut source_file, &mut temp_file)?
            }
        };
        if bytes_written == 0 {
            return Err(OperationError::InvalidInput("Source was empty"));
        }

        self.temp_path = Some(temp_path);
        Ok(self)
    }

    /// Read the source into memory.
    pub fn into_memory(mut self) -> Result<Self, OperationError> {
        let mut buffer = Vec::new();
        let bytes_read = match self.source {
            FileSource::Reader(reader) => reader.read_to_end(&mut buffer)?,
            FileSource::Path(path) => {
                let mut source_file = File::open(path)?;
                source_file.read_to_end(&mut buffer)?
            }
        };
        if bytes_read == 0 {
            return Err(OperationError::InvalidInput("Source was empty"));
        }
        self.data = Some(buffer);
        Ok(self)
    }

    /// A terminal operation that parses the source as a CSV of Users.
    pub fn execute_as_user_csv(self) -> Result<Vec<User>, OperationError> {
        let loaded_self = self.into_memory()?;
        let data = loaded_self.data.as_ref().unwrap();
        let content = std::str::from_utf8(data).map_err(|_| OperationError::ProcessingFailed("Invalid UTF-8"))?;
        
        let mut users = Vec::new();
        for line in content.lines().skip(1) {
            let cols: Vec<&str> = line.split(',').collect();
            if cols.len() != 2 { return Err(OperationError::ProcessingFailed("Invalid CSV column count")); }
            users.push(User {
                id: Uuid::new_mock(users.len() as u8),
                email: cols[0].to_string(),
                role: if cols[1] == "ADMIN" { Role::ADMIN } else { Role::USER },
            });
        }
        Ok(users)
    }

    /// A transitional operation that "processes" an image.
    pub fn then_process_as_image(self, max_size: usize) -> Result<FileProcessor<'a, Processed>, OperationError> {
        let loaded_self = self.into_memory()?;
        let data = loaded_self.data.as_ref().unwrap();

        // NOTE: Simulating image processing.
        if !data.starts_with(b"IMG") {
            return Err(OperationError::ProcessingFailed("Not a valid mock image"));
        }
        let mut processed_data = data.clone();
        processed_data.truncate(max_size);

        Ok(FileProcessor {
            source: loaded_self.source,
            temp_path: None, // Processed data is in memory now
            data: Some(processed_data),
            _state: PhantomData,
        })
    }
}

// Methods for the "Processed" state
impl<'a> FileProcessor<'a, Processed> {
    /// A terminal operation that saves the processed data to a destination.
    pub fn execute_and_save(self, destination_path: &Path) -> Result<(), OperationError> {
        let data = self.data.ok_or(OperationError::InvalidInput("No processed data available"))?;
        fs::write(destination_path, data)?;
        Ok(())
    }

    /// A terminal operation that streams the processed data to a writer.
    pub fn execute_and_stream(self, writer: &mut impl Write) -> Result<u64, OperationError> {
        let data = self.data.ok_or(OperationError::InvalidInput("No processed data available"))?;
        writer.write_all(&data)?;
        Ok(data.len() as u64)
    }
}

// Manual cleanup required for temp files if used.
impl<'a> Drop for FileProcessor<'a, Raw> {
    fn drop(&mut self) {
        if let Some(path) = &self.temp_path {
            let _ = fs::remove_file(path);
        }
    }
}

fn main() {
    println!("--- Variation 4: Builder & Fluent API Pattern ---");

    // --- Pipeline 1: Process a CSV from a byte stream into User structs ---
    println!("\n--- Pipeline 1: CSV Processing ---");
    let mut csv_data: &[u8] = b"email,role\r\nbuilder@test.com,ADMIN\r\nfluent@test.com,USER";
    let users_result = FileProcessor::new(FileSource::Reader(&mut csv_data))
        .execute_as_user_csv();
    
    match users_result {
        Ok(users) => println!("Successfully processed CSV into users: {:?}", users),
        Err(e) => println!("CSV processing failed: {:?}", e),
    }

    // --- Pipeline 2: Process an "image", resize it, and stream for download ---
    println!("\n--- Pipeline 2: Image Processing and Streaming ---");
    let mut image_data: &[u8] = b"IMG_SOME_VERY_LONG_IMAGE_DATA_HERE";
    let mut download_destination = Vec::new();

    let stream_result = FileProcessor::new(FileSource::Reader(&mut image_data))
        .then_process_as_image(15) // "Resize" to 15 bytes
        .and_then(|processor| processor.execute_and_stream(&mut download_destination));

    match stream_result {
        Ok(bytes_streamed) => {
            println!("Successfully processed and streamed {} bytes.", bytes_streamed);
            println!("Streamed content: {:?}", std::str::from_utf8(&download_destination).unwrap());
        },
        Err(e) => println!("Image processing pipeline failed: {:?}", e),
    }

    // --- Pipeline 3: Read from a file, store in temp, then fail (for demonstration) ---
    println!("\n--- Pipeline 3: Using Temporary File ---");
    let source_file_path = env::temp_dir().join("source.txt");
    fs::write(&source_file_path, "hello world").expect("Failed to create source file");

    {
        let processor = FileProcessor::new(FileSource::Path(&source_file_path))
            .into_temp_file("my_upload")
            .expect("Could not create temp file");
        
        println!("Created temp file at: {:?}", processor.temp_path);
        // Processor goes out of scope here, and its Drop implementation cleans up the temp file.
    }
    println!("Temp file should now be deleted.");
    assert!(!source_file_path.exists()); // The original is not touched
    
    fs::remove_file(&source_file_path).ok(); // Clean up the source file
}
</pre>