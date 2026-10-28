#!/usr/bin/env python3
"""Fetch and verify this release's public runtime bundle without GitHub credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ASSET_PREFIX = "app/src/main/assets/"


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_assets(manifest):
    items = manifest.get("files")
    if not isinstance(items, list) or not items:
        raise SystemExit("Runtime asset manifest is empty")
    result = {}
    for item in items:
        if not isinstance(item, dict):
            raise SystemExit("Invalid runtime asset manifest entry")
        name = item.get("path", "")
        sha = item.get("sha256", "")
        size = item.get("sizeBytes")
        mode = item.get("mode", 0o644)
        if not isinstance(name, str) or not name.startswith(ASSET_PREFIX) or "\\" in name:
            raise SystemExit("Invalid runtime asset path")
        components = name.split("/")
        if any(part in ("", ".", "..") for part in components) or name in result:
            raise SystemExit("Unsafe or duplicate runtime asset: " + name)
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise SystemExit("Invalid runtime asset checksum: " + name)
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            raise SystemExit("Invalid runtime asset size: " + name)
        if not isinstance(mode, int) or isinstance(mode, bool) or mode < 0 or mode > 0o777:
            raise SystemExit("Invalid runtime asset mode: " + name)
        result[name] = item
    return result


def checked_target(name):
    """Reject symlinks and non-directories before accessing an asset path."""
    target = ROOT / name
    current = ROOT
    for part in Path(name).parts[:-1]:
        current = current / part
        if current.is_symlink() or (current.exists() and not current.is_dir()):
            raise SystemExit("Unsafe runtime asset parent: " + name)
    if target.is_symlink() or (target.exists() and not target.is_file()):
        raise SystemExit("Unsafe runtime asset target: " + name)
    return target


def check_existing(expected):
    missing = set()
    for name, item in expected.items():
        target = checked_target(name)
        if not target.exists():
            missing.add(name)
        elif target.stat().st_size != item["sizeBytes"] or digest(target) != item["sha256"]:
            # A developer's existing file may be intentionally modified. Do not
            # overwrite it while bootstrapping the other, missing resources.
            raise SystemExit("Existing runtime asset mismatch; preserved: " + name)
    return missing


def verify_bundle(archive, expected, missing, stage_dir):
    """Verify every member before installing any file; stage only missing files."""
    seen = set()
    staged = {}
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            name = member.name.removeprefix("./")
            if member.isdir():
                continue
            if not member.isfile() or name not in expected or name in seen:
                raise SystemExit("Unexpected or duplicate runtime bundle entry: " + name)
            seen.add(name)
            item = expected[name]
            if member.size != item["sizeBytes"]:
                raise SystemExit("Asset size mismatch: " + name)
            source = tar.extractfile(member)
            if source is None:
                raise SystemExit("Cannot read runtime bundle entry: " + name)
            h = hashlib.sha256()
            staged_path = stage_dir / str(len(staged)) if name in missing else None
            try:
                with source:
                    if staged_path is None:
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            h.update(chunk)
                    else:
                        with staged_path.open("xb") as out:
                            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                                h.update(chunk)
                                out.write(chunk)
                            out.flush()
                            os.fsync(out.fileno())
                if h.hexdigest() != item["sha256"]:
                    raise SystemExit("Asset checksum mismatch: " + name)
                if staged_path is not None:
                    staged_path.chmod(item.get("mode", 0o644))
                    staged[name] = staged_path
            except BaseException:
                if staged_path is not None:
                    staged_path.unlink(missing_ok=True)
                raise
    if seen != set(expected):
        raise SystemExit("Incomplete runtime bundle: " + ", ".join(sorted(set(expected) - seen)))
    return staged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify local assets without downloading")
    parser.add_argument("--bundle", type=Path, help="Use an already downloaded release bundle")
    args = parser.parse_args()
    if args.verify and args.bundle:
        parser.error("--verify and --bundle are mutually exclusive")
    manifest = json.loads((ROOT / "runtime-assets.json").read_text(encoding="utf-8"))
    expected = expected_assets(manifest)
    missing = check_existing(expected)
    if not missing and not args.bundle:
        print(f"Verified {len(expected)} runtime assets.")
        return
    if args.verify:
        raise SystemExit("Missing runtime assets: " + ", ".join(sorted(missing)))
    bundle = manifest.get("bundle", {})
    bundle_sha = bundle.get("sha256", "")
    if not isinstance(bundle_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", bundle_sha):
        raise SystemExit("This source snapshot does not have a verified runtime bundle yet.")
    # Staging on the same filesystem as ROOT allows an exclusive hard-link
    # installation. Existing user files are never replaced by a race.
    with tempfile.TemporaryDirectory(prefix=".wowbox-assets-", dir=ROOT) as temp:
        stage_dir = Path(temp)
        if args.bundle:
            archive = args.bundle.resolve(strict=True)
            if not archive.is_file():
                raise SystemExit("Local runtime bundle is not a regular file")
        else:
            archive = stage_dir / "runtime-bundle.tar.gz"
            subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--output", str(archive), bundle["url"]], check=True)
        if digest(archive) != bundle_sha:
            raise SystemExit("Runtime bundle checksum mismatch")
        staged = verify_bundle(archive, expected, missing, stage_dir)
        for name in sorted(missing):
            target = checked_target(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target = checked_target(name)
            try:
                os.link(staged[name], target)
            except FileExistsError:
                # Another writer is allowed only if it produced the exact input.
                checked_target(name)
                if not target.is_file() or target.stat().st_size != expected[name]["sizeBytes"] or digest(target) != expected[name]["sha256"]:
                    raise SystemExit("Runtime asset appeared with a different hash; preserved: " + name)
        if check_existing(expected):
            raise SystemExit("Incomplete runtime asset installation")
    print(f"Verified {len(expected)} runtime assets.")


if __name__ == "__main__":
    main()
