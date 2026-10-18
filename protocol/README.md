# Oxide worker protocol (version 1)

The mod starts one `oxide-worker` process per dimension and talks to it over the
process's stdin (mod → worker) and stdout (worker → mod). The worker logs to stderr.

## Framing

Every message is one frame:

| Field | Type | Notes |
|---|---|---|
| length | `u32` | Payload length in bytes, 1 to 64 MiB |
| payload | bytes | Starts with a `u8` message type |

All integers are **big-endian**, so Java's `DataInputStream` / `DataOutputStream`
read and write them directly. A `string` is a `u16` byte length followed by UTF-8.

## Session

1. The mod sends **Hello**.
2. The worker answers **Ready**, or **Error** with request id 0 and exits.
3. The mod sends any number of **TerrainRequest**s without waiting for replies.
   Replies can arrive in any order; match them by `request_id`.
4. The mod sends **Shutdown**, or closes stdin. The worker saves its chunk cache and
   exits.

## Mod → worker

### Hello (`0x01`)

| Field | Type | Notes |
|---|---|---|
| protocol_version | `u32` | Must be `1` |
| seed | `i64` | World seed |
| dimension | string | `minecraft:overworld`, `minecraft:the_nether` or `minecraft:the_end` |
| cache_dir | string | Directory for SteelMC's chunk cache, e.g. `<world>/oxide/<dimension>`. Safe to delete while no worker runs |
| threads | `u16` | Generation threads; `0` = half the machine's cores |

### TerrainRequest (`0x02`)

| Field | Type |
|---|---|
| request_id | `u32` |
| chunk_x | `i32` |
| chunk_z | `i32` |

### Shutdown (`0x03`)

No fields.

## Worker → mod

### Ready (`0x81`)

| Field | Type | Notes |
|---|---|---|
| protocol_version | `u32` | |
| min_y | `i32` | Lowest block Y of the dimension |
| section_count | `u16` | 16-block sections per chunk |
| state_count | `u32` | Number of block states that follow |
| states | `state_count` × state | Indexed by SteelMC state id, starting at 0 |

Each state:

| Field | Type |
|---|---|
| block | string, e.g. `minecraft:grass_block` |
| property_count | `u8` |
| properties | `property_count` × (name string, value string) |

The mod resolves each entry to a vanilla `BlockState` once and fails the session if
any entry does not exist in vanilla.

### Terrain (`0x82`)

Everything vanilla's `TERRAIN` step (`ChunkStatusTasks.buildTerrain`) leaves on the
proto chunk.

| Field | Type | Notes |
|---|---|---|
| request_id | `u32` | |
| section_count | `u16` | |
| sections | `section_count` × section | Bottom to top |
| postprocessing | `section_count` × list | Bottom to top |
| world_surface_wg | 256 × `i32` | `WORLD_SURFACE_WG`, first free Y per column |
| ocean_floor_wg | 256 × `i32` | `OCEAN_FLOOR_WG`, first free Y per column |

Heightmap columns are indexed `x + z * 16`.

A section is either:

- `u8 0`, then `u16` state id: every block is that state;
- `u8 1`, then `u16` palette length `n`, `n` × `u16` state ids, then 4096 palette
  indices, each a `u8` if `n <= 256`, otherwise a `u16`.

Block indices follow vanilla's order: `(y << 8) | (z << 4) | x`.

A post-processing list is a `u16` count followed by that many `u16` offsets packed
like vanilla's `ProtoChunk.packOffsetCoordinates`: `x | (y << 4) | (z << 8)`, relative
to the section.

### Error (`0x83`)

| Field | Type | Notes |
|---|---|---|
| request_id | `u32` | `0` means the whole session failed and the worker is exiting |
| message | string | |

The mod falls back to vanilla's own `buildTerrain` for the failed request.
