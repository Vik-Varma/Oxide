package dev.oxide.mixin;

import java.util.List;
import java.util.Set;

import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a worker chunk replace a proto chunk's ticks, which vanilla never removes. */
@Mixin(ProtoChunkTicks.class)
public interface ProtoChunkTicksAccessor {
	@Accessor
	List<SavedTick<?>> getTicks();

	@Accessor
	Set<SavedTick<?>> getTicksPerPosition();
}
