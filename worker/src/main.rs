//! `oxide-worker`: generates Minecraft terrain with SteelMC for the Oxide mod.
//!
//! The mod starts one worker per dimension and talks to it over stdin/stdout
//! (see [`protocol`]). Logs go to stderr.

mod engine;
mod protocol;

use std::io::{self, BufReader, BufWriter, Write};
use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::thread;
use std::time::Duration;

use steel_core::chunk::chunk_request::ChunkRequestHandle;

use engine::{Engine, SectionStates, TerrainChunk};
use protocol::{FrameWriter, Hello, PROTOCOL_VERSION, Request};

/// How long to wait for new requests while chunks are still generating.
const BUSY_POLL: Duration = Duration::from_millis(1);

/// A terrain request SteelMC is still working on.
struct Pending {
    request_id: u32,
    handle: ChunkRequestHandle,
}

fn main() {
    StderrLogger::install();
    let mut output = BufWriter::new(io::stdout().lock());
    let result = spawn_request_reader()
        .map_err(|error| format!("failed to start the stdin reader: {error}"))
        .and_then(|requests| run(&requests, &mut output));
    if let Err(error) = result {
        log::error!("{error}");
        let _ = FrameWriter::error(0, &error).send(&mut output);
        std::process::exit(1);
    }
}

fn run(requests: &Receiver<io::Result<Request>>, output: &mut impl Write) -> Result<(), String> {
    let hello = match requests.recv() {
        Ok(Ok(Request::Hello(hello))) => hello,
        Ok(Ok(other)) => return Err(format!("expected Hello first, got {other:?}")),
        Ok(Err(error)) => return Err(format!("failed to read Hello: {error}")),
        Err(_) => return Ok(()),
    };
    if hello.protocol_version != PROTOCOL_VERSION {
        return Err(format!(
            "protocol version mismatch: mod {}, worker {PROTOCOL_VERSION}",
            hello.protocol_version
        ));
    }

    let engine = start_engine(&hello)?;
    send_ready(&engine, output).map_err(|error| format!("failed to send Ready: {error}"))?;
    log::info!("Ready for {} (seed {})", hello.dimension, hello.seed);

    let result = serve(&engine, requests, output);
    engine.shutdown();
    result
}

fn start_engine(hello: &Hello) -> Result<Engine, String> {
    let threads = if hello.threads == 0 {
        default_threads()
    } else {
        usize::from(hello.threads)
    };
    Engine::new(
        hello.seed,
        &hello.dimension,
        Path::new(&hello.cache_dir),
        threads,
    )
}

/// Half the machine, so generation never starves the game itself.
fn default_threads() -> usize {
    let cores = thread::available_parallelism().map_or(2, NonZeroUsize::get);
    (cores / 2).max(1)
}

fn serve(
    engine: &Engine,
    requests: &Receiver<io::Result<Request>>,
    output: &mut impl Write,
) -> Result<(), String> {
    let mut pending: Vec<Pending> = Vec::new();
    loop {
        let next = if pending.is_empty() {
            requests.recv().map_err(|_| RecvTimeoutError::Disconnected)
        } else {
            requests.recv_timeout(BUSY_POLL)
        };
        match next {
            Ok(Ok(Request::Terrain {
                request_id,
                chunk_x,
                chunk_z,
            })) => pending.push(Pending {
                request_id,
                handle: engine.request_terrain(chunk_x, chunk_z),
            }),
            Ok(Ok(Request::Shutdown)) | Err(RecvTimeoutError::Disconnected) => return Ok(()),
            Ok(Ok(Request::Hello(_))) => return Err("unexpected second Hello".to_owned()),
            Ok(Err(error)) => return Err(format!("failed to read request: {error}")),
            Err(RecvTimeoutError::Timeout) => {}
        }

        engine.advance_scheduling();
        send_finished(engine, &mut pending, output)
            .map_err(|error| format!("failed to write reply: {error}"))?;
    }
}

/// Replies to every pending request whose chunk has reached `TERRAIN`. Dropping a
/// finished request releases its SteelMC ticket so the chunk can unload.
fn send_finished(
    engine: &Engine,
    pending: &mut Vec<Pending>,
    output: &mut impl Write,
) -> io::Result<()> {
    let mut index = 0;
    while index < pending.len() {
        let request = &pending[index];
        let frame = match engine.read_terrain(&request.handle) {
            Ok(None) => {
                index += 1;
                continue;
            }
            Ok(Some(terrain)) => terrain_frame(request.request_id, &terrain),
            Err(error) => FrameWriter::error(request.request_id, &error),
        };
        frame.send(output)?;
        pending.swap_remove(index);
    }
    Ok(())
}

fn send_ready(engine: &Engine, output: &mut impl Write) -> io::Result<()> {
    let mut frame = FrameWriter::ready();
    frame.u32(PROTOCOL_VERSION);
    frame.i32(engine.min_y());
    frame.u16(engine.section_count() as u16);
    let states = engine::block_state_table();
    frame.u32(states.len() as u32);
    for (block, properties) in &states {
        frame.string(block);
        frame.u8(properties.len() as u8);
        for (name, value) in properties {
            frame.string(name);
            frame.string(value);
        }
    }
    frame.send(output)
}

fn terrain_frame(request_id: u32, terrain: &TerrainChunk) -> FrameWriter {
    let mut frame = FrameWriter::terrain();
    frame.u32(request_id);
    frame.u16(terrain.sections.len() as u16);
    for section in &terrain.sections {
        match section {
            SectionStates::Single(state) => {
                frame.u8(0);
                frame.u16(state.0);
            }
            SectionStates::Paletted { palette, indices } => {
                frame.u8(1);
                frame.u16(palette.len() as u16);
                for state in palette {
                    frame.u16(state.0);
                }
                let wide = palette.len() > 256;
                for &index in indices.iter() {
                    if wide {
                        frame.u16(index);
                    } else {
                        frame.u8(index as u8);
                    }
                }
            }
        }
    }
    for offsets in &terrain.postprocessing {
        frame.u16(offsets.len() as u16);
        for &offset in offsets {
            frame.u16(offset);
        }
    }
    for heights in [&terrain.world_surface_wg, &terrain.ocean_floor_wg] {
        for &height in heights {
            frame.i32(height);
        }
    }
    frame
}

/// Reads requests on their own thread so generation keeps running while the
/// mod is quiet.
fn spawn_request_reader() -> io::Result<Receiver<io::Result<Request>>> {
    let (sender, receiver) = mpsc::channel();
    thread::Builder::new()
        .name("oxide-stdin".to_owned())
        .spawn(move || {
            let mut input = BufReader::new(io::stdin().lock());
            loop {
                let message = match protocol::read_request(&mut input) {
                    Ok(Some(request)) => Ok(request),
                    Ok(None) => break,
                    Err(error) => Err(error),
                };
                let failed = message.is_err();
                if sender.send(message).is_err() || failed {
                    break;
                }
            }
        })?;
    Ok(receiver)
}

/// Minimal `log` backend: SteelMC logs through `log`, and stdout is reserved for
/// protocol frames.
struct StderrLogger;

impl StderrLogger {
    fn install() {
        static LOGGER: StderrLogger = StderrLogger;
        if log::set_logger(&LOGGER).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
    }
}

impl log::Log for StderrLogger {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            eprintln!("[oxide-worker {}] {}", record.level(), record.args());
        }
    }

    fn flush(&self) {}
}
