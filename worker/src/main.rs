//! `oxide-worker`: generates Minecraft chunks with SteelMC for the Oxide mod.
//!
//! The mod starts one worker per dimension and talks to it over stdin/stdout
//! (see [`protocol`]). Logs go to stderr.

mod engine;
mod full_chunk;
mod protocol;
mod sections;
mod vanilla_nbt;

use std::io::{self, BufReader, BufWriter, Write};
use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::thread;
use std::time::Duration;

use steel_core::chunk::chunk_request::ChunkRequestHandle;

use engine::{Engine, TerrainChunk};
use full_chunk::{FullChunk, LightSection, Tick};
use protocol::{FrameWriter, Hello, PROTOCOL_VERSION, Request};
use sections::{SectionBiomes, SectionStates};

/// How long to wait for new requests while chunks are still generating.
const BUSY_POLL: Duration = Duration::from_millis(1);

/// A request SteelMC is still working on.
struct Pending {
    request_id: u32,
    kind: RequestKind,
    chunk_x: i32,
    chunk_z: i32,
    handle: ChunkRequestHandle,
}

#[derive(Clone, Copy)]
enum RequestKind {
    Terrain,
    Full,
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
                kind: RequestKind::Terrain,
                chunk_x,
                chunk_z,
                handle: engine.request_terrain(chunk_x, chunk_z),
            }),
            Ok(Ok(Request::Full {
                request_id,
                chunk_x,
                chunk_z,
            })) => pending.push(Pending {
                request_id,
                kind: RequestKind::Full,
                chunk_x,
                chunk_z,
                handle: engine.request_full(chunk_x, chunk_z),
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

/// Replies to every pending request whose chunks are ready. Dropping a finished
/// request releases its SteelMC tickets so the chunks can unload.
fn send_finished(
    engine: &Engine,
    pending: &mut Vec<Pending>,
    output: &mut impl Write,
) -> io::Result<()> {
    let mut index = 0;
    while index < pending.len() {
        let request = &pending[index];
        let (x, z) = (request.chunk_x, request.chunk_z);
        let frame = match request.kind {
            RequestKind::Terrain => engine
                .read_terrain(&request.handle, x, z)
                .map(|terrain| terrain.map(|terrain| terrain_frame(request.request_id, &terrain))),
            RequestKind::Full => engine
                .read_full(&request.handle, x, z)
                .map(|full| full.map(|full| full_frame(request.request_id, &full))),
        };
        let frame = match frame {
            Ok(None) => {
                index += 1;
                continue;
            }
            Ok(Some(frame)) => frame,
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
    let biomes = engine::biome_table();
    frame.u16(biomes.len() as u16);
    for biome in &biomes {
        frame.string(biome);
    }
    frame.send(output)
}

fn terrain_frame(request_id: u32, terrain: &TerrainChunk) -> FrameWriter {
    let mut frame = FrameWriter::terrain();
    frame.u32(request_id);
    write_sections(&mut frame, &terrain.sections, &terrain.biomes);
    write_postprocessing(&mut frame, &terrain.postprocessing);
    for heights in [&terrain.world_surface_wg, &terrain.ocean_floor_wg] {
        write_heights(&mut frame, heights);
    }
    frame
}

fn full_frame(request_id: u32, full: &FullChunk) -> FrameWriter {
    let mut frame = FrameWriter::full();
    frame.u32(request_id);
    write_sections(&mut frame, &full.sections, &full.biomes);
    write_postprocessing(&mut frame, &full.postprocessing);
    frame.u8(full.heightmaps.len() as u8);
    for (name, heights) in &full.heightmaps {
        frame.string(name);
        write_heights(&mut frame, heights);
    }
    for layer in [&full.block_light, &full.sky_light] {
        write_light(&mut frame, layer);
    }
    for ticks in [&full.block_ticks, &full.fluid_ticks] {
        write_ticks(&mut frame, ticks);
    }
    for list in [&full.block_entities, &full.entities] {
        frame.u32(list.len() as u32);
        for nbt in list {
            frame.bytes(nbt);
        }
    }
    frame
}

fn write_sections(frame: &mut FrameWriter, sections: &[SectionStates], biomes: &[SectionBiomes]) {
    frame.u16(sections.len() as u16);
    for section in sections {
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
    for section in biomes {
        frame.u8(section.palette.len() as u8);
        for &biome in &section.palette {
            frame.u16(biome);
        }
        frame.raw(&section.indices);
    }
}

fn write_postprocessing(frame: &mut FrameWriter, postprocessing: &[Vec<u16>]) {
    for offsets in postprocessing {
        frame.u16(offsets.len() as u16);
        for &offset in offsets {
            frame.u16(offset);
        }
    }
}

fn write_heights(frame: &mut FrameWriter, heights: &[i32; 256]) {
    for &height in heights {
        frame.i32(height);
    }
}

fn write_light(frame: &mut FrameWriter, sections: &[LightSection]) {
    frame.u16(sections.len() as u16);
    for section in sections {
        frame.u16(section.index as u16);
        match &section.data {
            None => frame.u8(0),
            Some(data) => {
                frame.u8(1);
                frame.raw(data);
            }
        }
    }
}

fn write_ticks(frame: &mut FrameWriter, ticks: &[Tick]) {
    frame.u32(ticks.len() as u32);
    for tick in ticks {
        frame.string(&tick.kind);
        frame.u8(tick.x);
        frame.i16(tick.y);
        frame.u8(tick.z);
        frame.i8(tick.priority);
    }
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
