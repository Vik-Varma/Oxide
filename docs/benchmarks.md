# Benchmarks

## Terrain step only, with C2ME and Moonrise (2026-10-18)

Dev server (`tools/server_parity.py run`), seed 13579, force-loading a 25×25 chunk
square in the overworld, Nether and End (1,875 chunks), timed from the first
`forceload` until every chunk is loaded. Two runs per setup, interleaved. 4-core cloud
container; the worker uses 2 threads. No setup fell back to vanilla for any chunk.

| Setup | Run 1 | Run 2 | Average |
|---|---|---|---|
| Vanilla | 35.7 s | 35.7 s | 35.7 s |
| Oxide | 32.5 s | 29.4 s | 31.0 s |
| Oxide, no prefetch | 31.3 s | 30.0 s | 30.7 s |
| C2ME 0.4.2-alpha.0.106 | 33.0 s | 33.8 s | 33.4 s |
| C2ME + Oxide | 29.2 s | 29.6 s | 29.4 s |
| C2ME + Oxide, no prefetch | 31.1 s | 32.5 s | 31.8 s |
| Moonrise 1.2.0 | 48.7 s | 51.5 s | 50.1 s |
| Moonrise + Oxide | 29.5 s | 32.6 s | 31.1 s |
| Moonrise + Oxide, no prefetch | 94.5 s | 90.8 s | 92.7 s |

- Oxide alone, with C2ME and with Moonrise end up within about 1.5 s of each other,
  which is within the run-to-run spread. None is clearly fastest here.
- Prefetching makes no measurable difference with vanilla's or C2ME's chunk system and
  is what makes Oxide usable with Moonrise, whose generation threads block on each
  terrain request.
- The gain over vanilla is modest because Oxide replaces only the `TERRAIN` step; the
  structure, biome, feature and light steps still run in vanilla, and the worker
  shares the 4 cores with the server.
- Progress is polled over RCON once a second, so differences of a second or two are
  noise.

## Time per generation step (2026-10-18)

Same load (1,875 chunks, three dimensions), one run each, with
`tools/server_parity.py run --step-timing`. Each figure is the time from a step's start
until its result is ready, including queueing, averaged per chunk. Steps run in
parallel, so these are latencies, not CPU shares.

| Step | Vanilla (33.0 s run) | Oxide (31.0 s run) |
|---|---|---|
| structure_starts | 0.15 ms | 0.22 ms |
| structure_references | 0.12 ms | 0.70 ms |
| biomes | 2.61 ms | 3.89 ms |
| terrain | 24.19 ms | 88.05 ms (waiting on the worker's 2 threads) |
| features | 2.57 ms | 3.99 ms |
| initialize_light | 51.20 ms | 80.23 ms |
| light | 34.80 ms | 35.06 ms |
| spawn | 0.25 ms | 0.31 ms |
| full | 31.12 ms | 14.39 ms |

- Light is the longest wait: vanilla's light engine runs on a single thread, so chunks
  queue for it. Making terrain faster barely moves the total while light stays.
- Features are cheap in this test.
- With 2 threads, the worker's queue is what Oxide's terrain step waits on.
