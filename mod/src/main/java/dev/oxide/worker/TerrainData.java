package dev.oxide.worker;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One chunk's {@code TERRAIN} output as sent by the worker. Parsing validates every state
 * id and index, so applying it to a chunk cannot fail half way.
 */
public final class TerrainData {
	/** Per section, a palette of worker state ids. A one-entry palette means a uniform section. */
	public final short[][] palettes;
	/** Per section, 4096 palette indices in {@code (y << 8) | (z << 4) | x} order; null for uniform sections. */
	public final short[][] indices;
	/** Per section, packed {@code x | y << 4 | z << 8} post-processing offsets. */
	public final short[][] postProcessing;
	/** {@code WORLD_SURFACE_WG}, first free Y per column, indexed {@code x + z * 16}. */
	public final int[] worldSurfaceWg;
	/** {@code OCEAN_FLOOR_WG}, first free Y per column, indexed {@code x + z * 16}. */
	public final int[] oceanFloorWg;

	private TerrainData(short[][] palettes, short[][] indices, short[][] postProcessing, int[] worldSurfaceWg, int[] oceanFloorWg) {
		this.palettes = palettes;
		this.indices = indices;
		this.postProcessing = postProcessing;
		this.worldSurfaceWg = worldSurfaceWg;
		this.oceanFloorWg = oceanFloorWg;
	}

	/** Parses a Terrain message body after its request id. */
	static TerrainData read(ByteBuffer buffer, int expectedSections, int stateCount, int minY) throws IOException {
		int sectionCount = Short.toUnsignedInt(buffer.getShort());
		if (sectionCount != expectedSections) {
			throw new IOException("terrain has " + sectionCount + " sections, expected " + expectedSections);
		}
		short[][] palettes = new short[sectionCount][];
		short[][] indices = new short[sectionCount][];
		for (int section = 0; section < sectionCount; section++) {
			int kind = Byte.toUnsignedInt(buffer.get());
			if (kind == 0) {
				palettes[section] = new short[] {readState(buffer, stateCount)};
				continue;
			}
			if (kind != 1) {
				throw new IOException("unknown section kind " + kind);
			}
			int paletteLength = Short.toUnsignedInt(buffer.getShort());
			if (paletteLength == 0) {
				throw new IOException("empty palette");
			}
			short[] palette = new short[paletteLength];
			for (int i = 0; i < paletteLength; i++) {
				palette[i] = readState(buffer, stateCount);
			}
			short[] sectionIndices = new short[4096];
			boolean wide = paletteLength > 256;
			for (int i = 0; i < 4096; i++) {
				int index = wide ? Short.toUnsignedInt(buffer.getShort()) : Byte.toUnsignedInt(buffer.get());
				if (index >= paletteLength) {
					throw new IOException("palette index " + index + " out of range " + paletteLength);
				}
				sectionIndices[i] = (short) index;
			}
			palettes[section] = palette;
			indices[section] = sectionIndices;
		}

		short[][] postProcessing = new short[sectionCount][];
		for (int section = 0; section < sectionCount; section++) {
			int count = Short.toUnsignedInt(buffer.getShort());
			short[] offsets = new short[count];
			for (int i = 0; i < count; i++) {
				offsets[i] = buffer.getShort();
			}
			postProcessing[section] = offsets;
		}

		int maxY = minY + expectedSections * 16;
		int[] worldSurfaceWg = readHeightmap(buffer, minY, maxY);
		int[] oceanFloorWg = readHeightmap(buffer, minY, maxY);
		if (buffer.hasRemaining()) {
			throw new IOException(buffer.remaining() + " unexpected trailing bytes");
		}
		return new TerrainData(palettes, indices, postProcessing, worldSurfaceWg, oceanFloorWg);
	}

	private static int[] readHeightmap(ByteBuffer buffer, int minY, int maxY) throws IOException {
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

	private static short readState(ByteBuffer buffer, int stateCount) throws IOException {
		int state = Short.toUnsignedInt(buffer.getShort());
		if (state >= stateCount) {
			throw new IOException("state id " + state + " out of range " + stateCount);
		}
		return (short) state;
	}
}
