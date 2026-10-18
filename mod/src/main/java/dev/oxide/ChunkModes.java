package dev.oxide;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import org.jspecify.annotations.Nullable;

/** Reads and writes the {@link ChunkMode} stored on chunks. */
public final class ChunkModes {
	private ChunkModes() {
	}

	public static @Nullable ChunkMode get(ChunkAccess chunk) {
		return state(chunk).oxide$mode();
	}

	/** Oxide's state of a chunk; for a promoted chunk, that of the level chunk it wraps. */
	public static OxideChunk state(ChunkAccess chunk) {
		return (OxideChunk) (chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk);
	}

	public static void set(ChunkAccess chunk, ChunkMode mode) {
		((OxideChunk) chunk).oxide$setMode(mode);
	}

	public static boolean isSealed(@Nullable ChunkAccess chunk) {
		if (chunk == null) {
			return false;
		}
		ChunkMode mode = get(chunk);
		return mode != null && mode.sealed();
	}
}
