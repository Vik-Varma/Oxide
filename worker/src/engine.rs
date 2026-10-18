//! A SteelMC world with no server, used only to run world generation.

use std::path::Path;
use std::sync::Arc;

use steel_core::chunk::Chunk;
use steel_core::chunk::chunk_request::{
    ChunkRequest, ChunkRequestHandle, ChunkRequestState, ChunkTicketKind,
};
use steel_core::chunk::heightmap::{ChunkHeightmaps, HeightmapType};
use steel_core::chunk::status::ChunkStatus;
use steel_core::entity::damage::DamageHistory;
use steel_core::level_data::{GameTimeSource, WorldGenerationSettings};
use steel_core::world::{World, WorldConfig, WorldStorageConfig};
use steel_core::worldgen::registry::WorldGeneratorRegistry;
use steel_registry::REGISTRY;
use steel_utils::types::{Difficulty, GameType};
use steel_utils::{BlockStateId, ChunkPos, Identifier};

use crate::full_chunk::FullChunk;
use crate::sections::{SectionBiomes, SectionStates};
use tokio::runtime::Runtime;

/// Dimensions whose `TERRAIN` step this worker can generate.
const SUPPORTED_DIMENSIONS: [&str; 3] = [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end",
];

/// The heightmaps vanilla's `TERRAIN` step maintains.
const WORLDGEN_HEIGHTMAPS: [HeightmapType; 2] =
    [HeightmapType::WorldSurfaceWg, HeightmapType::OceanFloorWg];

/// Everything vanilla's `TERRAIN` step leaves on a proto chunk.
pub struct TerrainChunk {
    pub sections: Vec<SectionStates>,
    /// Filled by vanilla's `BIOMES` step, which the mod also replaces.
    pub biomes: Vec<SectionBiomes>,
    /// Per section, packed `x | y << 4 | z << 8` offsets marked for post-processing.
    pub postprocessing: Vec<Vec<u16>>,
    /// `WORLD_SURFACE_WG` first available Y per column, indexed `x + z * 16`.
    pub world_surface_wg: [i32; 256],
    /// `OCEAN_FLOOR_WG` first available Y per column, indexed `x + z * 16`.
    pub ocean_floor_wg: [i32; 256],
}

/// A generation-only SteelMC world for one dimension.
pub struct Engine {
    world: Arc<World>,
    /// Kept alive for the world: SteelMC holds it only weakly.
    _damage_history: Arc<DamageHistory>,
    /// Runs the world's chunk tasks; also used to block on shutdown.
    runtime: Arc<Runtime>,
}

impl Engine {
    /// Starts SteelMC for `dimension`, caching partly generated chunks in `cache_dir`.
    pub fn new(
        seed: i64,
        dimension: &str,
        cache_dir: &Path,
        threads: usize,
    ) -> Result<Self, String> {
        if !SUPPORTED_DIMENSIONS.contains(&dimension) {
            return Err(format!("unsupported dimension {dimension}"));
        }
        let dimension_key: Identifier = dimension
            .parse()
            .map_err(|error| format!("invalid dimension key {dimension}: {error:?}"))?;

        steel_core::bootstrap::init_globals();

        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(threads)
                .thread_name("oxide-chunk")
                .enable_all()
                .build()
                .map_err(|error| format!("failed to start chunk runtime: {error}"))?,
        );
        let generation_pool = Arc::new(
            rayon::ThreadPoolBuilder::new()
                .num_threads(threads)
                .thread_name(|index| format!("oxide-gen-{index}"))
                .build()
                .map_err(|error| format!("failed to start generation pool: {error}"))?,
        );

        let empty_config = toml::Value::Table(toml::map::Map::new());
        let generators = WorldGeneratorRegistry::new_with_builtins()?;
        let generator_config = generators.validate_config(&dimension_key, &empty_config)?;
        let generator =
            generators.create(None, &generator_config, seed, Arc::clone(&generation_pool))?;
        let dimension_type = generator.dimension_type;
        let generation_settings = WorldGenerationSettings::from_generator_config(
            dimension_key.clone(),
            &generator.config,
            dimension_type.key.clone(),
            dimension_type.min_y,
            dimension_type.height,
        );

        let damage_history = Arc::new(DamageHistory::default());
        let config = WorldConfig {
            damage_history: Arc::clone(&damage_history),
            game_time_source: GameTimeSource::Primary,
            storage: WorldStorageConfig::Disk {
                path: cache_dir.to_string_lossy().into_owned(),
            },
            level_data_path: None,
            generator: Arc::new(generator.generator),
            generation_settings,
            // Nothing is sent to players and no chunk reaches FULL, so these only
            // need to be valid.
            view_distance: 2,
            simulation_distance: 2,
            max_chained_neighbor_updates: 1_000_000,
            compression: None,
            is_flat: generator.is_flat,
            sea_level: generator.sea_level,
            default_gamemode: GameType::Survival,
            difficulty: Difficulty::Normal,
        };
        let world = runtime
            .block_on(World::new_with_config(
                Arc::clone(&runtime),
                dimension_key,
                dimension_type,
                seed,
                config,
                generation_pool,
            ))
            .map_err(|error| format!("failed to create world: {error}"))?;

        Ok(Self {
            world,
            _damage_history: damage_history,
            runtime,
        })
    }

    /// Lowest block Y of the dimension.
    pub fn min_y(&self) -> i32 {
        self.world.dimension_type.min_y
    }

    /// Number of 16-block sections in the dimension.
    pub fn section_count(&self) -> usize {
        (self.world.dimension_type.height / 16) as usize
    }

    /// Asks SteelMC to bring one chunk to `TERRAIN`. The ticket is held until the
    /// handle is dropped.
    pub fn request_terrain(&self, chunk_x: i32, chunk_z: i32) -> ChunkRequestHandle {
        self.world.chunk_map.request_chunks(ChunkRequest {
            status: ChunkStatus::Terrain,
            positions: vec![ChunkPos::new(chunk_x, chunk_z)],
            ticket_kind: ChunkTicketKind::Pregen,
        })
    }

    /// Asks SteelMC to bring a chunk and its 8 neighbours to `FULL`. Once the
    /// neighbours are lit, no other chunk can change the centre's blocks or light.
    /// `LIGHT` cannot be requested on its own: like vanilla's, SteelMC's ticket level
    /// for `LIGHT` is the one for `FULL`.
    pub fn request_full(&self, chunk_x: i32, chunk_z: i32) -> ChunkRequestHandle {
        let positions = (-1..=1)
            .flat_map(|dz| (-1..=1).map(move |dx| ChunkPos::new(chunk_x + dx, chunk_z + dz)))
            .collect();
        self.world.chunk_map.request_chunks(ChunkRequest {
            status: ChunkStatus::Full,
            positions,
            ticket_kind: ChunkTicketKind::Pregen,
        })
    }

    /// Runs SteelMC's chunk scheduling once. The worker never runs a game tick, so
    /// this never overlaps `tick_game`.
    pub fn advance_scheduling(&self) {
        self.world.chunk_map.advance_scheduling();
    }

    /// Reads the `TERRAIN` output of a ready [`Self::request_terrain`].
    pub fn read_terrain(
        &self,
        request: &ChunkRequestHandle,
        chunk_x: i32,
        chunk_z: i32,
    ) -> Result<Option<TerrainChunk>, String> {
        let pos = ChunkPos::new(chunk_x, chunk_z);
        Self::with_ready_chunk(request, pos, ChunkStatus::Terrain, TerrainChunk::read)
    }

    /// Reads the finished centre chunk of a ready [`Self::request_full`].
    pub fn read_full(
        &self,
        request: &ChunkRequestHandle,
        chunk_x: i32,
        chunk_z: i32,
    ) -> Result<Option<FullChunk>, String> {
        let pos = ChunkPos::new(chunk_x, chunk_z);
        let entities = self.world.entity_manager().get_saveable_entities_for_chunk(pos);
        Self::with_ready_chunk(request, pos, ChunkStatus::Full, |chunk| {
            FullChunk::read(chunk, &entities)
        })
    }

    fn with_ready_chunk<T>(
        request: &ChunkRequestHandle,
        chunk_pos: ChunkPos,
        status: ChunkStatus,
        read: impl FnOnce(&Chunk) -> Result<T, String>,
    ) -> Result<Option<T>, String> {
        match request.poll() {
            ChunkRequestState::Pending { .. } => return Ok(None),
            ChunkRequestState::Cancelled => return Err("chunk request was cancelled".to_owned()),
            ChunkRequestState::Ready => {}
        }
        let Some(ready) = request.ready_chunks() else {
            return Ok(None);
        };
        let holder = ready
            .holders
            .iter()
            .find(|holder| holder.get_pos() == chunk_pos)
            .ok_or("requested chunk missing from the ready set")?;
        let chunk = holder
            .try_chunk(status)
            .ok_or_else(|| format!("chunk is not at {status:?}"))?;
        read(chunk).map(Some)
    }

    /// Stops generation, waits for running chunk tasks, and saves the chunk cache.
    /// Follows the order of SteelMC's own server shutdown.
    pub fn shutdown(&self) {
        let chunk_map = &self.world.chunk_map;
        chunk_map.stop_generation_refill_loop();
        chunk_map.task_tracker.close();
        self.runtime.block_on(chunk_map.task_tracker.wait());
        match self.runtime.block_on(self.world.save_all_chunks()) {
            Ok(saved) => log::info!("Saved {saved} cached chunks"),
            Err(error) => log::error!("Failed to save the chunk cache: {error}"),
        }
    }
}

impl TerrainChunk {
    fn read(chunk: &Chunk) -> Result<Self, String> {
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
        let postprocessing = chunk.postprocessing.lock().to_vec();

        let stored = chunk.generation_heightmaps();
        let rebuilt;
        let heightmaps = if WORLDGEN_HEIGHTMAPS
            .iter()
            .all(|&heightmap_type| stored.get(heightmap_type).is_some())
        {
            &*stored
        } else {
            // SteelMC saves only the heightmaps of a chunk's current status (vanilla
            // saves all of them), so a TERRAIN chunk loaded from the cache lacks
            // these. Vanilla updates them on every block write during TERRAIN, so
            // rebuilding them from the final blocks gives the same values.
            let mut fresh = ChunkHeightmaps::empty();
            fresh.prime_from_sections(
                &WORLDGEN_HEIGHTMAPS,
                chunk.min_y(),
                chunk.height(),
                &chunk.sections.sections,
            );
            rebuilt = fresh;
            &rebuilt
        };
        let column_heights = |heightmap_type: HeightmapType| -> Result<[i32; 256], String> {
            let heightmap = heightmaps
                .get(heightmap_type)
                .ok_or_else(|| format!("chunk has no {heightmap_type:?} heightmap"))?;
            Ok(std::array::from_fn(|index| {
                heightmap.get_first_available(index % 16, index / 16)
            }))
        };

        Ok(Self {
            sections,
            biomes,
            postprocessing,
            world_surface_wg: column_heights(HeightmapType::WorldSurfaceWg)?,
            ocean_floor_wg: column_heights(HeightmapType::OceanFloorWg)?,
        })
    }
}

/// Every block state as `(block key, properties)`, indexed by SteelMC state ID.
pub fn block_state_table() -> Vec<(String, Vec<(&'static str, &'static str)>)> {
    let blocks = &REGISTRY.blocks;
    (0..blocks.state_to_block_lookup.len())
        .map(|id| {
            let state = BlockStateId(id as u16);
            let block = blocks.state_to_block_lookup[id];
            (block.key.to_string(), blocks.get_properties(state))
        })
        .collect()
}

/// Every biome key, indexed by SteelMC biome ID.
pub fn biome_table() -> Vec<String> {
    let mut biomes: Vec<(usize, String)> = REGISTRY
        .biomes
        .iter()
        .map(|(id, biome)| (id, biome.key.to_string()))
        .collect();
    biomes.sort_unstable_by_key(|&(id, _)| id);
    biomes.into_iter().map(|(_, key)| key).collect()
}
