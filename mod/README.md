# Oxide Fabric mod

Server-side Fabric mod for Minecraft 26.3. It replaces vanilla's `TERRAIN` chunk step
with output from [`oxide-worker`](../worker/), one worker process per dimension.
Every other step (structures, biomes, features, light, mobs) stays vanilla.

Versions come from Fabric's official 26.3 template: Loader 0.19.5, Fabric API
0.163.0+26.3, Loom 1.18-SNAPSHOT, Java 25.

## How it decides

At server start (`OxideServer.start`) Oxide turns itself off, and vanilla generates
terrain, if:

- a conflicting mod is installed (`ConflictingMods`, see
  [`docs/compatibility.md`](../docs/compatibility.md));
- any registry the `TERRAIN` step reads differs from vanilla's built-in copy, or a pack
  replaces a vanilla structure template (`VanillaTerrainCheck`);
- no worker binary is available for the platform.

Per dimension (`LevelTerrain.findIneligibility`) it needs vanilla's own generator
settings and biome source for the overworld, the Nether or the End. Per chunk
(`TerrainHook`) it falls back to vanilla for chunks being upgraded from old versions,
chunks next to pre-1.18 terrain (blending), and any request the worker fails.

The hook is on `ChunkStatusTasks.buildTerrain`, the step as vanilla's chunk pipeline
runs it. Mods that call `NoiseBasedChunkGenerator.buildTerrain` directly keep getting
vanilla's result.

The worker caches partly generated chunks in `<world>/oxide/<namespace>/<dimension>/`.
Deleting that folder while the server is stopped is always safe.

## Develop

```sh
JAVA_HOME=<JDK 25> ./gradlew build
JAVA_HOME=<JDK 25> ./gradlew runServer -PoxideWorker=../worker/target/release/oxide-worker
```

Without `-PoxideWorker` (and without a bundled binary) Oxide disables itself.

[`tools/server_parity.py`](../tools/server_parity.py) runs the dev server with and
without the worker on the same seed and compares the generated chunks.
