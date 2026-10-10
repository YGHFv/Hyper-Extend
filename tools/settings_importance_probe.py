#!/usr/bin/env python3
"""Read-only exact DEX signature/access verification for the Settings importance adapter."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def uleb(data, offset):
    value, shift = 0, 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7


def members(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a standard DEX")
    sc, so, tc, to, pc, po, fc, fo, mc, mo, cc, co = struct.unpack_from("<12I", data, 0x38)
    strings = []
    for i in range(sc):
        offset = struct.unpack_from("<I", data, so + 4 * i)[0]
        _, offset = uleb(data, offset)
        strings.append(data[offset:data.index(b"\x00", offset)].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, to + 4 * i)[0]] for i in range(tc)]
    prototypes = []
    for i in range(pc):
        _, returns, params = struct.unpack_from("<III", data, po + 12 * i)
        parameters = []
        if params:
            count = struct.unpack_from("<I", data, params)[0]
            parameters = [types[struct.unpack_from("<H", data, params + 4 + 2 * j)[0]] for j in range(count)]
        prototypes.append("(" + "".join(parameters) + ")" + types[returns])
    fields = []
    for i in range(fc):
        owner, field_type, name = struct.unpack_from("<HHI", data, fo + 8 * i)
        fields.append(types[owner] + "->" + strings[name] + ":" + types[field_type])
    methods = []
    for i in range(mc):
        owner, proto, name = struct.unpack_from("<HHI", data, mo + 8 * i)
        methods.append(types[owner] + "->" + strings[name] + prototypes[proto])
    result = {}
    for i in range(cc):
        offset = struct.unpack_from("<I", data, co + 32 * i + 24)[0]
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for group, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, offset = uleb(data, offset)
                flags, offset = uleb(data, offset)
                index += delta
                if group >= 2:
                    _, offset = uleb(data, offset)
                signature = (methods if group >= 2 else fields)[index]
                result[signature] = flags
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("docs/notification-importance-settings-evidence.json"))
    parser.add_argument("--output", type=Path, default=Path(".workbuddy/tmp/importance-settings-refresh.json"))
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    if digest != manifest["host"]["sha256"]:
        raise ValueError("Different Settings APK; audit it before updating the manifest")
    defined = {}
    with zipfile.ZipFile(args.apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                for signature, flags in members(archive.read(name)).items():
                    if signature in defined:
                        raise ValueError("Duplicate DEX definition: " + signature)
                    defined[signature] = {"dex": name, "access": flags}
    verified = []
    for target in manifest["members"]:
        actual = defined.get(target["signature"])
        if actual != {"dex": target["dex"], "access": target["access"]}:
            raise ValueError(f"Signature/access mismatch: {target}: {actual}")
        verified.append(target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"apkSha256": digest, "verified": verified}, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(verified)} Settings DEX members and APK identity; not runtime acceptance.")


if __name__ == "__main__":
    main()
