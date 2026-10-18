package dev.oxide;

import org.jspecify.annotations.Nullable;

/** The chunk mode carried through {@code SerializableChunkData}, added by its mixin. */
public interface OxideChunkData {
	@Nullable ChunkMode oxide$mode();

	void oxide$setMode(@Nullable ChunkMode mode);
}
