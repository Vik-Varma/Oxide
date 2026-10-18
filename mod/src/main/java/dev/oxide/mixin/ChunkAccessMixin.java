package dev.oxide.mixin;

import dev.oxide.ChunkMode;
import dev.oxide.OxideChunk;
import dev.oxide.worker.FullChunkData;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
abstract class ChunkAccessMixin implements OxideChunk {
	/** Volatile: neighbouring chunks read it from other generation threads. */
	@Unique
	private volatile @Nullable ChunkMode oxide$mode;
	@Unique
	private volatile FullChunkData.@Nullable Light oxide$pendingLight;
	@Unique
	private volatile boolean oxide$lightInstalled;

	@Override
	public @Nullable ChunkMode oxide$mode() {
		return this.oxide$mode;
	}

	@Override
	public void oxide$setMode(@Nullable ChunkMode mode) {
		this.oxide$mode = mode;
	}

	@Override
	public FullChunkData.@Nullable Light oxide$pendingLight() {
		return this.oxide$pendingLight;
	}

	@Override
	public void oxide$setPendingLight(FullChunkData.@Nullable Light light) {
		this.oxide$pendingLight = light;
	}

	@Override
	public boolean oxide$lightInstalled() {
		return this.oxide$lightInstalled;
	}

	@Override
	public void oxide$setLightInstalled(boolean installed) {
		this.oxide$lightInstalled = installed;
	}
}
