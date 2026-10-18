# oxide-worker

Generates the output of vanilla's `TERRAIN` chunk step with SteelMC, for the Oxide
mod. The mod starts one worker per dimension and talks to it over stdin/stdout; see
[`../protocol/README.md`](../protocol/README.md).

It depends on the `oxide` branch of
[Vik-Varma/SteelMC](https://github.com/Vik-Varma/SteelMC/tree/oxide) (upstream SteelMC
plus two visibility changes), pinned to one commit in `Cargo.toml`. To pick up
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
