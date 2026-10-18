//! A finished chunk: everything vanilla would have saved for it as a lit proto chunk,
//! so the mod can install it the way vanilla loads one from disk.

use steel_core::chunk::Chunk;
use steel_core::chunk::heightmap::{ChunkHeightmaps, HeightmapType};
use steel_core::chunk::status::ChunkStatus;
use steel_core::chunk_saver::{ChunkStorage, PersistentLightSection, PersistentTick};
use steel_core::entity::SharedEntity;

use crate::sections::{SectionBiomes, SectionStates};
use crate::vanilla_nbt;

/// The heightmaps vanilla keeps after `TERRAIN`, with their vanilla names.
const FINAL_HEIGHTMAPS: [(HeightmapType, &str); 4] = [
    (HeightmapType::WorldSurface, "WORLD_SURFACE"),
    (HeightmapType::OceanFloor, "OCEAN_FLOOR"),
    (HeightmapType::MotionBlocking, "MOTION_BLOCKING"),
    (
        HeightmapType::MotionBlockingNoLeaves,
        "MOTION_BLOCKING_NO_LEAVES",
    ),
];

/// One light section. `data` is `None` for a present but all-zero section.
pub struct LightSection {
    /// Index in the padded light-section array: 0 is the section below the world.
    pub index: u32,
    pub data: Option<Vec<u8>>,
}

/// A scheduled block or fluid tick. Proto-chunk ticks always fire on promotion
/// (vanilla `ProtoChunkTicks` stores them with delay 0), so no delay is sent.
pub struct Tick {
    pub kind: String,
    pub x: u8,
    pub y: i16,
    pub z: u8,
    pub priority: i8,
}

pub struct FullChunk {
    pub sections: Vec<SectionStates>,
    pub biomes: Vec<SectionBiomes>,
    pub postprocessing: Vec<Vec<u16>>,
    /// `(vanilla type name, first free Y per column indexed x + z * 16)`.
    pub heightmaps: Vec<(&'static str, [i32; 256])>,
    pub block_light: Vec<LightSection>,
    pub sky_light: Vec<LightSection>,
    pub block_ticks: Vec<Tick>,
    pub fluid_ticks: Vec<Tick>,
    /// Vanilla block entity NBT, each a complete NBT root.
    pub block_entities: Vec<Vec<u8>>,
    /// Vanilla entity NBT, each a complete NBT root.
    pub entities: Vec<Vec<u8>>,
}

impl FullChunk {
    /// Reads a `FULL` chunk whose 8 neighbours are `FULL` too, so no other chunk can
    /// still change its blocks or light. The worker never ticks, so the chunk is still
    /// as generation left it: post-processing and ticks are pending and `entities`,
    /// the chunk's entities in the world, are the ones features placed. SteelMC's
    /// `SPAWN` step adds none.
    pub fn read(chunk: &Chunk, entities: &[SharedEntity]) -> Result<Self, String> {
        let pos = chunk.pos();
        let snapshot = ChunkStorage::prepare_chunk_save(chunk, ChunkStatus::Full, entities, true)
            .ok_or("SteelMC did not produce a snapshot of the chunk")?
            .persistent;

        let sections = chunk
            .sections
            .sections
            .iter()
            .map(|section| SectionStates::read(&section.read().states))
            .collect();
        let biomes = chunk
            .sections
            .sections
            .iter()
            .map(|section| SectionBiomes::read(&section.read().biomes))
            .collect();

        let stored = chunk.generation_heightmaps();
        let types: Vec<HeightmapType> = FINAL_HEIGHTMAPS.iter().map(|&(kind, _)| kind).collect();
        let rebuilt;
        let heightmaps_source = if types.iter().all(|&kind| stored.get(kind).is_some()) {
            &*stored
        } else {
            let mut fresh = ChunkHeightmaps::empty();
            fresh.prime_from_sections(
                &types,
                chunk.min_y(),
                chunk.height(),
                &chunk.sections.sections,
            );
            rebuilt = fresh;
            &rebuilt
        };
        let heightmaps = FINAL_HEIGHTMAPS
            .iter()
            .map(|&(kind, name)| {
                let heightmap = heightmaps_source
                    .get(kind)
                    .ok_or_else(|| format!("chunk has no {kind:?} heightmap"))?;
                Ok((
                    name,
                    std::array::from_fn(|index| {
                        heightmap.get_first_available(index % 16, index / 16)
                    }),
                ))
            })
            .collect::<Result<_, String>>()?;

        let block_entities = snapshot
            .block_entities
            .iter()
            .map(|entity| {
                vanilla_nbt::block_entity(entity, pos.0.x, pos.0.y).map(vanilla_nbt::to_root_bytes)
            })
            .collect::<Result<_, _>>()?;
        let entities = snapshot
            .entities
            .iter()
            .map(|entity| vanilla_nbt::entity(entity).map(vanilla_nbt::to_root_bytes))
            .collect::<Result<_, _>>()?;

        Ok(Self {
            sections,
            biomes,
            postprocessing: snapshot.postprocessing,
            heightmaps,
            block_light: light_sections(&snapshot.light.block),
            sky_light: light_sections(&snapshot.light.sky),
            block_ticks: snapshot.block_ticks.iter().map(Tick::from).collect(),
            fluid_ticks: snapshot.fluid_ticks.iter().map(Tick::from).collect(),
            block_entities,
            entities,
        })
    }
}

/// Light sections vanilla's light engine has. SteelMC's `Internal` sections are its
/// own bookkeeping and are hidden from vanilla, so they are left out.
fn light_sections(sections: &[PersistentLightSection]) -> Vec<LightSection> {
    sections
        .iter()
        .filter_map(|section| match section {
            PersistentLightSection::Uninitialized { section_index } => Some(LightSection {
                index: *section_index,
                data: None,
            }),
            PersistentLightSection::Initialized {
                section_index,
                data,
            } => Some(LightSection {
                index: *section_index,
                data: Some(data.clone()),
            }),
            PersistentLightSection::Internal { .. } => None,
        })
        .collect()
}

impl From<&PersistentTick> for Tick {
    fn from(tick: &PersistentTick) -> Self {
        Self {
            kind: tick.tick_type.to_string(),
            x: tick.x,
            y: tick.y,
            z: tick.z,
            priority: tick.priority,
        }
    }
}
