package dev.oxide;

import dev.oxide.worker.FullChunkData;
import org.jspecify.annotations.Nullable;

/** Oxide's state on every chunk, added by {@code ChunkAccessMixin}. */
public interface OxideChunk {
	/** How the chunk is generated, or null if Oxide has not decided. */
	@Nullable ChunkMode oxide$mode();

	void oxide$setMode(@Nullable ChunkMode mode);

	/** Light from the worker, waiting for the {@code INITIALIZE_LIGHT} step; not saved. */
	FullChunkData.@Nullable Light oxide$pendingLight();

	void oxide$setPendingLight(FullChunkData.@Nullable Light light);

	/** Whether the worker's light was loaded into the light engine; not saved. */
	boolean oxide$lightInstalled();

	void oxide$setLightInstalled(boolean installed);
}
