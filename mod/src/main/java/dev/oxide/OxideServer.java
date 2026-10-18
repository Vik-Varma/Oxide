package dev.oxide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import dev.oxide.worker.WorkerExecutable;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Oxide's state for one running server: whether it is enabled, and one worker per dimension. */
final class OxideServer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile @Nullable OxideServer current;

	private final @Nullable Path executable;
	private final OxideConfig config;
	/** Empty for levels Oxide does not handle, so they are only checked once. */
	private final Map<ResourceKey<Level>, Optional<LevelTerrain>> levels = new ConcurrentHashMap<>();

	private OxideServer(@Nullable Path executable, OxideConfig config) {
		this.executable = executable;
		this.config = config;
	}

	/** Decides whether Oxide runs on this server. Called before any level loads. */
	static void start(MinecraftServer server) {
		Optional<String> disabledReason = findDisabledReason(server);
		Path executable = null;
		if (disabledReason.isEmpty()) {
			try {
				executable = WorkerExecutable.find(modVersion()).orElse(null);
				if (executable == null) {
					disabledReason = Optional.of("no worker binary for " + WorkerExecutable.platform()
						+ " (set -D" + WorkerExecutable.PATH_PROPERTY + "=<path> to use a local build)");
				}
			} catch (IOException error) {
				disabledReason = Optional.of("could not prepare the worker binary: " + error.getMessage());
			}
		}
		OxideConfig config = OxideConfig.load();
		if (disabledReason.isPresent()) {
			LOGGER.warn("Oxide is disabled: {}. Vanilla generates terrain.", disabledReason.get());
			current = new OxideServer(null, config);
		} else {
			LOGGER.info("Oxide is enabled, using worker {}; SteelMC generates {}", executable,
				config.features() ? "whole chunks away from existing ones" : "biomes and terrain");
			current = new OxideServer(executable, config);
		}
	}

	private static Optional<String> findDisabledReason(MinecraftServer server) {
		Optional<String> conflict = ConflictingMods.find();
		if (conflict.isPresent()) {
			return conflict;
		}
		long start = System.nanoTime();
		Optional<String> difference = VanillaTerrainCheck.findDifference(server);
		LOGGER.info("Checked worldgen data against vanilla in {} ms", (System.nanoTime() - start) / 1_000_000);
		return difference.map(reason -> "worldgen data is not vanilla: " + reason);
	}

	/** Stops every worker. Their chunk caches are saved on the way out. */
	static void stop() {
		TerrainVerifier.logSummary();
		GenerationStats.logSummary();
		StepTiming.logSummary();
		OxideServer server = current;
		current = null;
		if (server == null) {
			return;
		}
		for (Optional<LevelTerrain> terrain : server.levels.values()) {
			terrain.ifPresent(LevelTerrain::close);
		}
	}

	/** The level's Oxide terrain, starting its worker on first use; null if vanilla should generate. */
	static @Nullable LevelTerrain forLevel(ServerLevel level) {
		OxideServer server = current;
		if (server == null || server.executable == null) {
			return null;
		}
		Optional<LevelTerrain> terrain = server.levels.computeIfAbsent(level.dimension(), key -> server.startLevel(level));
		return terrain.filter(LevelTerrain::healthy).orElse(null);
	}

	private Optional<LevelTerrain> startLevel(ServerLevel level) {
		String name = level.dimension().identifier().toString();
		Optional<String> ineligible = LevelTerrain.findIneligibility(level);
		if (ineligible.isPresent()) {
			LOGGER.info("Oxide skips {}: {}", name, ineligible.get());
			return Optional.empty();
		}
		try {
			LevelTerrain terrain = LevelTerrain.start(level, this.executable, this.config);
			LOGGER.info("Oxide worker started for {}", name);
			return Optional.of(terrain);
		} catch (IOException error) {
			LOGGER.warn("Oxide could not start a worker for {}: {}. Vanilla generates its terrain.", name, error.getMessage());
			return Optional.empty();
		}
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer("oxide")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");
	}
}
