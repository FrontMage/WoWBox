#!/usr/bin/env python3
"""Verify an APK and its selected runtime inputs before a WoW Box release.

This is a static artifact check, not installation or game acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SIGNER = "dbcea8329cb812cc3237d1c923d10adbedefc59db602f4a3b955ba42696102be"
RELAY_MARKERS = ("cefproxy", "bnet-relay", "bnet_relay", "battlenet-relay")


def digest(stream):
    sha = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        sha.update(chunk)
    return sha.hexdigest()


def sdk_path(explicit):
    if explicit:
        return Path(explicit).expanduser()
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(name):
            return Path(os.environ[name]).expanduser()
    props = ROOT / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":"))
    raise ValueError("Android SDK not found; supply --sdk-dir or ANDROID_HOME")


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, check=False)
    if result.returncode:
        raise ValueError(f"{Path(args[0]).name} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def verify(args):
    apk = args.apk.resolve()
    if not apk.is_file():
        raise ValueError("APK does not exist")
    tools = sdk_path(args.sdk_dir) / "build-tools" / args.build_tools
    badging = run(str(tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package:
        raise ValueError("Unable to read APK package/version")
    actual_package, code, version = package.groups()
    if (actual_package, code, version) != ("com.winlator.llm", str(args.version_code), args.version_name):
        raise ValueError(f"Unexpected APK identity: {actual_package} / {code} / {version}")
    signatures = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signatures, re.M)
    if len(signers) != 1 or signers[0].lower() != EXPECTED_SIGNER:
        raise ValueError("APK signer does not match the published WoW Box certificate")

    manifest = json.loads((ROOT / "runtime-assets.json").read_text(encoding="utf-8"))
    if manifest.get("version") != args.release:
        raise ValueError("Runtime asset manifest release does not match")
    assets = manifest.get("files")
    if not isinstance(assets, list) or not assets:
        raise ValueError("Runtime asset manifest is empty")
    paths = set()
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP member names")
        if not any(name.startswith("lib/arm64-v8a/") and name.endswith(".so") for name in names):
            raise ValueError("APK does not contain ARM64 native libraries")
        for entry in assets:
            path = entry.get("path", "")
            prefix = "app/src/main/assets/"
            if not path.startswith(prefix):
                raise ValueError(f"Invalid runtime asset path: {path}")
            relative = path[len(prefix):]
            parts = PurePosixPath(relative).parts
            if not parts or any(part in (".", "..") for part in parts) or "\\" in relative:
                raise ValueError(f"Unsafe runtime asset path: {path}")
            name = "assets/" + relative
            if name in paths:
                raise ValueError(f"Duplicate runtime asset: {path}")
            paths.add(name)
            expected = str(entry.get("sha256", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError(f"Missing runtime asset hash: {path}")
            try:
                info = archive.getinfo(name)
                with archive.open(info) as stream:
                    actual = digest(stream)
            except KeyError as error:
                raise ValueError(f"APK is missing runtime asset: {path}") from error
            if info.file_size != entry.get("sizeBytes") or actual != expected:
                raise ValueError(f"Runtime asset mismatch: {path}")

        try:
            spec = json.loads(archive.read("assets/box_spec.json"))
        except (KeyError, json.JSONDecodeError) as error:
            raise ValueError("APK has no valid Box specification") from error
        layers = spec.get("layers")
        if not isinstance(layers, list) or not layers:
            raise ValueError("Box specification has no runtime layers")
        for layer in layers:
            source = layer.get("source", "")
            if any(marker in source.lower() for marker in RELAY_MARKERS):
                raise ValueError("Battle.net relay is selected by a runtime layer")
            if not source.startswith("asset://"):
                raise ValueError(f"Runtime layer is not an APK asset: {layer.get('type')}")
            name = "assets/" + source[len("asset://"):]
            checksum = str(layer.get("checksum", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{64}", checksum):
                raise ValueError(f"Invalid layer checksum: {layer.get('type')}")
            try:
                with archive.open(name) as stream:
                    if digest(stream) != checksum:
                        raise ValueError(f"Layer checksum mismatch: {layer.get('type')}")
            except KeyError as error:
                raise ValueError(f"Runtime layer is missing: {layer.get('type')}") from error
        relay_assets = [name for name in names if name.startswith("assets/") and
                        any(marker in name.lower() for marker in RELAY_MARKERS)]
        if relay_assets:
            raise ValueError("APK includes Battle.net relay-named assets")

    bundle_verified = False
    if args.runtime_bundle:
        bundle = manifest.get("bundle", {})
        expected = str(bundle.get("sha256", "")).lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("Runtime bundle SHA-256 is missing")
        if args.runtime_bundle.name != bundle.get("filename"):
            raise ValueError("Runtime bundle filename does not match the manifest")
        with args.runtime_bundle.open("rb") as stream:
            if digest(stream) != expected:
                raise ValueError("Runtime bundle checksum does not match")
        expected_files = {entry["path"]: entry for entry in assets}
        seen = set()
        with tarfile.open(args.runtime_bundle, "r:gz") as archive:
            for member in archive:
                name = member.name.removeprefix("./")
                if member.isdir():
                    continue
                if not member.isfile() or name not in expected_files or name in seen:
                    raise ValueError(f"Unexpected runtime bundle entry: {name}")
                seen.add(name)
                entry = expected_files[name]
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError(f"Cannot read runtime bundle entry: {name}")
                with source:
                    if member.size != entry.get("sizeBytes") or digest(source) != entry["sha256"].lower():
                        raise ValueError(f"Runtime bundle member mismatch: {name}")
        if seen != set(expected_files):
            raise ValueError("Runtime bundle is missing expected asset entries")
        bundle_verified = True
    with apk.open("rb") as stream:
        apk_sha256 = digest(stream)
    return {
        "package": actual_package,
        "versionCode": int(code),
        "versionName": version,
        "release": args.release,
        "apkSha256": apk_sha256,
        "signerSha256": signers[0].lower(),
        "runtimeAssetCount": len(assets),
        "runtimeLayerCount": len(layers),
        "runtimeBundleVerified": bundle_verified,
        "battleNetRelayAssetNameCheck": "passed",
        "scope": "static artifact checks only; runtime and game acceptance not covered",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--runtime-bundle", type=Path)
    parser.add_argument("--sdk-dir")
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--release", default="v0.2.0-preview")
    parser.add_argument("--version-code", type=int, default=18)
    parser.add_argument("--version-name", default="0.2.0-preview")
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args), ensure_ascii=False, indent=2))
    except (ValueError, OSError, zipfile.BadZipFile, tarfile.TarError) as error:
        print(f"Release check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
