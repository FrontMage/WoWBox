#!/usr/bin/env python3
"""Create the v0.3 public runtime bundle and update its checked manifest."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OLD_WINE = "app/src/main/assets/wine-runtime-wowbox-openfonts-9ac94fff.txz"
PREVIOUS_WINE = "app/src/main/assets/wine-runtime-wowbox-mainline-openfonts-56f206c4.txz"
OLD_FEX = "app/src/main/assets/fexcore/fexcore-2508-frontmage-bnet-99d69a-native-mock-jemalloc-5f1dbc8d.tzst"
OLD_INPUT_BRIDGE = "app/src/main/assets/winhandler/winhandler-xinput-wow-filtered-arm64x-437d6859.tzst"
NEW_FILES = (
    "app/src/main/assets/wine-runtime-wowbox-rwoa-openfonts-6d87ba31.txz",
    "app/src/main/assets/fexcore/fexcore-2508-wowbox-mainline-7519b960-2beba31f.tzst",
    "app/src/main/assets/wine_prefix_delta/wowbox-v010-to-mainline.json",
    "app/src/main/assets/wine_prefix_delta/wowbox-v010-to-rwoa.json",
    "app/src/main/assets/wine_runtime_baseline/wowbox-v010-tree.json",
    "app/src/main/assets/winhandler/winhandler-xinput-wow-filtered-arm64x-149a9ca4.tzst",
    "app/src/main/assets/graphics_driver/adrenotools-stevenmxz-turnip-gen8-v39-ad2b2d54.tzst",
    "app/src/main/assets/licenses/GameNative-GPL-3.0.txt",
)
V39_ASSET = "app/src/main/assets/graphics_driver/adrenotools-stevenmxz-turnip-gen8-v39-ad2b2d54.tzst"
V39_SHA256 = "ad2b2d546e77061d68b35164a0dd092ae5bb72d71b931e5a053eae9053ccdf4e"
GAMENATIVE_LICENSE_ASSET = "app/src/main/assets/licenses/GameNative-GPL-3.0.txt"
GAMENATIVE_LICENSE_SHA256 = "c8264e42ef8f1b8f6e9d7086bbf778472e2bc636018437d5821f6ee76f411ed1"
V39_UPSTREAM_URL = "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v39/Turnip_Gen8_V39.zip"
V39_UPSTREAM_ZIP_SHA256 = "572e415c11b636ea2a4c4968c60a9b24e459302549c8ea38e814dbd07e40654d"
V39_UPSTREAM_SO_SHA256 = "398b838b5d0b82b46ba2a209f8c2bd47bf3245c1059d5eed950b1db342b5cb39"


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
    if args.output.name != "WoWBox-v0.3.0-runtime-assets.tar.gz":
        raise SystemExit("Output filename must match the v0.3.0 release manifest")
    manifest_path = ROOT / "runtime-assets.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # Preserve release resources from v0.2 and add the pinned v0.3 candidate.
    paths = {row["path"] for row in manifest["files"]} - {OLD_WINE, PREVIOUS_WINE, OLD_FEX, OLD_INPUT_BRIDGE}
    paths.update(NEW_FILES)
    rows = []
    for name in sorted(paths):
        path = ROOT / name
        if not name.startswith("app/src/main/assets/") or not path.is_file() or path.is_symlink():
            raise SystemExit(f"Missing or unsafe public asset: {name}")
        checksum = sha256(path)
        if name == V39_ASSET and checksum != V39_SHA256:
            raise SystemExit("V39 archive differs from the pinned upstream-derived asset")
        if name == GAMENATIVE_LICENSE_ASSET and checksum != GAMENATIVE_LICENSE_SHA256:
            raise SystemExit("GameNative GPL license differs from its pinned upstream copy")
        row = {"path": name, "sha256": checksum, "sizeBytes": path.stat().st_size, "mode": 0o644}
        if name == V39_ASSET:
            row.update({"upstreamUrl": V39_UPSTREAM_URL, "upstreamZipSha256": V39_UPSTREAM_ZIP_SHA256,
                        "upstreamLibrarySha256": V39_UPSTREAM_SO_SHA256,
                        "embeddedMesaVersion": "27.0.0-devel (git-2648b2059f)",
                        "embeddedMesaBaseCommit": "2648b2059f1a412d64a8700dbbb75bbb4a768b0e",
                        "provenanceNote": "ZIP contents repacked without modifying meta.json or driver ELF; exact corresponding Mesa source and patch set not independently verified"})
        rows.append(row)
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
        "version": "v0.3.0",
        "bundle": {
            "filename": "WoWBox-v0.3.0-runtime-assets.tar.gz",
            "url": "https://github.com/FrontMage/WoWBox/releases/download/v0.3.0/WoWBox-v0.3.0-runtime-assets.tar.gz",
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
