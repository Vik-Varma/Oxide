# Oxide

A Fabric mod that makes Minecraft world generation faster by handing the expensive
parts to [SteelMC](https://github.com/Steel-Foundation/SteelMC) (via the
[Damascus](https://github.com/Vik-Varma/Damascus) fork), running as a separate
native process.

> [!WARNING]
> Pre-alpha planning stage. Nothing here runs yet. Back up worlds before trying it.

## Scope

- **Minecraft 26.3, Fabric only.**
- **World generation only.** Gameplay, ticking, lighting and saving stay in vanilla.
- **Server-side.** Works in singleplayer (integrated server) and on dedicated
  servers; clients do not need the mod.
- **New and existing worlds.**
- **Must be invisible to the player:** generated chunks have to match vanilla block
  for block. If Oxide cannot guarantee that for a chunk, vanilla generates it.

## Architecture (proposed)

```
Minecraft (Java, Fabric)                      oxide-worker (Rust, Damascus crates)
┌───────────────────────────────┐   stdin/    ┌──────────────────────────────────┐
│ vanilla chunk pipeline        │   stdout    │ handshake: seed, world preset,   │
│  STRUCTURE_STARTS  (vanilla)  │  framed     │   worldgen registry fingerprint  │
│  STRUCTURE_REFS    (vanilla)  │  messages   │                                  │
│  BIOMES            (vanilla)  │ ──────────► │ TERRAIN request (x, z, dim)      │
│  TERRAIN  ── mixin ───────────┼─────────────┤   noise + surface + carvers      │
│  FEATURES          (vanilla)  │ ◄────────── │ reply: sections, heightmaps,     │
│  LIGHT / SPAWN / FULL (vanilla)│             │   carving mask                   │
└───────────────────────────────┘             └──────────────────────────────────┘
```

In 26.3 the `TERRAIN` step (`ChunkStatusTasks.buildTerrain`) writes only to its own
chunk (`blockStateWriteRadius(0)`). Its inputs are the seed, the chunk position,
3×3 biomes and structure starts within 8 chunks. That makes it the cleanest step to
replace: one chunk goes over the process boundary per request, and vanilla's own
chunk storage stays the source of truth, so existing worlds need no special
handling.

Features (`FEATURES`, writes into a 3×3 area) stay vanilla for v1. Moving them
needs the 3×3 chunk state sent to the worker and diffs returned; that is a later
phase.

### Fallback to vanilla

Oxide uses vanilla's own `buildTerrain` for a chunk, or for the whole world, when:

- the worker fails to start, crashes, or times out;
- the dimension or world preset is not one Steel supports;
- the worldgen registries (noise settings, biomes, density functions, structures,
  structure sets, ...) differ from vanilla 26.3, e.g. a worldgen datapack;
- the chunk needs `Blender` data (borders pre-1.18 chunks);
- Steel's structure starts for the area differ from vanilla's;
- a known incompatible mod is present (e.g. C2ME).

## Repository layout (planned)

| Path | What |
|------|------|
| `mod/` | Fabric mod (Java, Gradle, Loom) |
| `worker/` | `oxide-worker` Rust binary, depends on Damascus via git |
| `protocol/` | IPC message spec shared by both sides |

## Roadmap

0. **Foundations.** Port Damascus to 26.3 (merged `TERRAIN` step). Confirm the
   `buildTerrain` inputs and outputs against the 26.3 source.
1. **Worker.** Headless generation-only Steel engine; terrain request/reply over
   stdin/stdout; handshake with registry fingerprint.
2. **Mod.** Spawn/supervise the worker per world; mixin on `buildTerrain`; copy the
   reply into the `ProtoChunk`; fallback rules above; bundle worker binaries for
   Windows, Linux and macOS.
3. **Parity and benchmarks.** Same seeds with and without Oxide, compared block for
   block after `FULL`; edge cases (old-world borders, worker crash mid-generation,
   flat/amplified, Nether, End). Benchmarks against vanilla and vanilla + C2ME.
4. **Later.** Features via 3×3 state transfer; speculative terrain ahead of the
   player.

## License

AGPL-3.0-or-later, matching SteelMC. See [LICENSE](LICENSE).

Oxide does not contain or redistribute Minecraft source code.
