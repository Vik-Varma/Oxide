package dev.oxide.worker;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** One chunk's {@code BIOMES} and {@code TERRAIN} output as sent by the worker. */
public final class TerrainData {
	public final ChunkSections sections;
	/** {@code WORLD_SURFACE_WG}, first free Y per column, indexed {@code x + z * 16}. */
	public final int[] worldSurfaceWg;
	/** {@code OCEAN_FLOOR_WG}, first free Y per column, indexed {@code x + z * 16}. */
	public final int[] oceanFloorWg;

	private TerrainData(ChunkSections sections, int[] worldSurfaceWg, int[] oceanFloorWg) {
		this.sections = sections;
		this.worldSurfaceWg = worldSurfaceWg;
		this.oceanFloorWg = oceanFloorWg;
	}

	/** Parses a Terrain message body after its request id. */
	static TerrainData read(ByteBuffer buffer, int expectedSections, int stateCount, Holder<Biome>[] biomes, int minY)
		throws IOException {
		ChunkSections sections = ChunkSections.read(buffer, expectedSections, stateCount, biomes);
		int maxY = minY + expectedSections * 16;
		int[] worldSurfaceWg = readHeightmap(buffer, minY, maxY);
		int[] oceanFloorWg = readHeightmap(buffer, minY, maxY);
		if (buffer.hasRemaining()) {
			throw new IOException(buffer.remaining() + " unexpected trailing bytes");
		}
		return new TerrainData(sections, worldSurfaceWg, oceanFloorWg);
	}

	static int[] readHeightmap(ByteBuffer buffer, int minY, int maxY) throws IOException {
		int[] heights = new int[256];
		for (int i = 0; i < 256; i++) {
			int height = buffer.getInt();
			if (height < minY || height > maxY) {
				throw new IOException("heightmap value " + height + " outside " + minY + ".." + maxY);
			}
			heights[i] = height;
		}
		return heights;
	}
}
