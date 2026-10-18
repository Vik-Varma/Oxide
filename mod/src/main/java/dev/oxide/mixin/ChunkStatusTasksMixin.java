package dev.oxide.mixin;

import java.util.concurrent.CompletableFuture;

import dev.oxide.TerrainHook;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the {@code TERRAIN} step where vanilla's chunk pipeline runs it, not
 * {@code NoiseBasedChunkGenerator.buildTerrain}, so direct callers of the generator keep
 * vanilla's behaviour.
 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {
	@Inject(method = "generateStructureReferences", at = @At("HEAD"))
	private static void oxide$prefetchTerrain(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		TerrainHook.prefetch(context, chunk);
	}

	@Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
	private static void oxide$buildTerrain(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		CompletableFuture<ChunkAccess> terrain = TerrainHook.buildTerrain(context, step, chunks, chunk);
		if (terrain != null) {
			callback.setReturnValue(terrain);
		}
	}
}
