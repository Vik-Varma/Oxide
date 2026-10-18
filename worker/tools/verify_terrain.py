#!/usr/bin/env python3
"""Checks oxide-worker's TERRAIN output against vanilla reference hashes.

Runs the worker binary through the real stdin/stdout protocol, requests chunks
listed in SteelMC's `steel-core/test_assets/chunk_stage_hashes.json` (extracted
from vanilla), and compares each chunk's block hash with the fixture's
`minecraft:terrain` hash. The hash is the one SteelMC's parity test uses: per
section, a single 0 byte if every block is air, otherwise every state id as a
big-endian u32 in (y, z, x) order.

It also checks that WORLD_SURFACE_WG matches the blocks (first Y above the
highest non-air block), and that post-processing offsets point at fluids.

Example:
    python3 -I tools/verify_terrain.py \\
        --worker target/release/oxide-worker \\
        --fixture ../../steelmc/steel-core/test_assets/chunk_stage_hashes.json \\
        --dimension minecraft:overworld --count 50
"""

import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile

PROTOCOL_VERSION = 2
AIR_BLOCKS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, fmt):
        values = struct.unpack_from(">" + fmt, self.data, self.pos)
        self.pos += struct.calcsize(">" + fmt)
        return values if len(values) > 1 else values[0]

    def string(self):
        length = self.take("H")
        text = self.data[self.pos:self.pos + length].decode("utf-8")
        self.pos += length
        return text


def send(stream, payload):
    stream.write(struct.pack(">I", len(payload)) + payload)
    stream.flush()


def receive(stream):
    header = stream.read(4)
    if len(header) < 4:
        raise RuntimeError("worker closed stdout")
    (length,) = struct.unpack(">I", header)
    return Reader(stream.read(length))


def encode_string(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def read_ready(reader):
    version, min_y, section_count, state_count = reader.take("IiHI")
    states = []
    for _ in range(state_count):
        block = reader.string()
        properties = {}
        for _ in range(reader.take("B")):
            name = reader.string()
            properties[name] = reader.string()
        states.append((block, properties))
    biomes = [reader.string() for _ in range(reader.take("H"))]
    return version, min_y, section_count, states, biomes


def read_sections(reader):
    section_count = reader.take("H")
    sections = []
    for _ in range(section_count):
        if reader.take("B") == 0:
            sections.append([reader.take("H")] * 4096)
            continue
        palette_len = reader.take("H")
        palette = [reader.take("H") for _ in range(palette_len)]
        fmt = "H" if palette_len > 256 else "B"
        indices = struct.unpack_from(">" + fmt * 4096, reader.data, reader.pos)
        reader.pos += struct.calcsize(">" + fmt * 4096)
        sections.append([palette[index] for index in indices])
    biomes = []
    for _ in range(section_count):
        palette = [reader.take("H") for _ in range(reader.take("B"))]
        biomes.append([palette[index] for index in reader.data[reader.pos:reader.pos + 64]])
        reader.pos += 64
    postprocessing = []
    for _ in range(section_count):
        count = reader.take("H")
        postprocessing.append([reader.take("H") for _ in range(count)])
    return sections, biomes, postprocessing


def read_terrain(reader):
    request_id = reader.take("I")
    sections, _, postprocessing = read_sections(reader)
    world_surface = [reader.take("i") for _ in range(256)]
    ocean_floor = [reader.take("i") for _ in range(256)]
    return request_id, sections, postprocessing, world_surface, ocean_floor


def read_full(reader):
    """Parses a Full reply and returns (request_id, summary) after checking its shape."""
    request_id = reader.take("I")
    sections, biomes, postprocessing = read_sections(reader)
    heightmaps = {}
    for _ in range(reader.take("B")):
        name = reader.string()
        heightmaps[name] = [reader.take("i") for _ in range(256)]
    light = []
    for _ in range(2):
        layer = {}
        for _ in range(reader.take("H")):
            index, present = reader.take("HB")
            layer[index] = reader.data[reader.pos:reader.pos + 2048] if present else None
            reader.pos += 2048 if present else 0
        light.append(layer)
    ticks = []
    for _ in range(2):
        ticks.append([(reader.string(), *reader.take("BhBb")) for _ in range(reader.take("I"))])
    nbt_lists = []
    for _ in range(2):
        entries = []
        for _ in range(reader.take("I")):
            length = reader.take("I")
            entries.append(read_nbt(reader.data[reader.pos:reader.pos + length]))
            reader.pos += length
        nbt_lists.append(entries)
    if reader.pos != len(reader.data):
        raise ValueError(f"{len(reader.data) - reader.pos} trailing bytes in Full")
    return request_id, {
        "sections": sections, "biomes": biomes, "postprocessing": postprocessing,
        "heightmaps": heightmaps, "block_light": light[0], "sky_light": light[1],
        "block_ticks": ticks[0], "fluid_ticks": ticks[1],
        "block_entities": nbt_lists[0], "entities": nbt_lists[1],
    }


def read_nbt(data):
    """Minimal reader for an uncompressed named NBT root; returns the root compound."""
    reader = Reader(data)

    def payload(tag):
        if tag == 1:
            return reader.take("b")
        if tag == 2:
            return reader.take("h")
        if tag == 3:
            return reader.take("i")
        if tag == 4:
            return reader.take("q")
        if tag == 5:
            return reader.take("f")
        if tag == 6:
            return reader.take("d")
        if tag in (7, 11, 12):
            count = reader.take("i")
            fmt = {7: "b", 11: "i", 12: "q"}[tag]
            values = struct.unpack_from(">" + fmt * count, reader.data, reader.pos)
            reader.pos += struct.calcsize(">" + fmt * count)
            return list(values)
        if tag == 8:
            return reader.string()
        if tag == 9:
            element, count = reader.take("Bi")
            return [payload(element) for _ in range(count)]
        if tag == 10:
            compound = {}
            while (child := reader.take("B")) != 0:
                name = reader.string()
                compound[name] = payload(child)
            return compound
        raise ValueError(f"unknown NBT tag {tag}")

    if reader.take("B") != 10:
        raise ValueError("NBT root is not a compound")
    reader.string()
    root = payload(10)
    if reader.pos != len(data):
        raise ValueError("trailing bytes after NBT root")
    return root


def block_hash(sections, air_ids):
    md5 = hashlib.md5()
    for blocks in sections:
        if all(state in air_ids for state in blocks):
            md5.update(b"\0")
        else:
            md5.update(struct.pack(">4096I", *blocks))
    return md5.hexdigest()


def world_surface_errors(sections, world_surface, air_ids, min_y):
    errors = 0
    for z in range(16):
        for x in range(16):
            expected = min_y
            for section_index in range(len(sections) - 1, -1, -1):
                column = [sections[section_index][(y << 8) | (z << 4) | x] for y in range(16)]
                top = next((y for y in range(15, -1, -1) if column[y] not in air_ids), None)
                if top is not None:
                    expected = min_y + section_index * 16 + top + 1
                    break
            if world_surface[x + z * 16] != expected:
                errors += 1
    return errors


def non_fluid_postprocessing(sections, postprocessing, states):
    count = 0
    for section_index, offsets in enumerate(postprocessing):
        for offset in offsets:
            x, y, z = offset & 15, (offset >> 4) & 15, (offset >> 8) & 15
            block, properties = states[sections[section_index][(y << 8) | (z << 4) | x]]
            is_fluid = block in ("minecraft:water", "minecraft:lava") or properties.get("waterlogged") == "true"
            if not is_fluid:
                count += 1
    return count


def full_problems(chunk, full, states, biome_names, section_count, min_y, air_ids):
    """Shape checks for a Full reply. The blocks are not compared with vanilla: vanilla's
    own feature placement depends on generation order, so only the in-server verify mode
    can check them."""
    problems = []
    sections = full["sections"]
    if len(sections) != section_count:
        problems.append(f"{len(sections)} sections, expected {section_count}")
    if any(biome >= len(biome_names) for section in full["biomes"] for biome in section):
        problems.append("biome id outside the Ready table")
    expected_maps = {"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"}
    if set(full["heightmaps"]) != expected_maps:
        problems.append(f"heightmaps {sorted(full['heightmaps'])}")
    elif world_surface_errors(sections, full["heightmaps"]["WORLD_SURFACE"], air_ids, min_y):
        problems.append("WORLD_SURFACE disagrees with the blocks")
    for name in ("block_light", "sky_light"):
        if any(index > section_count + 1 for index in full[name]):
            problems.append(f"{name} section index outside the padded range")
    for kind, ticks in (("block", full["block_ticks"]), ("fluid", full["fluid_ticks"])):
        for _, x, y, z, _ in ticks:
            if x > 15 or z > 15 or not min_y <= y < min_y + section_count * 16:
                problems.append(f"{kind} tick outside the chunk")
                break
    for entity in full["block_entities"]:
        x, y, z = entity.get("x"), entity.get("y"), entity.get("z")
        if "id" not in entity or x // 16 != chunk["x"] or z // 16 != chunk["z"]:
            problems.append(f"block entity {entity.get('id')} at {x} {y} {z} is outside the chunk")
            continue
        state = sections[(y - min_y) // 16][((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]
        if states[state][0] in AIR_BLOCKS:
            problems.append(f"block entity {entity['id']} at {x} {y} {z} sits on air")
    for entity in full["entities"]:
        if "id" not in entity or len(entity.get("Pos", [])) != 3 or len(entity.get("UUID", [])) != 4:
            problems.append(f"entity without id, Pos or UUID: {entity}")
    return problems


def report_full(chunks, results, states, biome_names, section_count, min_y, air_ids, dimension):
    failures = 0
    totals = {"block_entities": 0, "entities": 0, "block_ticks": 0, "fluid_ticks": 0,
              "block_light": 0, "sky_light": 0}
    entity_ids = {}
    for request_id, chunk in enumerate(chunks, start=1):
        result = results[request_id]
        position = f"({chunk['x']}, {chunk['z']})"
        if isinstance(result, tuple):
            print(f"{position}: worker error: {result[1]}")
            failures += 1
            continue
        for name in totals:
            totals[name] += len(result[name])
        for nbt in result["block_entities"] + result["entities"]:
            entity_ids[nbt.get("id")] = entity_ids.get(nbt.get("id"), 0) + 1
        problems = full_problems(chunk, result, states, biome_names, section_count, min_y, air_ids)
        if problems:
            failures += 1
            print(f"{position}: " + "; ".join(problems))
    print("totals: " + ", ".join(f"{name} {count}" for name, count in totals.items()))
    print("ids: " + ", ".join(f"{name} {count}" for name, count in sorted(entity_ids.items(), key=str)))
    print(f"{len(chunks) - failures}/{len(chunks)} full chunks well formed ({dimension})")
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--worker", required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--threads", type=int, default=0)
    parser.add_argument("--cache-dir", help="reuse this chunk cache instead of a fresh temporary one")
    parser.add_argument("--skip", type=int, default=0, help="skip this many fixture chunks first")
    parser.add_argument("--record", help="write each chunk's heightmaps and post-processing to this JSON file")
    parser.add_argument("--compare", help="check heightmaps and post-processing against a --record file")
    parser.add_argument("--full", action="store_true",
                        help="request whole chunks at LIGHT and check that each reply is well formed")
    args = parser.parse_args()

    with open(args.fixture) as file:
        fixture = json.load(file)
    chunks = [
        chunk for chunk in fixture["dimensions"][args.dimension]["chunks"]
        if "minecraft:terrain" in chunk["stages"]
    ][args.skip:args.skip + args.count]

    with tempfile.TemporaryDirectory() as temporary_dir:
        cache_dir = args.cache_dir or temporary_dir
        worker = subprocess.Popen([args.worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        send(worker.stdin, b"\x01" + struct.pack(">Iq", PROTOCOL_VERSION, fixture["seed"])
             + encode_string(args.dimension) + encode_string(cache_dir)
             + struct.pack(">H", args.threads))
        reader = receive(worker.stdout)
        message_type = reader.take("B")
        if message_type != 0x81:
            print(f"worker refused the session: {reader.take('I')} {reader.string()}")
            return 1
        _, min_y, section_count, states, biome_names = read_ready(reader)
        air_ids = {index for index, (block, _) in enumerate(states) if block in AIR_BLOCKS}

        request_type = b"\x04" if args.full else b"\x02"
        for request_id, chunk in enumerate(chunks, start=1):
            send(worker.stdin, request_type + struct.pack(">Iii", request_id, chunk["x"], chunk["z"]))

        results = {}
        while len(results) < len(chunks):
            reader = receive(worker.stdout)
            message_type = reader.take("B")
            if message_type == 0x83:
                request_id = reader.take("I")
                results[request_id] = ("error", reader.string())
                continue
            if message_type == 0x84:
                request_id, full = read_full(reader)
                results[request_id] = full
                continue
            request_id, sections, postprocessing, world_surface, ocean_floor = read_terrain(reader)
            results[request_id] = (sections, postprocessing, world_surface, ocean_floor)

        send(worker.stdin, b"\x03")
        worker.stdin.close()
        worker.wait(timeout=120)

    if args.full:
        return report_full(chunks, results, states, biome_names, section_count, min_y, air_ids, args.dimension)

    recorded = {}
    if args.compare:
        with open(args.compare) as file:
            recorded = json.load(file)
    to_record = {}

    failures = 0
    for request_id, chunk in enumerate(chunks, start=1):
        result = results[request_id]
        position = f"({chunk['x']}, {chunk['z']})"
        if result[0] == "error":
            print(f"{position}: worker error: {result[1]}")
            failures += 1
            continue
        sections, postprocessing, world_surface, ocean_floor = result
        key = f"{chunk['x']},{chunk['z']}"
        outputs = {"world_surface_wg": world_surface, "ocean_floor_wg": ocean_floor,
                   "postprocessing": [sorted(offsets) for offsets in postprocessing]}
        to_record[key] = outputs
        problems = []
        for name, value in recorded.get(key, {}).items():
            if outputs[name] != value:
                problems.append(f"{name} differs from the recorded run")
        if block_hash(sections, air_ids) != chunk["stages"]["minecraft:terrain"]:
            problems.append("block hash differs from vanilla")
        surface_errors = world_surface_errors(sections, world_surface, air_ids, min_y)
        if surface_errors:
            problems.append(f"{surface_errors} WORLD_SURFACE_WG columns disagree with the blocks")
        stray = non_fluid_postprocessing(sections, postprocessing, states)
        if stray:
            problems.append(f"{stray} post-processing offsets are not fluids")
        if problems:
            failures += 1
            print(f"{position}: " + "; ".join(problems))

    if args.record:
        with open(args.record, "w") as file:
            json.dump(to_record, file)
    compared = sum(1 for chunk in chunks if f"{chunk['x']},{chunk['z']}" in recorded)
    if args.compare:
        print(f"{compared} chunks compared with {args.compare}")
    print(f"{len(chunks) - failures}/{len(chunks)} chunks match ({args.dimension})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
