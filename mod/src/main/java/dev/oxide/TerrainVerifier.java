package dev.oxide;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import dev.oxide.worker.ChunkSections;
import dev.oxide.worker.TerrainData;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * Verify mode ({@code -Doxide.verify=true}): vanilla generates every chunk's terrain as
 * usual, and the worker's output for the same chunk is compared with it. The world stays
 * vanilla; mismatches are logged.
 */
final class TerrainVerifier {
	static final boolean ENABLED = Boolean.getBoolean("oxide.verify");
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong MISMATCHED = new AtomicLong();

	private TerrainVerifier() {
	}

	/** Compares vanilla's finished {@code BIOMES} and {@code TERRAIN} output in {@code chunk} with the worker's. */
	static void compare(ChunkAccess chunk, TerrainData data, LevelTerrain terrain) {
		StringBuilder problems = new StringBuilder();
		int differentBlocks = 0;
		String firstBlock = null;
		int differentBiomes = 0;
		String firstBiome = null;
		ChunkSections sections = data.sections;
		for (int sectionIndex = 0; sectionIndex < sections.sectionCount(); sectionIndex++) {
			LevelChunkSection section = chunk.getSection(sectionIndex);
			for (int index = 0; index < 4096; index++) {
				BlockState expected = section.getBlockState(index & 15, index >> 8, (index >> 4) & 15);
				BlockState actual = terrain.state(sections.state(sectionIndex, index));
				if (expected != actual) {
					differentBlocks++;
					if (firstBlock == null) {
						firstBlock = "section " + sectionIndex + " index " + index + ": vanilla " + expected + ", worker " + actual;
					}
				}
			}
			for (int cell = 0; cell < 64; cell++) {
				int x = cell & 3;
				int y = cell >> 4;
				int z = (cell >> 2) & 3;
				Holder<Biome> expected = section.getNoiseBiome(x, y, z);
				Holder<Biome> actual = sections.biome(sectionIndex, x, y, z);
				if (!expected.equals(actual)) {
					differentBiomes++;
					if (firstBiome == null) {
						firstBiome = "section " + sectionIndex + " cell " + cell + ": vanilla " + expected.getRegisteredName()
							+ ", worker " + actual.getRegisteredName();
					}
				}
			}
			ShortList vanillaOffsets = chunk.getPostProcessing()[sectionIndex];
			short[] expectedOffsets = vanillaOffsets == null ? new short[0] : vanillaOffsets.toShortArray();
			short[] actualOffsets = sections.postProcessing[sectionIndex];
			if (!Arrays.equals(expectedOffsets, actualOffsets)) {
				short[] expectedSorted = expectedOffsets.clone();
				short[] actualSorted = actualOffsets.clone();
				Arrays.sort(expectedSorted);
				Arrays.sort(actualSorted);
				// Order matters: post-processing schedules fluid ticks in list order.
				String kind = Arrays.equals(expectedSorted, actualSorted) ? " post-processing order differs" : " post-processing differs";
				problems.append("; section ").append(sectionIndex).append(kind).append(" (vanilla ")
					.append(expectedOffsets.length).append(", worker ").append(actualOffsets.length).append(")");
			}
		}
		if (differentBlocks > 0) {
			problems.append("; ").append(differentBlocks).append(" blocks differ, first at ").append(firstBlock);
		}
		if (differentBiomes > 0) {
			problems.append("; ").append(differentBiomes).append(" biome cells differ, first at ").append(firstBiome);
		}
		compareHeightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG, data.worldSurfaceWg, problems);
		compareHeightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG, data.oceanFloorWg, problems);

		long checked = CHECKED.incrementAndGet();
		if (!problems.isEmpty()) {
			long mismatched = MISMATCHED.incrementAndGet();
			LOGGER.warn("Oxide verify: chunk {} differs from vanilla{} ({} of {} chunks differ so far)",
				chunk.getPos(), problems, mismatched, checked);
		} else if (Long.bitCount(checked) == 1) {
			LOGGER.info("Oxide verify: {} chunks checked, {} differ from vanilla", checked, MISMATCHED.get());
		}
	}

	static void logSummary() {
		if (ENABLED) {
			LOGGER.info("Oxide verify summary: {} chunks checked, {} differ from vanilla", CHECKED.get(), MISMATCHED.get());
		}
	}

	private static void compareHeightmap(ChunkAccess chunk, Heightmap.Types type, int[] actual, StringBuilder problems) {
		Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
		int different = 0;
		for (int i = 0; i < 256; i++) {
			if (heightmap.getFirstAvailable(i & 15, i >> 4) != actual[i]) {
				different++;
			}
		}
		if (different > 0) {
			problems.append("; ").append(type).append(" differs in ").append(different).append(" columns");
		}
	}
}
