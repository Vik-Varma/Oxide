package dev.oxide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.oxide.worker.TerrainData;
import dev.oxide.worker.WorkerConnection;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;

/** Oxide terrain for one dimension: its worker and how replies are written into chunks. */
final class LevelTerrain implements AutoCloseable {
	/** The heightmaps vanilla primes after {@code buildTerrain} ({@code ChunkStatusTasks.buildTerrain}). */
	private static final EnumSet<Heightmap.Types> PRIMED_AFTER_TERRAIN = EnumSet.of(
		Heightmap.Types.MOTION_BLOCKING,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
		Heightmap.Types.OCEAN_FLOOR,
		Heightmap.Types.WORLD_SURFACE
	);

	/** {@code -Doxide.prefetch=false} turns prefetching off, e.g. to measure its effect. */
	static final boolean PREFETCH = !"false".equals(System.getProperty("oxide.prefetch"));
	/** Prefetched chunks kept at most; beyond this, stale entries are dropped and new ones skipped. */
	private static final int PREFETCH_LIMIT = 4096;
	/** A prefetched chunk not used within this time is assumed not to reach {@code TERRAIN} soon. */
	private static final long PREFETCH_MAX_AGE_NANOS = 120_000_000_000L;

	private final ChunkGenerator generator;
	private final WorkerConnection worker;
	/** Terrain requested ahead of the {@code TERRAIN} step, by packed chunk position. */
	private final Map<Long, Prefetched> prefetched = new ConcurrentHashMap<>();

	private record Prefetched(CompletableFuture<TerrainData> terrain, long requestedAt) {
	}

	private LevelTerrain(ChunkGenerator generator, WorkerConnection worker) {
		this.generator = generator;
		this.worker = worker;
	}

	/**
	 * Why SteelMC cannot generate this level's terrain, or empty if it can: the level must
	 * be one of the three vanilla dimensions with vanilla's own generator settings and
	 * biome source for it.
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
	static LevelTerrain start(ServerLevel level, Path executable) throws IOException {
		Identifier dimension = level.dimension().identifier();
		Path cacheDir = level.getServer().getWorldPath(LevelResource.ROOT)
			.resolve("oxide").resolve(dimension.getNamespace()).resolve(dimension.getPath());
		WorkerConnection worker = WorkerConnection.start(executable, level.getSeed(), dimension.toString(), cacheDir, 0);
		if (worker.minY() != level.getMinY() || worker.sectionCount() != level.getSectionsCount()) {
			worker.close();
			throw new IOException("worker reports min Y " + worker.minY() + " and " + worker.sectionCount()
				+ " sections, the level has " + level.getMinY() + " and " + level.getSectionsCount());
		}
		return new LevelTerrain(level.getChunkSource().getGenerator(), worker);
	}

	ChunkGenerator generator() {
		return this.generator;
	}

	/** Whether the worker is still usable. */
	boolean healthy() {
		return this.worker.failure() == null;
	}

	/**
	 * Asks the worker for a chunk's terrain before its {@code TERRAIN} step runs, so the
	 * worker always has work queued. Chunk systems that block a thread per step (Moonrise)
	 * otherwise keep only a few requests in flight.
	 */
	void prefetch(ChunkPos pos) {
		if (this.prefetched.size() >= PREFETCH_LIMIT) {
			long now = System.nanoTime();
			this.prefetched.values().removeIf(entry -> now - entry.requestedAt() > PREFETCH_MAX_AGE_NANOS);
			if (this.prefetched.size() >= PREFETCH_LIMIT) {
				return;
			}
		}
		this.prefetched.computeIfAbsent(pos.pack(), key ->
			new Prefetched(this.worker.requestTerrain(pos.x(), pos.z()), System.nanoTime()));
	}

	/** Drops a prefetched chunk that vanilla generates after all. */
	void discardPrefetch(ChunkPos pos) {
		this.prefetched.remove(pos.pack());
	}

	/** The worker's raw {@code TERRAIN} output for a chunk, prefetched if possible. */
	CompletableFuture<TerrainData> requestTerrain(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		Prefetched prefetched = this.prefetched.remove(pos.pack());
		return prefetched != null ? prefetched.terrain() : this.worker.requestTerrain(pos.x(), pos.z());
	}

	/** The vanilla block state for a worker state id. */
	BlockState state(short id) {
		return this.worker.state(id);
	}

	/** Fills a {@code BIOMES}-status proto chunk with the worker's {@code TERRAIN} output. */
	CompletableFuture<ChunkAccess> buildTerrain(ProtoChunk chunk) {
		return this.requestTerrain(chunk)
			.thenApplyAsync(data -> {
				this.apply(chunk, data);
				Heightmap.primeHeightmaps(chunk, PRIMED_AFTER_TERRAIN);
				return chunk;
			}, OxideExecutor.get());
	}

	private void apply(ProtoChunk chunk, TerrainData data) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int sectionIndex = 0; sectionIndex < data.palettes.length; sectionIndex++) {
			short[] palette = data.palettes[sectionIndex];
			short[] indices = data.indices[sectionIndex];
			LevelChunkSection section = chunk.getSection(sectionIndex);
			section.acquire();
			try {
				for (int index = 0; index < 4096; index++) {
					BlockState state = this.worker.state(palette[indices == null ? 0 : indices[index]]);
					// Sections start as air. Other air variants such as cave_air still need writing.
					if (state != air) {
						section.setBlockState(index & 15, index >> 8, (index >> 4) & 15, state, false);
					}
				}
			} finally {
				section.release();
			}
			if (data.postProcessing[sectionIndex].length > 0) {
				chunk.addPackedPostProcess(new ShortArrayList(data.postProcessing[sectionIndex]), sectionIndex);
			}
		}
		chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, packHeightmap(chunk, data.worldSurfaceWg));
		chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, packHeightmap(chunk, data.oceanFloorWg));
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
