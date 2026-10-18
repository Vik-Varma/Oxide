package dev.oxide.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Resolves the worker's block state table (Ready message) to vanilla block states. */
final class BlockStateTable {
	private BlockStateTable() {
	}

	/**
	 * Reads {@code state_count} entries and resolves each one. Fails if any block, property
	 * or value does not exist in vanilla, or if a block's properties are incomplete: then
	 * SteelMC and this game disagree on block data and nothing it sends can be trusted.
	 */
	static BlockState[] read(ByteBuffer buffer) throws IOException {
		int count = buffer.getInt();
		if (count <= 0 || count > 65536) {
			throw new IOException("invalid block state count " + count);
		}
		BlockState[] states = new BlockState[count];
		for (int id = 0; id < count; id++) {
			String blockName = WorkerConnection.readString(buffer);
			Identifier blockId = Identifier.tryParse(blockName);
			Optional<Block> block = blockId == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(blockId);
			if (block.isEmpty()) {
				throw new IOException("state " + id + ": unknown block " + blockName);
			}
			BlockState state = block.get().defaultBlockState();
			int propertyCount = Byte.toUnsignedInt(buffer.get());
			if (propertyCount != state.getProperties().size()) {
				throw new IOException("state " + id + ": " + blockName + " has " + propertyCount
					+ " properties, vanilla has " + state.getProperties().size());
			}
			for (int i = 0; i < propertyCount; i++) {
				String name = WorkerConnection.readString(buffer);
				String value = WorkerConnection.readString(buffer);
				Property<?> property = block.get().getStateDefinition().getProperty(name);
				if (property == null) {
					throw new IOException("state " + id + ": " + blockName + " has no property " + name);
				}
				state = withValue(state, property, value, id);
			}
			states[id] = state;
		}
		return states;
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value, int id)
		throws IOException {
		Optional<T> parsed = property.getValue(value);
		if (parsed.isEmpty()) {
			throw new IOException("state " + id + ": invalid value " + value + " for " + property.getName());
		}
		return state.setValue(property, parsed.get());
	}
}
