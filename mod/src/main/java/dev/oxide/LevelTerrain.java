package dev.oxide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.oxide.mixin.BlenderAccessor;
import dev.oxide.mixin.ProtoChunkTicksAccessor;
import dev.oxide.worker.ChunkSections;
import dev.oxide.worker.FullChunkData;
import dev.oxide.worker.TerrainData;
import dev.oxide.worker.WorkerConnection;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ScheduledTick;

/** Oxide generation for one dimension: its worker and how replies are written into chunks. */
final class LevelTerrain implements AutoCloseable {
	/** The heightmaps vanilla primes after {@code buildTerrain} ({@code ChunkStatusTasks.buildTerrain}). */
	private static final EnumSet<Heightmap.Types> PRIMED_AFTER_TERRAIN = EnumSet.of(
		Heightmap.Types.MOTION_BLOCKING,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
		Heightmap.Types.OCEAN_FLOOR,
		Heightmap.Types.WORLD_SURFACE
	);
	/**
	 * Chunks within this distance of a chunk decide whether SteelMC can generate all of it:
	 * its blocks come from its own and its neighbours' features, and those features read
	 * the chunks around them.
	 */
	private static final int FULL_RADIUS = 2;
	/**
	 * Mods that replace vanilla's light engine. With one of them, light from the worker is
	 * not loaded and the replacement lights every chunk itself.
	 */
	private static final List<String> LIGHT_ENGINE_MODS = List.of("moonrise", "scalablelux", "starlight");
	private static final boolean LOAD_WORKER_LIGHT = LIGHT_ENGINE_MODS.stream().noneMatch(FabricLoader.getInstance()::isModLoaded);

	/** {@code -Doxide.prefetch=false} turns prefetching off, e.g. to measure its effect. */
	static final boolean PREFETCH = !"false".equals(System.getProperty("oxide.prefetch"));
	/** Prefetched chunks kept at most; beyond this, stale entries are dropped and new ones skipped. */
	private static final int PREFETCH_LIMIT = 4096;
	/** Whole chunks are larger, so fewer are requested ahead. */
	private static final int FULL_PREFETCH_LIMIT = 1024;
	/** A prefetched chunk not used within this time is assumed not to be needed soon. */
	private static final long PREFETCH_MAX_AGE_NANOS = 120_000_000_000L;

	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final WorkerConnection worker;
	private final boolean features;
	/** Terrain requested ahead of the {@code BIOMES} and {@code TERRAIN} steps, by packed chunk position. */
	private final Map<Long, Prefetched<TerrainData>> terrain = new ConcurrentHashMap<>();
	/** Whole chunks requested ahead of the {@code FEATURES} step, by packed chunk position. */
	private final Map<Long, Prefetched<FullChunkData>> full = new ConcurrentHashMap<>();

	private record Prefetched<T>(CompletableFuture<T> future, long requestedAt) {
	}

	private LevelTerrain(ServerLevel level, WorkerConnection worker, boolean features) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.worker = worker;
		this.features = features;
	}

	/**
	 * Why SteelMC cannot generate this level, or empty if it can: the level must be one of
	 * the three vanilla dimensions with vanilla's own generator settings and biome source
	 * for it.
	 */
	static Optional<String> findIneligibility(ServerLevel level) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
			return Optional.of("generator is " + generator.getClass().getSimpleName() + ", not vanilla noise");
		}
		BiomeSource biomes = generator.getBiomeSource();
		boolean vanilla;
		if (level.dimension() == Level.OVERWORLD) {
			vanilla = noise.stable(NoiseGeneratorSettings.OVERWORLD)
				&& biomes instanceof MultiNoiseBiomeSource multiNoise
				&& multiNoise.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
		} else if (level.dimension() == Level.NETHER) {
			vanilla = noise.stable(NoiseGeneratorSettings.NETHER)
				&& biomes instanceof MultiNoiseBiomeSource multiNoise
				&& multiNoise.stable(MultiNoiseBiomeSourceParameterLists.NETHER);
		} else if (level.dimension() == Level.END) {
			vanilla = noise.stable(NoiseGeneratorSettings.END) && biomes instanceof TheEndBiomeSource;
		} else {
			return Optional.of("not a vanilla dimension");
		}
		return vanilla ? Optional.empty() : Optional.of("uses a non-default world preset or biome source");
	}

	/** Starts this level's worker. The level must have passed {@link #findIneligibility}. */
	static LevelTerrain start(ServerLevel level, Path executable, OxideConfig config) throws IOException {
		Identifier dimension = level.dimension().identifier();
		Path cacheDir = level.getServer().getWorldPath(LevelResource.ROOT)
			.resolve("oxide").resolve(dimension.getNamespace()).resolve(dimension.getPath());
		WorkerConnection worker = WorkerConnection.start(executable, level.getSeed(), dimension.toString(), cacheDir,
			config.workerThreads(), level.registryAccess().lookupOrThrow(Registries.BIOME));
		if (worker.minY() != level.getMinY() || worker.sectionCount() != level.getSectionsCount()) {
			worker.close();
			throw new IOException("worker reports min Y " + worker.minY() + " and " + worker.sectionCount()
				+ " sections, the level has " + level.getMinY() + " and " + level.getSectionsCount());
		}
		return new LevelTerrain(level, worker, config.features());
	}

	ChunkGenerator generator() {
		return this.generator;
	}

	/** Whether the worker is still usable. */
	boolean healthy() {
		return this.worker.failure() == null;
	}

	/**
	 * Decides how a chunk is generated. SteelMC generates all of it only when no chunk
	 * within {@link #FULL_RADIUS} existed before: next to existing chunks, vanilla's
	 * features must see what is really there.
	 */
	ChunkMode decideMode(StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		// Blender.of's own first check: vanilla blends only near chunks from before 1.18,
		// whose saved data SteelMC does not have.
		if (this.level.getChunkSource().chunkMap.isOldChunkAround(pos, BlenderAccessor.oxide$heightBlendingRangeChunks())) {
			return ChunkMode.VANILLA;
		}
		if (!this.features || TerrainVerifier.ENABLED) {
			return ChunkMode.TERRAIN;
		}
		for (int dz = -FULL_RADIUS; dz <= FULL_RADIUS; dz++) {
			for (int dx = -FULL_RADIUS; dx <= FULL_RADIUS; dx++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				ChunkAccess other = chunks.get(pos.x() + dx, pos.z() + dz).getLatestChunk();
				if (other == null || existedBefore(other)) {
					return ChunkMode.TERRAIN;
				}
			}
		}
		return ChunkMode.FULL;
	}

	/**
	 * Whether a chunk has blocks Oxide did not generate, or has already been decorated.
	 * Chunks Oxide gave terrain to count as new: their terrain is SteelMC's.
	 */
	private static boolean existedBefore(ChunkAccess chunk) {
		ChunkStatus status = chunk.getPersistedStatus();
		if (status.isOrAfter(ChunkStatus.FEATURES)) {
			return true;
		}
		if (!status.isOrAfter(ChunkStatus.TERRAIN)) {
			return false;
		}
		ChunkMode mode = ChunkModes.get(chunk);
		return mode == null || mode == ChunkMode.VANILLA;
	}

	/**
	 * Asks the worker for a chunk before its {@code BIOMES} step runs, so the worker always
	 * has work queued: the whole chunk for a full chunk, its terrain otherwise. Chunk
	 * systems that block a thread per step (Moonrise) otherwise keep only a few requests in
	 * flight.
	 */
	void prefetch(ChunkPos pos, ChunkMode mode) {
		if (!PREFETCH) {
			return;
		}
		if (mode == ChunkMode.FULL) {
			if (hasRoom(this.full, FULL_PREFETCH_LIMIT)) {
				this.fullForBiomes(pos);
			}
		} else if (hasRoom(this.terrain, PREFETCH_LIMIT)) {
			this.terrainForBiomes(pos);
		}
	}

	private static boolean hasRoom(Map<Long, ? extends Prefetched<?>> prefetched, int limit) {
		if (prefetched.size() < limit) {
			return true;
		}
		long now = System.nanoTime();
		prefetched.values().removeIf(entry -> now - entry.requestedAt() > PREFETCH_MAX_AGE_NANOS);
		return prefetched.size() < limit;
	}

	/** Drops requests for a chunk that vanilla generates after all. */
	void discardPrefetch(ChunkPos pos) {
		this.terrain.remove(pos.pack());
		this.full.remove(pos.pack());
	}

	/** The worker's terrain for a chunk, prefetched if possible; kept for the {@code TERRAIN} step. */
	private CompletableFuture<TerrainData> terrainForBiomes(ChunkPos pos) {
		return this.terrain.computeIfAbsent(pos.pack(), key ->
			new Prefetched<>(this.worker.requestTerrain(pos.x(), pos.z()), System.nanoTime())).future();
	}

	/** The worker's whole chunk, prefetched if possible; kept for the {@code TERRAIN} or {@code FEATURES} step. */
	private CompletableFuture<FullChunkData> fullForBiomes(ChunkPos pos) {
		return this.full.computeIfAbsent(pos.pack(), key ->
			new Prefetched<>(this.worker.requestFull(pos.x(), pos.z()), System.nanoTime())).future();
	}

	/** The worker's terrain for a chunk, prefetched if possible. */
	CompletableFuture<TerrainData> requestTerrain(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		Prefetched<TerrainData> prefetched = this.terrain.remove(pos.pack());
		return prefetched != null ? prefetched.future() : this.worker.requestTerrain(pos.x(), pos.z());
	}

	/** The worker's whole chunk, prefetched if possible. */
	CompletableFuture<FullChunkData> requestFull(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		Prefetched<FullChunkData> prefetched = this.full.remove(pos.pack());
		return prefetched != null ? prefetched.future() : this.worker.requestFull(pos.x(), pos.z());
	}

	/** The vanilla block state for a worker state id. */
	BlockState state(short id) {
		return this.worker.state(id);
	}

	/**
	 * Fills a {@code STRUCTURE_REFERENCES}-status proto chunk with the worker's biomes,
	 * taken from the whole chunk for a full chunk, so it needs one request in all.
	 */
	CompletableFuture<ChunkAccess> fillBiomes(ProtoChunk chunk, ChunkMode mode) {
		ChunkPos pos = chunk.getPos();
		CompletableFuture<ChunkSections> biomes = mode == ChunkMode.FULL
			? this.fullForBiomes(pos).thenApply(data -> data.sections)
				.exceptionallyCompose(error -> this.terrainForBiomes(pos).thenApply(data -> data.sections))
			: this.terrainForBiomes(pos).thenApply(data -> data.sections);
		return biomes
			.thenApplyAsync(sections -> {
				int quartMinX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
				int quartMinY = QuartPos.fromSection(chunk.getMinSectionY());
				int quartMinZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
				chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> {
					int y = quartY - quartMinY;
					return sections.biome(y >> 2, quartX - quartMinX, y & 3, quartZ - quartMinZ);
				});
				return chunk;
			}, OxideExecutor.get());
	}

	/**
	 * Puts the worker's whole chunk in place at the {@code TERRAIN} step, for a full chunk
	 * whose neighbours are all sealed: no vanilla feature will read its undecorated
	 * terrain, so it can be finished at once and its {@code FEATURES} step skipped.
	 */
	CompletableFuture<ChunkAccess> installAtTerrain(ProtoChunk chunk) {
		return this.requestFull(chunk)
			.thenApplyAsync(data -> {
				this.install(chunk, data);
				Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
				ChunkModes.set(chunk, ChunkMode.INSTALLED);
				GenerationStats.INSTALLED.increment();
				return chunk;
			}, OxideExecutor.get());
	}

	/** Fills a {@code BIOMES}-status proto chunk with the worker's {@code TERRAIN} output. */
	CompletableFuture<ChunkAccess> buildTerrain(ProtoChunk chunk) {
		return this.requestTerrain(chunk)
			.thenApplyAsync(data -> {
				this.applyTerrain(chunk, data);
				Heightmap.primeHeightmaps(chunk, PRIMED_AFTER_TERRAIN);
				return chunk;
			}, OxideExecutor.get());
	}

	private void applyTerrain(ProtoChunk chunk, TerrainData data) {
		BlockState air = Blocks.AIR.defaultBlockState();
		ChunkSections sections = data.sections;
		for (int sectionIndex = 0; sectionIndex < sections.sectionCount(); sectionIndex++) {
			LevelChunkSection section = chunk.getSection(sectionIndex);
			section.acquire();
			try {
				for (int index = 0; index < 4096; index++) {
					BlockState state = this.worker.state(sections.state(sectionIndex, index));
					// Sections start as air. Other air variants such as cave_air still need writing.
					if (state != air) {
						section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, state, false);
					}
				}
			} finally {
				section.release();
			}
			if (sections.postProcessing[sectionIndex].length > 0) {
				chunk.addPackedPostProcess(new ShortArrayList(sections.postProcessing[sectionIndex]), sectionIndex);
			}
		}
		chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, packHeightmap(chunk, data.worldSurfaceWg));
		chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, packHeightmap(chunk, data.oceanFloorWg));
	}

	/**
	 * Replaces everything in a decorated proto chunk with the worker's whole chunk, as if
	 * vanilla had loaded it lit from disk. Only this chunk changes: it is sealed, so no
	 * other chunk writes into it meanwhile, and its sections are swapped for complete new
	 * ones, so chunks reading it from other threads never see a half-written section.
	 */
	void install(ProtoChunk chunk, FullChunkData data) {
		ChunkPos pos = chunk.getPos();
		ChunkSections sections = data.sections;
		LevelChunkSection[] chunkSections = chunk.getSections();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
		int quartMinX = QuartPos.fromBlock(pos.getMinBlockX());
		int quartMinZ = QuartPos.fromBlock(pos.getMinBlockZ());
		for (int sectionIndex = 0; sectionIndex < chunkSections.length; sectionIndex++) {
			LevelChunkSection previous = chunkSections[sectionIndex];
			LevelChunkSection section = new LevelChunkSection(this.level.palettedContainerFactory());
			int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
			int minY = SectionPos.sectionToBlockCoord(sectionY);
			for (int index = 0; index < 4096; index++) {
				int x = index & 15;
				int y = index >> 8;
				int z = (index >> 4) & 15;
				BlockState state = this.worker.state(sections.state(sectionIndex, index));
				if (state != air) {
					section.setBlockState(x, y, z, state, false);
				}
				// Vanilla registers points of interest (bells, beds, bee nests) as features
				// place them; the swap below bypasses that.
				BlockState old = previous.getBlockState(x, y, z);
				if (old != state && (PoiTypes.hasPoi(old) || PoiTypes.hasPoi(state))) {
					this.level.updatePOIOnBlockStateChange(blockPos.set(pos.getMinBlockX() + x, minY + y, pos.getMinBlockZ() + z), old, state);
				}
			}
			int finalSectionIndex = sectionIndex;
			int quartMinY = QuartPos.fromSection(sectionY);
			section.fillBiomesFromNoise((quartX, quartY, quartZ) ->
				sections.biome(finalSectionIndex, quartX - quartMinX, quartY - quartMinY, quartZ - quartMinZ), quartMinX, quartMinY, quartMinZ);
			chunkSections[sectionIndex] = section;
		}

		for (int sectionIndex = 0; sectionIndex < chunkSections.length; sectionIndex++) {
			chunk.getPostProcessing()[sectionIndex] = null;
			if (sections.postProcessing[sectionIndex].length > 0) {
				chunk.addPackedPostProcess(new ShortArrayList(sections.postProcessing[sectionIndex]), sectionIndex);
			}
		}
		data.heightmaps.forEach((type, heights) -> chunk.setHeightmap(type, packHeightmap(chunk, heights)));

		for (BlockPos blockEntity : List.copyOf(chunk.getBlockEntitiesPos())) {
			chunk.removeBlockEntity(blockEntity);
		}
		data.blockEntities.forEach(chunk::setBlockEntityNbt);

		clearTicks(chunk.getBlockTicks());
		clearTicks(chunk.getFluidTicks());
		for (FullChunkData.Tick<Block> tick : data.blockTicks) {
			chunk.getBlockTicks().schedule(new ScheduledTick<>(tick.type(), tickPos(pos, tick), 0, tick.priority(), 0));
		}
		for (FullChunkData.Tick<Fluid> tick : data.fluidTicks) {
			chunk.getFluidTicks().schedule(new ScheduledTick<>(tick.type(), tickPos(pos, tick), 0, tick.priority(), 0));
		}

		chunk.getEntities().clear();
		data.entities.forEach(chunk::addEntity);

		if (LOAD_WORKER_LIGHT) {
			((OxideChunk) chunk).oxide$setPendingLight(data.light);
		}
		chunk.markUnsaved();
	}

	private static BlockPos tickPos(ChunkPos pos, FullChunkData.Tick<?> tick) {
		return new BlockPos(pos.getMinBlockX() + tick.x(), tick.y(), pos.getMinBlockZ() + tick.z());
	}

	private static void clearTicks(Object ticks) {
		ProtoChunkTicksAccessor accessor = (ProtoChunkTicksAccessor) ticks;
		accessor.getTicks().clear();
		accessor.getTicksPerPosition().clear();
	}

	/**
	 * Hands the worker's light for an installed chunk to the light engine, the way vanilla
	 * does for a lit chunk read from disk, before the {@code INITIALIZE_LIGHT} step runs.
	 */
	static void loadLight(ChunkAccess chunk, ThreadedLevelLightEngine lightEngine, boolean hasSkyLight) {
		OxideChunk oxideChunk = (OxideChunk) chunk;
		FullChunkData.Light light = oxideChunk.oxide$pendingLight();
		if (light == null) {
			return;
		}
		oxideChunk.oxide$setPendingLight(null);
		ChunkPos pos = chunk.getPos();
		lightEngine.retainData(pos, true);
		queueLight(lightEngine, LightLayer.BLOCK, pos, light.block());
		if (hasSkyLight) {
			queueLight(lightEngine, LightLayer.SKY, pos, light.sky());
		}
		oxideChunk.oxide$setLightInstalled(true);
		GenerationStats.LIGHT_LOADED.increment();
	}

	private static void queueLight(ThreadedLevelLightEngine lightEngine, LightLayer layer, ChunkPos pos, List<FullChunkData.LightSection> sections) {
		for (FullChunkData.LightSection section : sections) {
			if (section.data() != null) {
				lightEngine.queueSectionData(layer, SectionPos.of(pos, section.sectionY()), new DataLayer(section.data()));
			}
		}
	}

	/** Packs heights the way {@code Heightmap} stores them: offset from min Y, in {@code ceillog2(height + 1)} bits. */
	private static long[] packHeightmap(ChunkAccess chunk, int[] heights) {
		SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(chunk.getHeight() + 1), 256);
		for (int i = 0; i < 256; i++) {
			storage.set(i, heights[i] - chunk.getMinY());
		}
		return storage.getRaw();
	}

	@Override
	public void close() {
		this.worker.close();
	}
}
