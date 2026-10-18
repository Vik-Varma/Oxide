package dev.oxide;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Counts what Oxide did, logged when the server stops. */
final class GenerationStats {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<ChunkMode, LongAdder> DECIDED = new EnumMap<>(ChunkMode.class);
	static final LongAdder INSTALLED = new LongAdder();
	static final LongAdder FEATURES_SKIPPED = new LongAdder();
	static final LongAdder LIGHT_LOADED = new LongAdder();
	static final LongAdder LIGHT_SKIPPED = new LongAdder();

	static {
		for (ChunkMode mode : ChunkMode.values()) {
			DECIDED.put(mode, new LongAdder());
		}
	}

	private GenerationStats() {
	}

	static void decided(ChunkMode mode) {
		DECIDED.get(mode).increment();
	}

	static void logSummary() {
		long decided = DECIDED.values().stream().mapToLong(LongAdder::sum).sum();
		if (decided == 0) {
			return;
		}
		LOGGER.info("Oxide generated {} chunks: {} whole, {} terrain only, {} by vanilla. {} whole chunks installed "
				+ "({} without running vanilla features), light loaded for {}, light propagation skipped for {}.",
			decided, DECIDED.get(ChunkMode.FULL).sum(), DECIDED.get(ChunkMode.TERRAIN).sum(), DECIDED.get(ChunkMode.VANILLA).sum(),
			INSTALLED.sum(), FEATURES_SKIPPED.sum(), LIGHT_LOADED.sum(), LIGHT_SKIPPED.sum());
	}
}
