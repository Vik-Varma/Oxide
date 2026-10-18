package dev.oxide.mixin;

import java.util.concurrent.CompletableFuture;

import dev.oxide.FullChunkHook;
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
 * Hooks generation steps where vanilla's chunk pipeline runs them, not in the chunk
 * generator, so direct callers of the generator keep vanilla's behaviour.
 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {
	@Inject(method = "generateStructureReferences", at = @At("HEAD"))
	private static void oxide$structureReferences(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		TerrainHook.structureReferences(context, chunks, chunk);
	}

	@Inject(method = "generateBiomes", at = @At("HEAD"), cancellable = true)
	private static void oxide$generateBiomes(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		CompletableFuture<ChunkAccess> biomes = TerrainHook.generateBiomes(context, step, chunks, chunk);
		if (biomes != null) {
			callback.setReturnValue(biomes);
		}
	}

	@Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
	private static void oxide$buildTerrain(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		CompletableFuture<ChunkAccess> terrain = TerrainHook.buildTerrain(context, step, chunks, chunk);
		if (terrain != null) {
			callback.setReturnValue(terrain);
		}
	}

	@Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
	private static void oxide$generateFeatures(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		CompletableFuture<ChunkAccess> features = FullChunkHook.generateFeatures(context, step, chunks, chunk);
		if (features != null) {
			callback.setReturnValue(features);
		}
	}

	@Inject(method = "initializeLight", at = @At("HEAD"))
	private static void oxide$initializeLight(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		FullChunkHook.initializeLight(context, chunk);
	}

	@Inject(method = "light", at = @At("HEAD"), cancellable = true)
	private static void oxide$light(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
		CompletableFuture<ChunkAccess> light = FullChunkHook.light(context, chunks, chunk);
		if (light != null) {
			callback.setReturnValue(light);
		}
	}
}
