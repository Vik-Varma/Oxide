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
log says why. When it overrides a mod, Oxide generates the terrain step even though
that mod is installed.

| Mod (mod id) | Status | Conflict | Handling |
|---|---|---|---|
| C2ME (`c2me`) | Confirmed from source (`dev/26.3.0`, `361361d`); tested with release `0.4.2-alpha.0.106+26.3` | Its chunk system (`c2me-rewrites-chunk-system`) still runs vanilla's step through `ChunkStatusTasks` and waits on the future without blocking. Its only `ChunkStatusTasks` mixin is in the OpenCL module, which is not built. In verify mode, C2ME's own terrain differed from vanilla in 195 of 3,267 chunks (aquifer water, a few cave blocks), where plain vanilla differed in none. | **Oxide overrides it**: Oxide generates the terrain, so those chunks get vanilla's terrain. Tested on a 26.3 server: all three dimensions, no fallbacks |
| Moonrise (`moonrise`) | Confirmed from source (`mc/26.3`, `4ca759d`); tested with release `1.2.0+61c1328` | Its chunk system runs vanilla's step through `ChunkStatusTasks`, then blocks a generation thread on the future (`ChunkUpgradeGenericStatusTask`) until Oxide's terrain arrives. It logs "Future status not complete after scheduling" once; that is expected. Oxide writes replies on its own threads, so that wait cannot deadlock. | **Oxide overrides it**. Tested on a 26.3 server: verify mode found 0 differences in 3,267 chunks and real runs had no fallbacks. Needs prefetching (on by default): with it, Moonrise + Oxide took 31.1 s vs 50.1 s for Moonrise alone; without it, 92.7 s, because the blocked threads keep few requests in flight ([benchmarks](benchmarks.md)) |
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
- Re-test C2ME and Moonrise after their updates; both hook code near Oxide's.
