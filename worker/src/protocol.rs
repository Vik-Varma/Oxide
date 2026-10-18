//! Framed binary messages between the Oxide mod and this worker.
//!
//! Every frame is a big-endian `u32` payload length followed by the payload. A
//! payload starts with a `u8` message type. All integers are big-endian, so the
//! Java side can read them with `DataInputStream`. Strings are a `u16` byte length
//! followed by UTF-8. See `protocol/README.md` for the full layout.

use std::io::{self, Read, Write};

/// Bumped on any incompatible change to a message layout.
pub const PROTOCOL_VERSION: u32 = 1;

/// Largest payload either side accepts, to reject a corrupted length early.
const MAX_FRAME_LEN: u32 = 64 * 1024 * 1024;

const HELLO: u8 = 0x01;
const TERRAIN_REQUEST: u8 = 0x02;
const SHUTDOWN: u8 = 0x03;

const READY: u8 = 0x81;
const TERRAIN: u8 = 0x82;
const ERROR: u8 = 0x83;

/// A message from the mod.
#[derive(Debug)]
pub enum Request {
    /// First message of a session; selects the world to generate.
    Hello(Hello),
    /// Generate one chunk's `TERRAIN` step output.
    Terrain {
        /// Echoed in the reply so the mod can match out-of-order replies.
        request_id: u32,
        /// Chunk X coordinate.
        chunk_x: i32,
        /// Chunk Z coordinate.
        chunk_z: i32,
    },
    /// Finish in-flight work and exit.
    Shutdown,
}

/// Session parameters sent once by the mod.
#[derive(Debug)]
pub struct Hello {
    /// The mod's protocol version; must equal [`PROTOCOL_VERSION`].
    pub protocol_version: u32,
    /// World seed.
    pub seed: i64,
    /// Dimension key, e.g. `minecraft:overworld`.
    pub dimension: String,
    /// Directory for SteelMC's own chunk cache for this dimension.
    pub cache_dir: String,
    /// Generation threads; `0` lets the worker choose.
    pub threads: u16,
}

/// Reads one request frame. Returns `Ok(None)` on a clean end of input.
pub fn read_request(input: &mut impl Read) -> io::Result<Option<Request>> {
    let Some(payload) = read_frame(input)? else {
        return Ok(None);
    };
    let mut reader = PayloadReader::new(&payload);
    let request = match reader.u8()? {
        HELLO => Request::Hello(Hello {
            protocol_version: reader.u32()?,
            seed: reader.i64()?,
            dimension: reader.string()?,
            cache_dir: reader.string()?,
            threads: reader.u16()?,
        }),
        TERRAIN_REQUEST => Request::Terrain {
            request_id: reader.u32()?,
            chunk_x: reader.i32()?,
            chunk_z: reader.i32()?,
        },
        SHUTDOWN => Request::Shutdown,
        other => {
            return Err(invalid_data(format!("unknown message type 0x{other:02x}")));
        }
    };
    reader.finish()?;
    Ok(Some(request))
}

fn read_frame(input: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut len_bytes = [0u8; 4];
    match input.read_exact(&mut len_bytes) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error),
    }
    let len = u32::from_be_bytes(len_bytes);
    if len == 0 || len > MAX_FRAME_LEN {
        return Err(invalid_data(format!("invalid frame length {len}")));
    }
    let mut payload = vec![0u8; len as usize];
    input.read_exact(&mut payload)?;
    Ok(Some(payload))
}

fn invalid_data(message: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

struct PayloadReader<'a> {
    bytes: &'a [u8],
}

impl<'a> PayloadReader<'a> {
    const fn new(bytes: &'a [u8]) -> Self {
        Self { bytes }
    }

    fn take<const N: usize>(&mut self) -> io::Result<[u8; N]> {
        let Some((head, rest)) = self.bytes.split_first_chunk::<N>() else {
            return Err(invalid_data("message ended early".to_owned()));
        };
        self.bytes = rest;
        Ok(*head)
    }

    fn u8(&mut self) -> io::Result<u8> {
        Ok(self.take::<1>()?[0])
    }

    fn u16(&mut self) -> io::Result<u16> {
        Ok(u16::from_be_bytes(self.take()?))
    }

    fn u32(&mut self) -> io::Result<u32> {
        Ok(u32::from_be_bytes(self.take()?))
    }

    fn i32(&mut self) -> io::Result<i32> {
        Ok(i32::from_be_bytes(self.take()?))
    }

    fn i64(&mut self) -> io::Result<i64> {
        Ok(i64::from_be_bytes(self.take()?))
    }

    fn string(&mut self) -> io::Result<String> {
        let len = usize::from(self.u16()?);
        if self.bytes.len() < len {
            return Err(invalid_data("string ended early".to_owned()));
        }
        let (text, rest) = self.bytes.split_at(len);
        self.bytes = rest;
        String::from_utf8(text.to_vec()).map_err(|error| invalid_data(error.to_string()))
    }

    fn finish(&self) -> io::Result<()> {
        if self.bytes.is_empty() {
            Ok(())
        } else {
            Err(invalid_data(format!(
                "{} unexpected trailing bytes",
                self.bytes.len()
            )))
        }
    }
}

/// Builds one outgoing frame.
pub struct FrameWriter {
    payload: Vec<u8>,
}

impl FrameWriter {
    fn new(message_type: u8) -> Self {
        Self {
            payload: vec![message_type],
        }
    }

    /// Starts a `Ready` reply.
    #[must_use]
    pub fn ready() -> Self {
        Self::new(READY)
    }

    /// Starts a `Terrain` reply.
    #[must_use]
    pub fn terrain() -> Self {
        Self::new(TERRAIN)
    }

    /// Builds a complete `Error` reply. `request_id` 0 means the session failed.
    #[must_use]
    pub fn error(request_id: u32, message: &str) -> Self {
        let mut frame = Self::new(ERROR);
        frame.u32(request_id);
        frame.string(message);
        frame
    }

    pub fn u8(&mut self, value: u8) {
        self.payload.push(value);
    }

    pub fn u16(&mut self, value: u16) {
        self.payload.extend_from_slice(&value.to_be_bytes());
    }

    pub fn u32(&mut self, value: u32) {
        self.payload.extend_from_slice(&value.to_be_bytes());
    }

    pub fn i32(&mut self, value: i32) {
        self.payload.extend_from_slice(&value.to_be_bytes());
    }

    /// Writes a string, truncated to the `u16` length limit on a character boundary.
    pub fn string(&mut self, value: &str) {
        let mut end = value.len().min(usize::from(u16::MAX));
        while !value.is_char_boundary(end) {
            end -= 1;
        }
        let text = &value[..end];
        self.u16(text.len() as u16);
        self.payload.extend_from_slice(text.as_bytes());
    }

    /// Writes the frame and flushes it.
    pub fn send(self, output: &mut impl Write) -> io::Result<()> {
        let len = u32::try_from(self.payload.len())
            .ok()
            .filter(|len| *len <= MAX_FRAME_LEN)
            .ok_or_else(|| invalid_data(format!("frame too large: {}", self.payload.len())))?;
        output.write_all(&len.to_be_bytes())?;
        output.write_all(&self.payload)?;
        output.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(payload: &[u8]) -> Vec<u8> {
        let mut bytes = (payload.len() as u32).to_be_bytes().to_vec();
        bytes.extend_from_slice(payload);
        bytes
    }

    #[test]
    fn reads_a_terrain_request_with_negative_coordinates() {
        let mut payload = vec![TERRAIN_REQUEST];
        payload.extend_from_slice(&7u32.to_be_bytes());
        payload.extend_from_slice(&(-3i32).to_be_bytes());
        payload.extend_from_slice(&i32::MIN.to_be_bytes());
        let bytes = frame(&payload);

        let request = read_request(&mut bytes.as_slice()).unwrap().unwrap();
        let Request::Terrain {
            request_id,
            chunk_x,
            chunk_z,
        } = request
        else {
            panic!("expected a terrain request, got {request:?}");
        };
        assert_eq!((request_id, chunk_x, chunk_z), (7, -3, i32::MIN));
    }

    #[test]
    fn rejects_trailing_bytes_and_truncated_frames() {
        let bytes = frame(&[SHUTDOWN, 0]);
        assert!(read_request(&mut bytes.as_slice()).is_err());

        let mut truncated = frame(&[TERRAIN_REQUEST, 0, 0, 0, 1]);
        truncated.truncate(6);
        assert!(read_request(&mut truncated.as_slice()).is_err());
    }

    #[test]
    fn end_of_input_between_frames_is_a_clean_close() {
        assert!(read_request(&mut [].as_slice()).unwrap().is_none());
    }

    #[test]
    fn string_truncation_keeps_utf8_valid() {
        let long = "é".repeat(40_000);
        let mut writer = FrameWriter::new(ERROR);
        writer.string(&long);
        let len = u16::from_be_bytes([writer.payload[1], writer.payload[2]]);
        let text = &writer.payload[3..3 + usize::from(len)];
        assert!(std::str::from_utf8(text).is_ok());
    }
}
