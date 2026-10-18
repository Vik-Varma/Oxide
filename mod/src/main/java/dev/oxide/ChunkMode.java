package dev.oxide;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/** How a chunk is generated. Decided once, before {@code BIOMES}, and saved with the chunk. */
public enum ChunkMode {
	/** SteelMC generates biomes and terrain; vanilla runs features and light. */
	TERRAIN,
	/**
	 * SteelMC generates the whole chunk, which replaces the chunk at {@code FEATURES}.
	 * Until then and after, other chunks' features cannot write into it.
	 */
	FULL,
	/** SteelMC's whole chunk is in place. Other chunks' features still cannot write into it. */
	INSTALLED,
	/** Vanilla generated the terrain: blending next to old chunks, or a worker failure. */
	VANILLA;

	/** Whether other chunks' features must not write into the chunk. */
	public boolean sealed() {
		return this == FULL || this == INSTALLED;
	}

	/** The name stored in chunk NBT. */
	public String serializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable ChunkMode bySerializedName(String name) {
		for (ChunkMode mode : values()) {
			if (mode.serializedName().equals(name)) {
				return mode;
			}
		}
		return null;
	}
}
