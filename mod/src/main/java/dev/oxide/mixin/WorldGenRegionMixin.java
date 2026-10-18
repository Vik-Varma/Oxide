package dev.oxide.mixin;

import java.util.function.Function;

import dev.oxide.ChunkModes;
import dev.oxide.VanillaSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.TickContainerAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps features out of sealed chunks ({@link dev.oxide.ChunkMode#sealed()}): during the
 * {@code FEATURES} step, writes into a sealed chunk other than the one being decorated are
 * dropped, together with their block entities, ticks, post-processing and entities. The
 * worker's chunk already contains what those features would have placed there.
 */
@Mixin(WorldGenRegion.class)
abstract class WorldGenRegionMixin {
	@Shadow
	@Final
	private ChunkStep generatingStep;
	@Shadow
	@Final
	private ChunkAccess center;

	@Shadow
	public abstract ChunkAccess getChunk(int chunkX, int chunkZ);

	@Shadow
	private boolean isWithinWriteZone(int chunkX, int chunkZ) {
		throw new AssertionError();
	}

	@Unique
	private boolean oxide$sealed(BlockPos pos) {
		if (this.generatingStep.targetStatus() != ChunkStatus.FEATURES) {
			return false;
		}
		int chunkX = SectionPos.blockToSectionCoord(pos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
		if (!this.isWithinWriteZone(chunkX, chunkZ)) {
			return false;
		}
		ChunkAccess chunk = this.getChunk(chunkX, chunkZ);
		return chunk != this.center && (VanillaSteps.centerOnly() || ChunkModes.isSealed(chunk));
	}

	@Inject(method = "ensureCanWrite", at = @At("HEAD"), cancellable = true)
	private void oxide$dropSealedWrite(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
		if (this.oxide$sealed(pos)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "markPosForPostProcessing", at = @At("HEAD"), cancellable = true)
	private void oxide$dropSealedPostProcessing(BlockPos pos, CallbackInfo callback) {
		if (this.oxide$sealed(pos)) {
			callback.cancel();
		}
	}

	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void oxide$dropSealedEntity(Entity entity, CallbackInfoReturnable<Boolean> callback) {
		if (this.oxide$sealed(entity.blockPosition())) {
			callback.setReturnValue(true);
		}
	}

	@Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
	private void oxide$hideSealedBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callback) {
		if (this.oxide$sealed(pos)) {
			callback.setReturnValue(null);
		}
	}

	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/WorldGenTickAccess;<init>(Ljava/util/function/Function;)V")
	)
	private Function<BlockPos, TickContainerAccess<?>> oxide$dropSealedTicks(Function<BlockPos, TickContainerAccess<?>> containers) {
		return pos -> this.oxide$sealed(pos) ? BlackholeTickAccess.emptyContainer() : containers.apply(pos);
	}
}
