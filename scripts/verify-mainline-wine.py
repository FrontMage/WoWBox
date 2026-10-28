#!/usr/bin/env python3
"""Independently compare a repackaged Wine archive against its member receipt."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
    archive_hash = hashlib.sha256()
    with args.archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            archive_hash.update(chunk)
    if archive_hash.hexdigest() != receipt["outputSha256"]:
        raise SystemExit("Wine archive SHA differs from sanitization receipt")
    expected = {entry["path"]: entry for entry in receipt["members"]}
    if len(expected) != len(receipt["members"]):
        raise SystemExit("Duplicate Wine member path in receipt")
    decoder = subprocess.Popen(["xz", "-dc", str(args.archive)], stdout=subprocess.PIPE)
    seen = set()
    checked = 0
    fonts = []
    try:
        with tarfile.open(fileobj=decoder.stdout, mode="r|") as archive:
            for item in archive:
                path = item.name.removeprefix("./")
                if path not in expected or path in seen:
                    raise ValueError(f"Unexpected or duplicate archive member: {path}")
                seen.add(path)
                row = expected[path]
                if item.type.decode("latin1") != row["type"]:
                    raise ValueError(f"Member type differs: {path}")
                if item.isfile():
                    sha = hashlib.sha256()
                    source = archive.extractfile(item)
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        sha.update(chunk)
                    if sha.hexdigest() != row["outputSha256"]:
                        raise ValueError(f"Member bytes differ: {path}")
                    if path != "share/wine/wine.inf" and item.size != row["size"]:
                        raise ValueError(f"Member size differs: {path}")
                    checked += 1
                    if path.lower().endswith((".ttf", ".ttc", ".otf", ".fon", ".pcf", ".bdf")):
                        if not path.startswith("share/wine/fonts/"):
                            raise ValueError(f"Unexpected Wine font outside Wine source tree: {path}")
                        fonts.append(path)
                elif item.linkname != row.get("linkname", ""):
                    raise ValueError(f"Member link differs: {path}")
        if decoder.wait() != 0:
            raise RuntimeError("Cannot decompress Wine archive")
        if seen != set(expected):
            raise ValueError(f"Missing Wine archive members: {set(expected) - seen}")
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()
    result = {
        "schemaVersion": 1,
        "archiveSha256": archive_hash.hexdigest(),
        "verifiedMembers": len(seen),
        "verifiedRegularFiles": checked,
        "onlySourceChange": receipt["onlyModifiedMembers"],
        "fontMembers": fonts,
        "pass": True,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pass": True, "members": len(seen), "regularFiles": checked, "fonts": len(fonts)}))


if __name__ == "__main__":
    main()
