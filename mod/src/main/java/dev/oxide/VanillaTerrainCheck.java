package dev.oxide;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/**
 * Checks that everything the {@code TERRAIN} step reads is vanilla's: SteelMC only knows
 * vanilla 26.3's built-in worldgen data, so any datapack or mod change to it would make
 * Oxide's terrain differ.
 */
final class VanillaTerrainCheck {
	/** Registries compared entry by entry with vanilla's built-in copy. */
	private static final Set<ResourceKey<? extends Registry<?>>> COMPARED = Set.of(
		Registries.DIMENSION_TYPE,
		Registries.NOISE_SETTINGS,
		Registries.NOISE,
		Registries.DENSITY_FUNCTION,
		Registries.MATERIAL_RULE,
		Registries.MATERIAL_CONDITION,
		Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
		Registries.CARVER,
		Registries.STRUCTURE,
		Registries.STRUCTURE_SET,
		Registries.TEMPLATE_POOL,
		Registries.PROCESSOR_LIST
	);

	private VanillaTerrainCheck() {
	}

	/** A description of the first difference from vanilla, or empty if there is none. */
	static Optional<String> findDifference(MinecraftServer server) {
		RegistryAccess registries = server.registryAccess();
		HolderLookup.Provider vanilla = VanillaRegistries.createWorldLookup();
		RegistryOps<JsonElement> vanillaOps = vanilla.createSerializationContext(JsonOps.INSTANCE);
		RegistryOps<JsonElement> serverOps = registries.createSerializationContext(JsonOps.INSTANCE);

		for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLD_REGISTRIES) {
			if (!COMPARED.contains(data.key())) {
				continue;
			}
			Optional<String> difference = compareRegistry(data, vanilla, registries, vanillaOps, serverOps);
			if (difference.isPresent()) {
				return difference;
			}
		}
		Optional<String> carvers = compareBiomeCarvers(vanilla, registries);
		if (carvers.isPresent()) {
			return carvers;
		}
		return findStructureTemplateOverride(server);
	}

	private static <T> Optional<String> compareRegistry(RegistryDataLoader.RegistryData<T> data, HolderLookup.Provider vanilla,
		RegistryAccess registries, RegistryOps<JsonElement> vanillaOps, RegistryOps<JsonElement> serverOps) {
		HolderLookup.RegistryLookup<T> vanillaLookup = vanilla.lookupOrThrow(data.key());
		Registry<T> registry = registries.lookupOrThrow(data.key());
		Set<ResourceKey<T>> vanillaKeys = vanillaLookup.listElementIds().collect(Collectors.toSet());
		Set<ResourceKey<T>> serverKeys = registry.registryKeySet();
		if (!vanillaKeys.equals(serverKeys)) {
			List<String> added = serverKeys.stream().filter(key -> !vanillaKeys.contains(key))
				.map(key -> key.identifier().toString()).limit(3).toList();
			List<String> removed = vanillaKeys.stream().filter(key -> !serverKeys.contains(key))
				.map(key -> key.identifier().toString()).limit(3).toList();
			return Optional.of(data.key().identifier() + " entries differ from vanilla (added " + added + ", missing " + removed + ")");
		}
		for (ResourceKey<T> key : vanillaKeys) {
			JsonElement expected = data.elementCodec().encodeStart(vanillaOps, vanillaLookup.getOrThrow(key).value()).result().orElse(null);
			JsonElement actual = data.elementCodec().encodeStart(serverOps, registry.getValueOrThrow(key)).result().orElse(null);
			if (expected == null || !expected.equals(actual)) {
				return Optional.of(key.registry() + " entry " + key.identifier() + " differs from vanilla");
			}
		}
		return Optional.empty();
	}

	/** Biomes are compared only by their carvers: features run in vanilla's own FEATURES step. */
	private static Optional<String> compareBiomeCarvers(HolderLookup.Provider vanilla, RegistryAccess registries) {
		HolderLookup.RegistryLookup<Biome> vanillaBiomes = vanilla.lookupOrThrow(Registries.BIOME);
		Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		for (ResourceKey<Biome> key : vanillaBiomes.listElementIds().toList()) {
			Optional<Biome> biome = biomes.getOptional(key);
			if (biome.isEmpty()) {
				return Optional.of("biome " + key.identifier() + " is missing");
			}
			List<String> expected = carverIds(vanillaBiomes.getOrThrow(key).value().getGenerationSettings().getCarvers());
			List<String> actual = carverIds(biome.get().getGenerationSettings().getCarvers());
			if (!expected.equals(actual)) {
				return Optional.of("biome " + key.identifier() + " has different carvers than vanilla");
			}
		}
		return Optional.empty();
	}

	private static List<String> carverIds(Iterable<Holder<WorldCarver>> carvers) {
		List<String> ids = new ArrayList<>();
		for (Holder<WorldCarver> carver : carvers) {
			ids.add(carver.unwrapKey().map(key -> key.identifier().toString()).orElse("<inline carver>"));
		}
		return ids;
	}

	/** Structure templates are files, not registry entries, so check the packs directly. */
	private static Optional<String> findStructureTemplateOverride(MinecraftServer server) {
		for (PackResources pack : server.getResourceManager().listPacks().toList()) {
			if (pack.knownPackInfo().map(KnownPack::isVanilla).orElse(false)) {
				continue;
			}
			AtomicReference<String> found = new AtomicReference<>();
			pack.listResources(PackType.SERVER_DATA, "minecraft", "structure", (id, resource) -> found.compareAndSet(null, id.toString()));
			if (found.get() != null) {
				return Optional.of("pack " + pack.packId() + " replaces vanilla structure template " + found.get());
			}
		}
		return Optional.empty();
	}
}
