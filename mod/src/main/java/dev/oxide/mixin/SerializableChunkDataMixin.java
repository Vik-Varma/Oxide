package dev.oxide.mixin;

import dev.oxide.ChunkMode;
import dev.oxide.OxideChunk;
import dev.oxide.OxideChunkData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Saves and loads each chunk's {@link ChunkMode} as {@code oxide:mode} in the chunk NBT. */
@Mixin(SerializableChunkData.class)
abstract class SerializableChunkDataMixin implements OxideChunkData {
	@Unique
	private static final String MODE_TAG = "oxide:mode";

	@Unique
	private @Nullable ChunkMode oxide$mode;

	@Override
	public @Nullable ChunkMode oxide$mode() {
		return this.oxide$mode;
	}

	@Override
	public void oxide$setMode(@Nullable ChunkMode mode) {
		this.oxide$mode = mode;
	}

	@Inject(method = "copyOf", at = @At("RETURN"))
	private static void oxide$copyMode(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> callback) {
		((OxideChunkData) (Object) callback.getReturnValue()).oxide$setMode(((OxideChunk) chunk).oxide$mode());
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void oxide$writeMode(CallbackInfoReturnable<CompoundTag> callback) {
		if (this.oxide$mode != null) {
			callback.getReturnValue().putString(MODE_TAG, this.oxide$mode.serializedName());
		}
	}

	@Inject(method = "parse", at = @At("RETURN"))
	private static void oxide$parseMode(LevelHeightAccessor levelHeight, PalettedContainerFactory containerFactory, CompoundTag chunkData,
		CallbackInfoReturnable<SerializableChunkData> callback) {
		SerializableChunkData data = callback.getReturnValue();
		if (data != null) {
			((OxideChunkData) (Object) data).oxide$setMode(chunkData.getString(MODE_TAG).map(ChunkMode::bySerializedName).orElse(null));
		}
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void oxide$readMode(ServerLevel level, PoiManager poiManager, RegionStorageInfo regionInfo, ChunkPos pos,
		CallbackInfoReturnable<ProtoChunk> callback) {
		((OxideChunk) callback.getReturnValue()).oxide$setMode(this.oxide$mode);
	}
}
