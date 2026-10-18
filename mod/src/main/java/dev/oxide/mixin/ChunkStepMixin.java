package dev.oxide.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.oxide.StepTiming;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/** Times generation steps for {@link StepTiming}; does nothing unless it is enabled. */
@Mixin(ChunkStep.class)
abstract class ChunkStepMixin {
	@WrapMethod(method = "apply")
	private CompletableFuture<ChunkAccess> oxide$timeStep(WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache,
		ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		ChunkStep step = (ChunkStep) (Object) this;
		if (!StepTiming.ENABLED || !chunk.getPersistedStatus().isBefore(step.targetStatus())) {
			return original.call(context, cache, chunk);
		}
		long start = System.nanoTime();
		return original.call(context, cache, chunk)
			.whenComplete((result, error) -> StepTiming.record(step.targetStatus(), System.nanoTime() - start));
	}
}
