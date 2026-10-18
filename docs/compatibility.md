# Mod compatibility

Oxide replaces vanilla's `TERRAIN` chunk step with output from SteelMC. It hooks
`ChunkStatusTasks.buildTerrain`, the step as vanilla's chunk pipeline runs it, not
`NoiseBasedChunkGenerator.buildTerrain`, so mods that call the generator directly
still get vanilla's own result. A mod conflicts when it changes that step, the chunk system
that calls it, or the inputs it reads (noise settings, density functions, biome
sources, carvers, structures), since SteelMC would not see those changes.

**Status** says how much is known:

- **Confirmed from source**: read in the mod's own code for 26.3, at the commit listed.
- **Suspected**: a likely conflict that has not been confirmed in source.
- **Untested**: the code is understood but nobody has run it alongside Oxide yet.

**Handling** is what Oxide does when the mod is installed. Oxide never stops the game
from launching; when it disables itself, vanilla generates terrain as usual and the
log says why.

| Mod (mod id) | Status | Conflict | Handling |
|---|---|---|---|
| C2ME (`c2me`, or its module `c2me-rewrites-chunk-system`) | Confirmed from source (`dev/26.3.0`, `361361d`) | `c2me-rewrites-chunk-system` replaces the chunk system and makes `buildTerrain` run inline (`MixinNoiseChunkGenerator` in that module). Its other modules change noise sampling and surface building, which Oxide skips. | Oxide disables itself until tested together |
| Moonrise (`moonrise`) | Confirmed from source (`mc/26.3`, `4ca759d`); untested | Rewrites the chunk system and redirects `buildTerrain`'s `supplyAsync` (`chunk_system/NoiseBasedChunkGeneratorMixin`). Oxide's hook replaces the whole step, so that call never runs; it is unknown whether Moonrise's scheduler copes with a terrain future that completes later on another thread. | Oxide disables itself until tested together |
| TerraBlender (`terrablender`) | Confirmed from source (`26.3`, `7d7a778`) | Changes vanilla's noise settings and biome sources in memory at server start (`LevelUtils.initializeOnServerStart`). These changes are not in the registries, so Oxide's datapack check cannot see them. | Oxide disables itself |
| Lithium (`lithium`) | Confirmed from source (`develop` for 26.3, `416f68d`) | None for terrain: its only `NoiseBasedChunkGenerator` change caches the sea level. | Compatible |
| VMP (`vmp`) | Checked source (`ver/26.1.1`, `7e89476`; no 26.3 branch yet) | No changes to the terrain step. | Compatible |
| Chunky | Checked source (`master`, `3be6b74`) | No changes to the terrain step; pregeneration goes through the normal chunk system, so Oxide speeds it up. | Compatible |
| ScalableLux (`scalablelux`) | Checked source (`ver/26.2.0`, `77c290c`; no 26.3 branch yet) | Lighting only. | Compatible |
| Distant Horizons (`distanthorizons`) | Confirmed from source (`main` for 26.3, `2206fe7`) | Generates far-away chunks by calling `NoiseBasedChunkGenerator.buildTerrain` directly and ignoring the returned future (`StepTerrain.generateGroup`). Oxide's hook is on `ChunkStatusTasks.buildTerrain`, which Distant Horizons does not use. | Compatible: its own generation stays vanilla (no speedup for it); chunks the server generates use Oxide |
| Noisium (`noisium`) | Not available for 26.3 (latest branch `1.21.6`) | Would optimize the same terrain step. | Re-check if it is updated |
| Datapacks or mods that change worldgen (e.g. Terralith, Tectonic) | Handled by design | They change the registries SteelMC copies from vanilla. | Oxide compares the terrain registries with vanilla's built-in ones at server start and disables itself for any difference |

## To check

- Any other mod that rewrites the chunk system or the worldgen executor.
- Moonrise and C2ME together with Oxide, to see whether they can be allowed.
