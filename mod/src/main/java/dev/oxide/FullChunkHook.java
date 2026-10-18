package dev.oxide;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import dev.oxide.worker.FullChunkData;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Puts SteelMC's whole chunk in place of a {@link ChunkMode#FULL} chunk, and loads its light. */
public final class FullChunkHook {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicLong FAILURES = new AtomicLong();

	private FullChunkHook() {
	}

	/**
	 * Oxide's replacement for {@code ChunkStatusTasks.generateFeatures} for full chunks, or
	 * null to let vanilla run it. The worker's chunk replaces everything in this chunk.
	 *
	 * <p>Next to a chunk SteelMC does not replace, vanilla's features run here first,
	 * synchronously like vanilla's step, to decorate that chunk; they also keep this chunk
	 * decorated if the worker fails. When every neighbour is sealed, nothing outside this
	 * chunk needs them, so they run only if the worker fails, and then write only into this
	 * chunk.
	 */
	public static @Nullable CompletableFuture<ChunkAccess> generateFeatures(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		if (VanillaSteps.running()) {
			return null;
		}
		ChunkMode mode = ChunkModes.get(chunk);
		if (mode == ChunkMode.INSTALLED) {
			// Finished at the TERRAIN step, with every neighbour sealed.
			GenerationStats.FEATURES_SKIPPED.increment();
			return CompletableFuture.completedFuture(chunk);
		}
		if (mode != ChunkMode.FULL) {
			return null;
		}
		LevelTerrain terrain = TerrainHook.handledBy(context, chunk);
		if (terrain == null) {
			// The worker is gone: let other chunks' features write into this one again.
			ChunkModes.set(chunk, ChunkMode.TERRAIN);
			return null;
		}
		CompletableFuture<FullChunkData> full = terrain.requestFull(chunk);
		boolean decorateNeighbours = !neighboursSealed(chunks, chunk.getPos());
		if (decorateNeighbours) {
			VanillaSteps.run(() -> ChunkStatusTasks.generateFeatures(context, step, chunks, chunk));
		} else {
			GenerationStats.FEATURES_SKIPPED.increment();
		}
		return full.handleAsync((data, error) -> {
			if (error == null) {
				terrain.install((ProtoChunk) chunk, data);
				ChunkModes.set(chunk, ChunkMode.INSTALLED);
				GenerationStats.INSTALLED.increment();
				return chunk;
			}
			if (FAILURES.incrementAndGet() <= 10) {
				LOGGER.warn("Oxide could not generate the whole of chunk {}; vanilla decorates it: {}", chunk.getPos(), error.getMessage());
			}
			if (!decorateNeighbours) {
				VanillaSteps.runCenterOnly(() -> ChunkStatusTasks.generateFeatures(context, step, chunks, chunk));
			}
			ChunkModes.set(chunk, ChunkMode.TERRAIN);
			return chunk;
		}, OxideExecutor.get());
	}

	static boolean neighboursSealed(StaticCache2D<GenerationChunkHolder> chunks, ChunkPos pos) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if ((dx != 0 || dz != 0) && !ChunkModes.isSealed(chunks.get(pos.x() + dx, pos.z() + dz).getLatestChunk())) {
					return false;
				}
			}
		}
		return true;
	}

	/** Called at the start of {@code INITIALIZE_LIGHT}: hands an installed chunk's light to the light engine. */
	public static void initializeLight(WorldGenContext context, ChunkAccess chunk) {
		LevelTerrain.loadLight(chunk, context.lightEngine(), context.level().dimensionType().hasSkyLight());
	}

	/**
	 * Oxide's replacement for {@code ChunkStatusTasks.light}, or null to let vanilla run it.
	 * When this chunk and all 8 neighbours have the worker's light, every value vanilla's
	 * propagation could set is already there, so the chunk is lit the way vanilla lights a
	 * chunk loaded from disk. Next to any other chunk, vanilla propagates as usual on top of
	 * the loaded light.
	 */
	public static @Nullable CompletableFuture<ChunkAccess> light(WorldGenContext context, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk) {
		if (!((OxideChunk) chunk).oxide$lightInstalled()) {
			return null;
		}
		ChunkPos pos = chunk.getPos();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				ChunkAccess neighbour = chunks.get(pos.x() + dx, pos.z() + dz).getLatestChunk();
				if (neighbour == null || !ChunkModes.state(neighbour).oxide$lightInstalled()) {
					return null;
				}
			}
		}
		GenerationStats.LIGHT_SKIPPED.increment();
		ThreadedLevelLightEngine lightEngine = context.lightEngine();
		lightEngine.setLightEnabled(pos, true);
		return lightEngine.lightChunk(chunk, true);
	}
}
