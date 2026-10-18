package dev.oxide;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import dev.oxide.worker.TerrainData;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Decides each chunk's {@link ChunkMode} and runs the {@code BIOMES} and {@code TERRAIN} steps with the worker. */
public final class TerrainHook {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicLong FALLBACKS = new AtomicLong();

	private TerrainHook() {
	}

	/**
	 * Called at the start of a chunk's {@code STRUCTURE_REFERENCES} step, which only chunks
	 * on their way to {@code BIOMES} reach (unlike {@code STRUCTURE_STARTS}, which runs 8
	 * chunks around every generated chunk). Decides the chunk's mode and requests its
	 * terrain from the worker early.
	 */
	public static void structureReferences(WorldGenContext context, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		LevelTerrain terrain = handledBy(context, chunk);
		if (terrain != null) {
			ChunkMode mode = mode(terrain, chunks, chunk);
			if (mode != ChunkMode.VANILLA) {
				terrain.prefetch(chunk.getPos(), mode);
			}
		}
	}

	/** Oxide's replacement for {@code ChunkStatusTasks.generateBiomes}, or null to let vanilla run it. */
	public static @Nullable CompletableFuture<ChunkAccess> generateBiomes(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		LevelTerrain terrain = handledBy(context, chunk);
		if (terrain == null || TerrainVerifier.ENABLED) {
			return null;
		}
		ChunkMode mode = mode(terrain, chunks, chunk);
		if (mode == ChunkMode.VANILLA) {
			return null;
		}
		return terrain.fillBiomes((ProtoChunk) chunk, mode).exceptionallyComposeAsync(error -> {
			warnFallback(chunk, error);
			return VanillaSteps.run(() -> ChunkStatusTasks.generateBiomes(context, step, chunks, chunk));
		}, OxideExecutor.get());
	}

	/** Oxide's replacement for {@code ChunkStatusTasks.buildTerrain}, or null to let vanilla run it. */
	public static @Nullable CompletableFuture<ChunkAccess> buildTerrain(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		LevelTerrain terrain = handledBy(context, chunk);
		if (terrain == null) {
			return null;
		}
		ChunkMode mode = mode(terrain, chunks, chunk);
		if (mode == ChunkMode.VANILLA) {
			terrain.discardPrefetch(chunk.getPos());
			return null;
		}
		if (TerrainVerifier.ENABLED) {
			return verify(context, step, chunks, chunk, terrain);
		}
		ProtoChunk protoChunk = (ProtoChunk) chunk;
		if (mode == ChunkMode.FULL && FullChunkHook.neighboursSealed(chunks, chunk.getPos())) {
			// If the whole chunk fails, the terrain alone; FEATURES then retries the whole chunk.
			return terrain.installAtTerrain(protoChunk)
				.exceptionallyComposeAsync(error -> buildTerrain(context, step, chunks, protoChunk, terrain), OxideExecutor.get());
		}
		return buildTerrain(context, step, chunks, protoChunk, terrain);
	}

	private static CompletableFuture<ChunkAccess> buildTerrain(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ProtoChunk chunk, LevelTerrain terrain) {
		return terrain.buildTerrain(chunk).exceptionallyComposeAsync(error -> {
			warnFallback(chunk, error);
			return VanillaSteps.run(() -> ChunkStatusTasks.buildTerrain(context, step, chunks, chunk));
		}, OxideExecutor.get());
	}

	/** The level's Oxide generation if it should handle this chunk's step, or null. */
	static @Nullable LevelTerrain handledBy(WorldGenContext context, ChunkAccess chunk) {
		if (VanillaSteps.running() || !(chunk instanceof ProtoChunk) || chunk.isUpgrading()) {
			return null;
		}
		LevelTerrain terrain = OxideServer.forLevel(context.level());
		return terrain != null && terrain.generator() == context.generator() ? terrain : null;
	}

	/**
	 * The chunk's mode, decided now if it has none: chunks loaded from a save made without
	 * Oxide may have passed {@code STRUCTURE_REFERENCES} already.
	 */
	private static ChunkMode mode(LevelTerrain terrain, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		ChunkMode mode = ChunkModes.get(chunk);
		if (mode == null) {
			mode = terrain.decideMode(chunks, chunk);
			ChunkModes.set(chunk, mode);
			GenerationStats.decided(mode);
		}
		return mode;
	}

	static void warnFallback(ChunkAccess chunk, Throwable error) {
		long count = FALLBACKS.incrementAndGet();
		if (count <= 10) {
			LOGGER.warn("Oxide could not generate chunk {}; vanilla generates it: {}", chunk.getPos(), error.getMessage());
		}
	}

	/** Verify mode: vanilla generates the chunk, then the worker's output is compared with it. */
	private static CompletableFuture<ChunkAccess> verify(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, LevelTerrain terrain) {
		CompletableFuture<TerrainData> workerTerrain = terrain.requestTerrain(chunk);
		return VanillaSteps.run(() -> ChunkStatusTasks.buildTerrain(context, step, chunks, chunk))
			.thenCompose(generated -> workerTerrain.handle((data, error) -> {
				if (error != null) {
					LOGGER.warn("Oxide verify: worker failed for chunk {}: {}", generated.getPos(), error.getMessage());
				} else {
					TerrainVerifier.compare(generated, data, terrain);
				}
				return generated;
			}));
	}
}
