package dev.oxide;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

/**
 * Step timing ({@code -Doxide.stepTiming=true}): how long each chunk generation step
 * takes, from its start until its result is ready, summed over all chunks. Steps run in
 * parallel, so the totals are thread time, not wall time. Asynchronous steps include
 * time spent waiting in a queue.
 */
public final class StepTiming {
	public static final boolean ENABLED = Boolean.getBoolean("oxide.stepTiming");
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<ChunkStatus, Totals> TOTALS = new ConcurrentHashMap<>();

	private record Totals(LongAdder count, LongAdder nanos) {
	}

	private StepTiming() {
	}

	public static void record(ChunkStatus status, long nanos) {
		Totals totals = TOTALS.computeIfAbsent(status, key -> new Totals(new LongAdder(), new LongAdder()));
		totals.count().increment();
		totals.nanos().add(nanos);
	}

	static void logSummary() {
		if (!ENABLED) {
			return;
		}
		StringBuilder summary = new StringBuilder("Oxide step timing (thread time, start to result):");
		for (ChunkStatus status : ChunkStatus.getStatusList()) {
			Totals totals = TOTALS.get(status);
			if (totals == null) {
				continue;
			}
			long count = totals.count().sum();
			double seconds = totals.nanos().sum() / 1e9;
			summary.append(String.format("%n  %-20s %6d steps %9.2f s total %8.2f ms each",
				status.getName(), count, seconds, seconds * 1000 / count));
		}
		LOGGER.info(summary.toString());
	}
}
