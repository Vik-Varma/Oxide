package dev.oxide.mixin;

import dev.oxide.OxideChunk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Oxide's state when a proto chunk becomes a level chunk: neighbours still being lit
 * look it up after the promotion.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
		at = @At("RETURN"))
	private void oxide$copyState(ServerLevel level, ProtoChunk protoChunk, LevelChunk.@Nullable PostLoadProcessor postLoad, CallbackInfo callback) {
		OxideChunk from = (OxideChunk) protoChunk;
		OxideChunk to = (OxideChunk) this;
		to.oxide$setMode(from.oxide$mode());
		to.oxide$setLightInstalled(from.oxide$lightInstalled());
	}
}
