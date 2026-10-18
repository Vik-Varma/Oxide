package dev.oxide;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mods that Oxide cannot run alongside. Keep in sync with {@code docs/compatibility.md},
 * which records where each conflict was confirmed.
 */
final class ConflictingMods {
	private static final Map<String, String> CONFLICTS = new LinkedHashMap<>();

	static {
		CONFLICTS.put("c2me", "C2ME replaces the chunk system");
		CONFLICTS.put("c2me-rewrites-chunk-system", "C2ME replaces the chunk system");
		CONFLICTS.put("moonrise", "Moonrise replaces the chunk system (untested with Oxide)");
		CONFLICTS.put("terrablender", "TerraBlender changes terrain generation in memory");
	}

	private ConflictingMods() {
	}

	/** The first installed conflicting mod and why it conflicts. */
	static Optional<String> find() {
		FabricLoader loader = FabricLoader.getInstance();
		for (Map.Entry<String, String> conflict : CONFLICTS.entrySet()) {
			if (loader.isModLoaded(conflict.getKey())) {
				return Optional.of(conflict.getValue() + " (" + conflict.getKey() + ")");
			}
		}
		return Optional.empty();
	}
}
