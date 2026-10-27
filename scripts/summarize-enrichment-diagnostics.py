#!/usr/bin/env python3
"""Read owned measurement class files and HotSpot XML; never modifies measured artifacts."""
import argparse
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET


def code_lengths(file):
    data = file.read_bytes()
    offset = 0

    def read(count):
        nonlocal offset
        result = data[offset:offset + count]
        if len(result) != count:
            raise ValueError("Truncated class file")
        offset += count
        return result

    def u2():
        return struct.unpack(">H", read(2))[0]

    def u4():
        return struct.unpack(">I", read(4))[0]

    if u4() != 0xCAFEBABE:
        raise ValueError("Not a class file")
    read(4)
    count = u2()
    strings = {}
    index = 1
    while index < count:
        tag = read(1)[0]
        if tag == 1:
            # Only ASCII method/attribute names are used; other UTF constants can be opaque.
            strings[index] = read(u2()).decode("utf-8", errors="replace")
        elif tag in (5, 6):
            read(8)
            index += 1
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            read(4)
        elif tag in (7, 8, 16, 19, 20):
            read(2)
        elif tag == 15:
            read(3)
        else:
            raise ValueError(f"Unknown class pool tag {tag}")
        index += 1
    read(6)
    read(2 * u2())
    for _ in range(u2()):
        read(6)
        for _ in range(u2()):
            read(2)
            read(u4())
    methods = {}
    for _ in range(u2()):
        read(2)
        name, descriptor = strings[u2()], strings[u2()]
        for _ in range(u2()):
            attribute, size = strings[u2()], u4()
            body = read(size)
            if attribute == "Code":
                methods[name + descriptor] = struct.unpack(">I", body[4:8])[0]
    return methods


def summarize(directory):
    result = {}
    for mode in ("off", "core", "enrichment"):
        folder = directory / f"scoped-hot-{mode}-0-diagnostic"
        files = sorted((folder / "bytecode").glob("*EnrichmentMeasurementWorkload*.class"))
        bytecode = {file.name: code_lengths(file) for file in files}
        root = ET.parse(folder / "jit.xml").getroot()
        compilations = [dict(node.attrib) for node in root.iter("nmethod")
                        if "EnrichmentMeasurementWorkload scopedHot" in node.get("method", "")]
        inlining = []
        for task in root.iter("task"):
            methods = {node.get("id"): node for node in task.iter("method")}
            for parse in task.iter("parse"):
                last_call = None
                for node in parse:
                    if node.tag == "call":
                        last_call = methods.get(node.get("method"))
                    elif node.tag in ("inline_success", "inline_fail"):
                        if last_call is not None and last_call.get("name") == "scopedHot":
                            inlining.append(dict(outcome=node.tag, **node.attrib))
        result[mode] = dict(bytecode=bytecode, compilations=compilations, inlining=inlining)
    enriched = result["enrichment"]["bytecode"]
    original = [value["scopedHot(I)I"] for name, value in enriched.items() if "-original." in name]
    transformed = [value["scopedHot(I)I"] for name, value in enriched.items() if "-original." not in name]
    if len(original) != 1 or len(transformed) != 1 or transformed[0] <= original[0]:
        raise ValueError("Missing actual transformed hot-method bytecode evidence")
    if any(not value["inlining"] for value in result.values()):
        raise ValueError("Missing hot-method JIT inlining decisions")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.directory), indent=2))


if __name__ == "__main__":
    main()
