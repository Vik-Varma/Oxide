package dev.oxide.worker;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

/**
 * A whole generated chunk as sent by the worker (Full reply): everything vanilla saves
 * for a lit proto chunk. Parsing validates it, so installing it cannot fail half way.
 */
public final class FullChunkData {
	/** The heightmaps a lit proto chunk keeps. */
	private static final List<Heightmap.Types> FINAL_HEIGHTMAPS = List.of(
		Heightmap.Types.WORLD_SURFACE,
		Heightmap.Types.OCEAN_FLOOR,
		Heightmap.Types.MOTION_BLOCKING,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
	);

	/** The chunk's light, kept until the {@code INITIALIZE_LIGHT} step. */
	public record Light(List<LightSection> block, List<LightSection> sky) {
	}

	/** One light section; {@code data} is null when every value is 0. */
	public record LightSection(int sectionY, byte @Nullable [] data) {
	}

	/** A scheduled tick at a position relative to the chunk. Proto chunk ticks have no delay. */
	public record Tick<T>(T type, int x, int y, int z, TickPriority priority) {
	}

	public final ChunkSections sections;
	/** First free Y per column, indexed {@code x + z * 16}. */
	public final Map<Heightmap.Types, int[]> heightmaps;
	/** Sky light is empty in dimensions without it. */
	public final Light light;
	public final List<Tick<Block>> blockTicks;
	public final List<Tick<Fluid>> fluidTicks;
	/** Block entity NBT for {@code ChunkAccess.setBlockEntityNbt}. */
	public final List<CompoundTag> blockEntities;
	/** Entity NBT for {@code ProtoChunk.addEntity}. */
	public final List<CompoundTag> entities;

	private FullChunkData(ChunkSections sections, Map<Heightmap.Types, int[]> heightmaps, List<LightSection> blockLight,
		List<LightSection> skyLight, List<Tick<Block>> blockTicks, List<Tick<Fluid>> fluidTicks, List<CompoundTag> blockEntities,
		List<CompoundTag> entities) {
		this.sections = sections;
		this.heightmaps = heightmaps;
		this.light = new Light(blockLight, skyLight);
		this.blockTicks = blockTicks;
		this.fluidTicks = fluidTicks;
		this.blockEntities = blockEntities;
		this.entities = entities;
	}

	/** Parses a Full message body after its request id. */
	static FullChunkData read(ByteBuffer buffer, int expectedSections, int stateCount, Holder<Biome>[] biomes, int minY)
		throws IOException {
		ChunkSections sections = ChunkSections.read(buffer, expectedSections, stateCount, biomes);
		int maxY = minY + expectedSections * 16;

		Map<Heightmap.Types, int[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
		int heightmapCount = Byte.toUnsignedInt(buffer.get());
		for (int i = 0; i < heightmapCount; i++) {
			String name = WorkerConnection.readString(buffer);
			Heightmap.Types type = FINAL_HEIGHTMAPS.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
				.orElseThrow(() -> new IOException("unexpected heightmap " + name));
			heightmaps.put(type, TerrainData.readHeightmap(buffer, minY, maxY));
		}
		if (heightmaps.size() != FINAL_HEIGHTMAPS.size()) {
			throw new IOException("expected heightmaps " + FINAL_HEIGHTMAPS + ", got " + heightmaps.keySet());
		}

		int minLightSection = (minY >> 4) - 1;
		List<LightSection> blockLight = readLight(buffer, minLightSection, expectedSections + 2);
		List<LightSection> skyLight = readLight(buffer, minLightSection, expectedSections + 2);
		List<Tick<Block>> blockTicks = readTicks(buffer, BuiltInRegistries.BLOCK, minY, maxY);
		List<Tick<Fluid>> fluidTicks = readTicks(buffer, BuiltInRegistries.FLUID, minY, maxY);
		List<CompoundTag> blockEntities = readNbtList(buffer);
		List<CompoundTag> entities = readNbtList(buffer);
		if (buffer.hasRemaining()) {
			throw new IOException(buffer.remaining() + " unexpected trailing bytes");
		}
		return new FullChunkData(sections, heightmaps, blockLight, skyLight, blockTicks, fluidTicks, blockEntities, entities);
	}

	private static List<LightSection> readLight(ByteBuffer buffer, int minLightSection, int lightSectionCount) throws IOException {
		int count = Short.toUnsignedInt(buffer.getShort());
		List<LightSection> sections = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int index = Short.toUnsignedInt(buffer.getShort());
			if (index >= lightSectionCount) {
				throw new IOException("light section " + index + " out of range " + lightSectionCount);
			}
			int present = Byte.toUnsignedInt(buffer.get());
			byte[] data = null;
			if (present == 1) {
				data = new byte[2048];
				buffer.get(data);
			} else if (present != 0) {
				throw new IOException("unknown light section kind " + present);
			}
			sections.add(new LightSection(minLightSection + index, data));
		}
		return sections;
	}

	private static <T> List<Tick<T>> readTicks(ByteBuffer buffer, Registry<T> registry, int minY, int maxY) throws IOException {
		int count = buffer.getInt();
		if (count < 0 || count > buffer.remaining()) {
			throw new IOException("invalid tick count " + count);
		}
		List<Tick<T>> ticks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String name = WorkerConnection.readString(buffer);
			Identifier id = Identifier.tryParse(name);
			Optional<T> type = id == null ? Optional.empty() : registry.getOptional(id);
			int x = Byte.toUnsignedInt(buffer.get());
			int y = buffer.getShort();
			int z = Byte.toUnsignedInt(buffer.get());
			int priority = buffer.get();
			if (type.isEmpty()) {
				throw new IOException("tick for unknown " + name);
			}
			if (x > 15 || z > 15 || y < minY || y >= maxY) {
				throw new IOException("tick outside the chunk at " + x + " " + y + " " + z);
			}
			if (priority < TickPriority.EXTREMELY_HIGH.getValue() || priority > TickPriority.EXTREMELY_LOW.getValue()) {
				throw new IOException("invalid tick priority " + priority);
			}
			ticks.add(new Tick<>(type.get(), x, y, z, TickPriority.byValue(priority)));
		}
		return ticks;
	}

	private static List<CompoundTag> readNbtList(ByteBuffer buffer) throws IOException {
		int count = buffer.getInt();
		if (count < 0 || count > buffer.remaining()) {
			throw new IOException("invalid NBT list length " + count);
		}
		List<CompoundTag> tags = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int length = buffer.getInt();
			if (length <= 0 || length > buffer.remaining()) {
				throw new IOException("invalid NBT length " + length);
			}
			ByteArrayInputStream bytes = new ByteArrayInputStream(buffer.array(), buffer.arrayOffset() + buffer.position(), length);
			buffer.position(buffer.position() + length);
			DataInputStream input = new DataInputStream(bytes);
			tags.add(NbtIo.read(input));
			if (input.available() != 0) {
				throw new IOException("trailing bytes after NBT");
			}
		}
		return tags;
	}
}
