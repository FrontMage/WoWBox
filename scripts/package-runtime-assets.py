#!/usr/bin/env python3
"""Create the v0.2 public runtime bundle and update its checked manifest."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OLD_WINE = "app/src/main/assets/wine-runtime-wowbox-openfonts-9ac94fff.txz"
OLD_FEX = "app/src/main/assets/fexcore/fexcore-2508-frontmage-bnet-99d69a-native-mock-jemalloc-5f1dbc8d.tzst"
OLD_INPUT_BRIDGE = "app/src/main/assets/winhandler/winhandler-xinput-wow-filtered-arm64x-437d6859.tzst"
NEW_FILES = (
    "app/src/main/assets/wine-runtime-wowbox-mainline-openfonts-086c3fe6.txz",
    "app/src/main/assets/fexcore/fexcore-2508-wowbox-mainline-7519b960-2beba31f.tzst",
    "app/src/main/assets/wine_prefix_delta/wowbox-v010-to-mainline.json",
    "app/src/main/assets/wine_runtime_baseline/wowbox-v010-tree.json",
    "app/src/main/assets/winhandler/winhandler-xinput-wow-filtered-arm64x-149a9ca4.tzst",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest_path = ROOT / "runtime-assets.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # Accept both the original v0.1 manifest and our own prior v0.2 output.
    paths = {row["path"] for row in manifest["files"]} - {OLD_WINE, OLD_FEX, OLD_INPUT_BRIDGE}
    paths.update(NEW_FILES)
    rows = []
    for name in sorted(paths):
        path = ROOT / name
        if not name.startswith("app/src/main/assets/") or not path.is_file() or path.is_symlink():
            raise SystemExit(f"Missing or unsafe public asset: {name}")
        rows.append({"path": name, "sha256": sha256(path), "sizeBytes": path.stat().st_size, "mode": 0o644})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_name(args.output.name + ".tmp")
    with temp.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=6) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                for row in rows:
                    path = ROOT / row["path"]
                    info = tarfile.TarInfo(row["path"])
                    info.size = row["sizeBytes"]
                    info.mode = row["mode"]
                    info.mtime = 0
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    with path.open("rb") as source:
                        archive.addfile(info, source)
    temp.replace(args.output)
    manifest = {
        "schemaVersion": 1,
        "version": "v0.2.0-preview",
        "bundle": {
            "filename": "WoWBox-v0.2.0-preview-runtime-assets.tar.gz",
            "url": "https://github.com/FrontMage/WoWBox/releases/download/v0.2.0-preview/WoWBox-v0.2.0-preview-runtime-assets.tar.gz",
            "sha256": sha256(args.output),
        },
        "files": rows,
    }
    temp_manifest = manifest_path.with_name(manifest_path.name + ".tmp")
    temp_manifest.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temp_manifest.replace(manifest_path)
    print(json.dumps({"files": len(rows), "bytes": args.output.stat().st_size, "sha256": manifest["bundle"]["sha256"]}))


if __name__ == "__main__":
    main()
