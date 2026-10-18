package dev.oxide;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Oxide's own threads for writing worker replies into chunks and for vanilla fallbacks.
 *
 * <p>Not vanilla's background executor: chunk systems such as Moonrise block their
 * generation threads on the {@code TERRAIN} future, and if those were the threads that
 * had to complete it, a busy server could deadlock. Not the worker's reader thread
 * either: a fallback runs vanilla's terrain step, inline under Moonrise, and would stall
 * every other reply.
 */
final class OxideExecutor {
	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
		Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
		task -> {
			Thread thread = new Thread(task, "oxide-terrain-" + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	);

	private OxideExecutor() {
	}

	static Executor get() {
		return EXECUTOR;
	}
}
