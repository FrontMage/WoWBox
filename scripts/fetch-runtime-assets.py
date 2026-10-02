#!/usr/bin/env python3
"""Fetch and verify this release's public runtime bundle without GitHub credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify local assets without downloading")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "runtime-assets.json").read_text())
    required = manifest["files"]
    missing = [x for x in required if not (ROOT / x["path"]).is_file() or digest(ROOT / x["path"]) != x["sha256"]]
    if not missing:
        print(f"Verified {len(required)} runtime assets.")
        return
    if args.verify:
        raise SystemExit("Missing or mismatched assets: " + ", ".join(x["path"] for x in missing))
    bundle = manifest["bundle"]
    if not bundle.get("sha256"):
        raise SystemExit("This source snapshot does not have a published runtime bundle yet.")
    with tempfile.TemporaryDirectory(prefix="wowbox-assets-") as temp:
        archive = Path(temp) / bundle["filename"]
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--output", str(archive), bundle["url"]], check=True)
        if digest(archive) != bundle["sha256"]:
            raise SystemExit("Runtime bundle checksum mismatch")
        expected = {x["path"]: x for x in required}
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                name = member.name.removeprefix("./")
                if member.isdir():
                    continue
                if not member.isfile() or name not in expected:
                    raise SystemExit("Unexpected runtime bundle entry: " + name)
                target = ROOT / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as out:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        out.write(chunk)
                    staged = Path(out.name)
                try:
                    if digest(staged) != expected[name]["sha256"]:
                        raise SystemExit("Asset checksum mismatch: " + name)
                    staged.chmod(expected[name].get("mode", 0o644))
                    os.replace(staged, target)
                finally:
                    staged.unlink(missing_ok=True)
        for item in required:
            if not (ROOT / item["path"]).is_file() or digest(ROOT / item["path"]) != item["sha256"]:
                raise SystemExit("Incomplete runtime bundle: " + item["path"])
    print(f"Verified {len(required)} runtime assets.")

if __name__ == "__main__":
    main()
