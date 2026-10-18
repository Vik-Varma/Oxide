package dev.oxide;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import dev.oxide.worker.TerrainData;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Decides, per chunk, whether Oxide or vanilla runs the {@code TERRAIN} step. */
public final class TerrainHook {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Set while calling vanilla's step from a fallback, so the hook lets it through. */
	private static final ThreadLocal<Boolean> RUNNING_VANILLA = ThreadLocal.withInitial(() -> false);
	private static final AtomicLong FALLBACKS = new AtomicLong();

	private TerrainHook() {
	}

	/**
	 * Oxide's replacement for {@code ChunkStatusTasks.buildTerrain}, or null to let vanilla
	 * run it.
	 */
	public static @Nullable CompletableFuture<ChunkAccess> buildTerrain(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		if (RUNNING_VANILLA.get() || !(chunk instanceof ProtoChunk protoChunk) || chunk.isUpgrading()) {
			return null;
		}
		LevelTerrain terrain = OxideServer.forLevel(context.level());
		if (terrain == null || terrain.generator() != context.generator()) {
			return null;
		}
		// Next to chunks from before 1.18, vanilla blends terrain using their saved data,
		// which SteelMC does not have.
		if (Blender.of(new WorldGenRegion(context.level(), chunks, step, chunk)) != Blender.empty()) {
			return null;
		}
		if (TerrainVerifier.ENABLED) {
			return verify(context, step, chunks, chunk, terrain);
		}
		return terrain.buildTerrain(protoChunk).exceptionallyCompose(error -> {
			long count = FALLBACKS.incrementAndGet();
			if (count <= 10) {
				LOGGER.warn("Oxide could not generate chunk {}; vanilla generates it: {}", chunk.getPos(), error.getMessage());
			}
			return runVanilla(context, step, chunks, chunk);
		});
	}

	/** Verify mode: vanilla generates the chunk, then the worker's output is compared with it. */
	private static CompletableFuture<ChunkAccess> verify(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk, LevelTerrain terrain) {
		CompletableFuture<TerrainData> workerTerrain = terrain.requestTerrain(chunk);
		return runVanilla(context, step, chunks, chunk).thenCompose(generated -> workerTerrain.handle((data, error) -> {
			if (error != null) {
				LOGGER.warn("Oxide verify: worker failed for chunk {}: {}", generated.getPos(), error.getMessage());
			} else {
				TerrainVerifier.compare(generated, data, terrain);
			}
			return generated;
		}));
	}

	private static CompletableFuture<ChunkAccess> runVanilla(WorldGenContext context, ChunkStep step,
		StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk) {
		RUNNING_VANILLA.set(true);
		try {
			return ChunkStatusTasks.buildTerrain(context, step, chunks, chunk);
		} finally {
			RUNNING_VANILLA.set(false);
		}
	}
}
