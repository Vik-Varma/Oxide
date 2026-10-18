package dev.oxide.worker;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Blocks, biomes and post-processing of every section, as the worker sends them in both
 * Terrain and Full replies. Parsing validates every id and index, so writing the data
 * into a chunk cannot fail half way.
 */
public final class ChunkSections {
	/** Per section, a palette of worker state ids. A one-entry palette means a uniform section. */
	private final short[][] palettes;
	/**
	 * Per section, 4096 palette indices in {@code (y << 8) | (z << 4) | x} order: a
	 * {@code byte[]} for palettes of up to 256 entries, else a {@code short[]}; null for
	 * uniform sections. Bytes keep whole chunks waiting for their step small.
	 */
	private final Object[] indices;
	/** Per section, the vanilla biome of each palette entry. */
	private final Holder<Biome>[][] biomePalettes;
	/** Per section, 64 biome palette indices in {@code (y << 4) | (z << 2) | x} order. */
	private final byte[][] biomeIndices;
	/** Per section, packed {@code x | y << 4 | z << 8} post-processing offsets. */
	public final short[][] postProcessing;

	private ChunkSections(short[][] palettes, Object[] indices, Holder<Biome>[][] biomePalettes, byte[][] biomeIndices,
		short[][] postProcessing) {
		this.palettes = palettes;
		this.indices = indices;
		this.biomePalettes = biomePalettes;
		this.biomeIndices = biomeIndices;
		this.postProcessing = postProcessing;
	}

	/** The worker state id at a block index of a section. */
	public short state(int section, int index) {
		Object sectionIndices = this.indices[section];
		int paletteIndex;
		if (sectionIndices == null) {
			paletteIndex = 0;
		} else if (sectionIndices instanceof byte[] narrow) {
			paletteIndex = Byte.toUnsignedInt(narrow[index]);
		} else {
			paletteIndex = ((short[]) sectionIndices)[index];
		}
		return this.palettes[section][paletteIndex];
	}

	/** The biome of one 4×4×4 cell, with coordinates relative to the section. */
	public Holder<Biome> biome(int section, int x, int y, int z) {
		return this.biomePalettes[section][this.biomeIndices[section][(y << 4) | (z << 2) | x]];
	}

	public int sectionCount() {
		return this.palettes.length;
	}

	@SuppressWarnings("unchecked")
	static ChunkSections read(ByteBuffer buffer, int expectedSections, int stateCount, Holder<Biome>[] biomes) throws IOException {
		int sectionCount = Short.toUnsignedInt(buffer.getShort());
		if (sectionCount != expectedSections) {
			throw new IOException("chunk has " + sectionCount + " sections, expected " + expectedSections);
		}
		short[][] palettes = new short[sectionCount][];
		Object[] indices = new Object[sectionCount];
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
			if (paletteLength > 256) {
				short[] wide = new short[4096];
				for (int i = 0; i < 4096; i++) {
					wide[i] = (short) checkIndex(Short.toUnsignedInt(buffer.getShort()), paletteLength);
				}
				indices[section] = wide;
			} else {
				byte[] narrow = new byte[4096];
				buffer.get(narrow);
				for (byte index : narrow) {
					checkIndex(Byte.toUnsignedInt(index), paletteLength);
				}
				indices[section] = narrow;
			}
			palettes[section] = palette;
		}

		Holder<Biome>[][] biomePalettes = new Holder[sectionCount][];
		byte[][] biomeIndices = new byte[sectionCount][64];
		for (int section = 0; section < sectionCount; section++) {
			int paletteLength = Byte.toUnsignedInt(buffer.get());
			if (paletteLength == 0) {
				throw new IOException("empty biome palette");
			}
			Holder<Biome>[] palette = new Holder[paletteLength];
			for (int i = 0; i < paletteLength; i++) {
				int id = Short.toUnsignedInt(buffer.getShort());
				if (id >= biomes.length) {
					throw new IOException("biome id " + id + " out of range " + biomes.length);
				}
				palette[i] = biomes[id];
			}
			buffer.get(biomeIndices[section]);
			for (byte index : biomeIndices[section]) {
				if (Byte.toUnsignedInt(index) >= paletteLength) {
					throw new IOException("biome palette index " + Byte.toUnsignedInt(index) + " out of range " + paletteLength);
				}
			}
			biomePalettes[section] = palette;
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
		return new ChunkSections(palettes, indices, biomePalettes, biomeIndices, postProcessing);
	}

	private static int checkIndex(int index, int paletteLength) throws IOException {
		if (index >= paletteLength) {
			throw new IOException("palette index " + index + " out of range " + paletteLength);
		}
		return index;
	}

	private static short readState(ByteBuffer buffer, int stateCount) throws IOException {
		int state = Short.toUnsignedInt(buffer.getShort());
		if (state >= stateCount) {
			throw new IOException("state id " + state + " out of range " + stateCount);
		}
		return (short) state;
	}
}
