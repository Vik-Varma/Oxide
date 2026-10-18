package dev.oxide;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.minecraft.world.level.chunk.ChunkAccess;

/** Runs vanilla's own step code from inside Oxide's hooks without the hooks taking it over again. */
public final class VanillaSteps {
	private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<Boolean> CENTER_ONLY = ThreadLocal.withInitial(() -> false);

	private VanillaSteps() {
	}

	/** Whether vanilla's step is running on this thread at Oxide's request; hooks must let it through. */
	static boolean running() {
		return RUNNING.get();
	}

	/** Whether the running step may write only into the chunk it generates. */
	public static boolean centerOnly() {
		return CENTER_ONLY.get();
	}

	/**
	 * Runs vanilla's step letting it write only into the chunk it generates, so it can run
	 * off vanilla's generation thread without racing other chunks' steps.
	 */
	static CompletableFuture<ChunkAccess> runCenterOnly(Supplier<CompletableFuture<ChunkAccess>> step) {
		CENTER_ONLY.set(true);
		try {
			return run(step);
		} finally {
			CENTER_ONLY.set(false);
		}
	}

	static CompletableFuture<ChunkAccess> run(Supplier<CompletableFuture<ChunkAccess>> step) {
		RUNNING.set(true);
		try {
			return step.get();
		} finally {
			RUNNING.set(false);
		}
	}
}
