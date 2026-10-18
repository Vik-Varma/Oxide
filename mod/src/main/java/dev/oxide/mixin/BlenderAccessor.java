package dev.oxide.mixin;

import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Blender.class)
public interface BlenderAccessor {
	/** How far vanilla looks for old chunks to blend new terrain with. */
	@Accessor("HEIGHT_BLENDING_RANGE_CHUNKS")
	static int oxide$heightBlendingRangeChunks() {
		throw new AssertionError();
	}
}
