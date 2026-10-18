# oxide-worker

Generates the output of vanilla's `TERRAIN` chunk step with SteelMC, for the Oxide
mod. The mod starts one worker per dimension and talks to it over stdin/stdout; see
[`../protocol/README.md`](../protocol/README.md).

It depends on the `oxide` branch of
[Vik-Varma/SteelMC](https://github.com/Vik-Varma/SteelMC/tree/oxide), pinned to one commit
in `Cargo.toml`. That branch is upstream SteelMC plus:

- two visibility changes the worker needs (`Chunk::generation_heightmaps`,
  `ChunkMap::advance_scheduling`);
- vanilla parity fixes, worth sending upstream, found with the mod's verify mode:
  - fluids placed by surface rules are marked for post-processing, as vanilla's
    `MaterialSystem` does. Without it, lava from the Nether's surface rules never got
    its post-processing fluid tick;
  - noise-fill fluids are marked in vanilla's `doFill` order (z, x, then y from the
    top). Post-processing schedules fluid ticks in list order;
- a build fix: `steel-worldgen` depends on `steel-utils` at build time again, so its
  build script cannot run before the Minecraft data it reads has been downloaded. To pick up
upstream changes, bump the `rev` to the branch's latest commit and re-run the
checks below.

## Build

Uses the same pinned nightly toolchain as SteelMC (`rust-toolchain.toml`). The first
build downloads the Minecraft server jar to extract data, so it needs network access.

```sh
cargo build --release
```

## Check against vanilla

`tools/verify_terrain.py` runs the release binary through the real protocol and
compares each chunk's blocks with the vanilla reference hashes SteelMC ships in
`steel-core/test_assets/chunk_stage_hashes.json`:

```sh
python3 -I tools/verify_terrain.py \
    --worker target/release/oxide-worker \
    --fixture <SteelMC checkout>/steel-core/test_assets/chunk_stage_hashes.json \
    --dimension minecraft:overworld --count 50
```
