package dev.oxide;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Settings from {@code config/oxide.properties}, created with the defaults on first start. */
record OxideConfig(boolean features, int workerThreads) {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final OxideConfig DEFAULTS = new OxideConfig(true, 0);
	private static final String HEADER = """
		Oxide settings. Changes apply on the next server start.

		features: true lets SteelMC generate whole chunks: trees, ores, structures and light
		as well as biomes and terrain. false keeps SteelMC to biomes and terrain, and vanilla
		generates the rest.

		worker_threads: generation threads per dimension's worker; 0 lets Oxide choose.""";

	static OxideConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("oxide.properties");
		Properties properties = new Properties();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException error) {
				LOGGER.warn("Could not read {}: {}. Using the defaults.", path, error.getMessage());
				return DEFAULTS;
			}
		}
		OxideConfig config = new OxideConfig(
			parseBoolean(properties, "features", DEFAULTS.features()),
			parseInt(properties, "worker_threads", DEFAULTS.workerThreads())
		);
		if (!Files.exists(path)) {
			config.save(path);
		}
		return config;
	}

	private void save(Path path) {
		Properties properties = new Properties();
		properties.setProperty("features", Boolean.toString(this.features));
		properties.setProperty("worker_threads", Integer.toString(this.workerThreads));
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				properties.store(writer, HEADER);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not write {}: {}", path, error.getMessage());
		}
	}

	private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		if (value == null) {
			return fallback;
		}
		value = value.trim();
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return Boolean.parseBoolean(value);
		}
		LOGGER.warn("Oxide setting {}={} is not true or false; using {}", key, value, fallback);
		return fallback;
	}

	private static int parseInt(Properties properties, String key, int fallback) {
		String value = properties.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			int parsed = Integer.parseInt(value.trim());
			if (parsed >= 0 && parsed <= 0xffff) {
				return parsed;
			}
		} catch (NumberFormatException ignored) {
			// Reported below.
		}
		LOGGER.warn("Oxide setting {}={} is not a number from 0 to 65535; using {}", key, value, fallback);
		return fallback;
	}
}
