#!/usr/bin/env python3
"""Create a full public v0.1.0 Wine tree fingerprint for safe upgrades."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import subprocess
import tarfile

WINE_SHA = "9ac94fff85c5156e0179e1e11508b1cbb569d4f974bca8838df319327c6f291d"
BRIDGE_SHA = "437d68591aace9cd599e7e2653abe0333fa7726df1d65d20fc2ffa33a2beff4d"


def sha_file(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def valid_path(name):
    return (bool(name) and not name.startswith("/") and "\\" not in name and
            ":" not in name and all(part not in ("", ".", "..") for part in name.split("/")))


def bridge_hashes(path):
    decoder = subprocess.Popen(["zstd", "-q", "-dc", str(path)], stdout=subprocess.PIPE)
    result = {}
    try:
        with tarfile.open(fileobj=decoder.stdout, mode="r|") as archive:
            for item in archive:
                name = item.name.removeprefix("./")
                if item.isfile() and name in ("xinput1_3.dll", "xinput1_4.dll"):
                    result[name] = hashlib.sha256(archive.extractfile(item).read()).hexdigest()
        if decoder.wait() != 0 or set(result) != {"xinput1_3.dll", "xinput1_4.dll"}:
            raise RuntimeError("Unable to audit the public input bridge")
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine", type=Path, required=True)
    parser.add_argument("--input-bridge", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sha_file(args.wine) != WINE_SHA or sha_file(args.input_bridge) != BRIDGE_SHA:
        raise SystemExit("Public v0.1 Wine or input-bridge checksum mismatch")
    bridge = bridge_hashes(args.input_bridge)
    decoder = subprocess.Popen(["xz", "-dc", str(args.wine)], stdout=subprocess.PIPE)
    entries = {}
    try:
        with tarfile.open(fileobj=decoder.stdout, mode="r|") as archive:
            for item in archive:
                path = item.name.removeprefix("./").rstrip("/")
                if not path:
                    continue
                if not valid_path(path) or path in entries:
                    raise ValueError(f"Unsafe or duplicated Wine archive path: {path}")
                if item.isdir():
                    entry = {"path": path, "type": "dir"}
                elif item.issym():
                    target = item.linkname
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
                    if target.startswith("/") or resolved == ".." or resolved.startswith("../"):
                        raise ValueError(f"Wine symlink escapes runtime: {path}")
                    entry = {"path": path, "type": "symlink", "linkTarget": target}
                elif item.isfile():
                    sha = hashlib.sha256()
                    source = archive.extractfile(item)
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        sha.update(chunk)
                    value = sha.hexdigest()
                    entry = {"path": path, "type": "file", "size": item.size, "sha256": value}
                    prefix = "lib/wine/aarch64-windows/"
                    if path.startswith(prefix) and path[len(prefix):] in bridge:
                        replacement = bridge[path[len(prefix):]]
                        if replacement != value:
                            entry["allowedSha256"] = [value, replacement]
                else:
                    raise ValueError(f"Unsupported Wine archive entry type: {path} {item.type!r}")
                entries[path] = entry
        if decoder.wait() != 0:
            raise RuntimeError("Unable to decompress public Wine archive")
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()
    if "bin/wine" not in entries:
        entries["bin/wine"] = {"path": "bin/wine", "type": "symlink", "linkTarget": "../lib/wine/aarch64-unix/wine"}
    if "bin/wine64" not in entries:
        entries["bin/wine64"] = {"path": "bin/wine64", "type": "symlink", "linkTarget": "wine"}
    for name in bridge:
        path = "lib/wine/aarch64-windows/" + name
        if path not in entries or entries[path]["type"] != "file":
            raise ValueError(f"Public Wine bridge destination missing: {path}")
    document = {
        "schemaVersion": 1,
        "runtimeArchiveSha256": WINE_SHA,
        "inputBridgeArchiveSha256": BRIDGE_SHA,
        "entries": [entries[name] for name in sorted(entries)],
    }
    raw = (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    print(json.dumps({"entries": len(entries), "bridge": bridge, "sha256": hashlib.sha256(raw).hexdigest()}))


if __name__ == "__main__":
    main()
