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

PROTOCOL_VERSION = 1
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
    return version, min_y, section_count, states


def read_terrain(reader):
    request_id, section_count = reader.take("IH")
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
    postprocessing = []
    for _ in range(section_count):
        count = reader.take("H")
        postprocessing.append([reader.take("H") for _ in range(count)])
    world_surface = [reader.take("i") for _ in range(256)]
    ocean_floor = [reader.take("i") for _ in range(256)]
    return request_id, sections, postprocessing, world_surface, ocean_floor


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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--worker", required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--threads", type=int, default=0)
    args = parser.parse_args()

    with open(args.fixture) as file:
        fixture = json.load(file)
    chunks = [
        chunk for chunk in fixture["dimensions"][args.dimension]["chunks"]
        if "minecraft:terrain" in chunk["stages"]
    ][:args.count]

    with tempfile.TemporaryDirectory() as cache_dir:
        worker = subprocess.Popen([args.worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        send(worker.stdin, b"\x01" + struct.pack(">Iq", PROTOCOL_VERSION, fixture["seed"])
             + encode_string(args.dimension) + encode_string(cache_dir)
             + struct.pack(">H", args.threads))
        reader = receive(worker.stdout)
        message_type = reader.take("B")
        if message_type != 0x81:
            print(f"worker refused the session: {reader.take('I')} {reader.string()}")
            return 1
        _, min_y, _, states = read_ready(reader)
        air_ids = {index for index, (block, _) in enumerate(states) if block in AIR_BLOCKS}

        for request_id, chunk in enumerate(chunks, start=1):
            send(worker.stdin, b"\x02" + struct.pack(">Iii", request_id, chunk["x"], chunk["z"]))

        results = {}
        while len(results) < len(chunks):
            reader = receive(worker.stdout)
            message_type = reader.take("B")
            if message_type == 0x83:
                request_id = reader.take("I")
                results[request_id] = ("error", reader.string())
                continue
            request_id, sections, postprocessing, world_surface, _ = read_terrain(reader)
            results[request_id] = (sections, postprocessing, world_surface)

        send(worker.stdin, b"\x03")
        worker.stdin.close()
        worker.wait(timeout=120)

    failures = 0
    for request_id, chunk in enumerate(chunks, start=1):
        result = results[request_id]
        position = f"({chunk['x']}, {chunk['z']})"
        if result[0] == "error":
            print(f"{position}: worker error: {result[1]}")
            failures += 1
            continue
        sections, postprocessing, world_surface = result
        problems = []
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

    print(f"{len(chunks) - failures}/{len(chunks)} chunks match ({args.dimension})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
