#!/usr/bin/env python3
"""Derive the v0.1.0-to-mainline Wine prefix delta from public Wine archives.

No device prefix, account, overlay or session data is used. Existing destinations
must match a declared baseline; new destinations are represented as additions.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import struct
import subprocess
import tarfile

OLD_SHA = "9ac94fff85c5156e0179e1e11508b1cbb569d4f974bca8838df319327c6f291d"
NEW_SHA = "086c3fe69c60493c2a43c9625a3b4d755bff2d6ebc3f44e02a0c85f49cc6efd6"
OLD_PREFIX_SHA = "350c6f287ef2008e62439a00f67affc0ef650a7ec2c2b7e8d0bea89e47c2f05a"
RUNTIME_ID = "proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff"
WINEBOOT_OPTIONAL_NAMES = frozenset({
    "aspnet_regiis.exe", "ngen.exe", "fusion.dll", "diasymreader.dll", "mscorwks.dll",
    "regsvcs.exe", "regasm.exe", "servicemodelreg.exe", "presentationfontcache.exe", "comctl32_v6.dll",
})


def verified_wineboot_optional_paths(archive):
    """Read the already-authenticated candidate Wine INF; never infer from device state."""
    with tarfile.open(archive, "r:xz") as source:
        members = [m for m in source if m.name.removeprefix("./") == "share/wine/wine.inf"]
        if len(members) != 1 or not members[0].isfile():
            raise ValueError("Candidate Wine archive has no unique wine.inf")
        text = source.extractfile(members[0]).read().decode("utf-8-sig")
    sections, current = {}, None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("[") and line.endswith("]"):
            current = line[1:-1].lower()
        elif current in ("fakedlls", "fakedllswow64"):
            match = re.fullmatch(r"11,,([A-Za-z0-9_.+-]+),-", line, re.IGNORECASE)
            if match:
                sections.setdefault(current, set()).add(match.group(1).lower())
    for section in ("fakedlls", "fakedllswow64"):
        if not WINEBOOT_OPTIONAL_NAMES.issubset(sections.get(section, set())):
            raise ValueError(f"Candidate Wine INF does not authorize expected removals in {section}")
    return {f"{arch}/{name}" for arch in ("system32", "syswow64") for name in WINEBOOT_OPTIONAL_NAMES}


def file_hash(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def safe_path(name):
    if not name or name.startswith("/") or "\\" in name or ":" in name:
        return False
    parts = PurePosixPath(name).parts
    return all(part not in (".", "..", "") for part in parts)


def inventory(archive):
    files, links, seen = {}, {}, set()
    with tarfile.open(archive, "r:xz") as source:
        for item in source:
            name = item.name.removeprefix("./").rstrip("/")
            if not name:
                continue
            if not safe_path(name) or name in seen:
                raise ValueError(f"Unsafe or duplicated archive member: {name}")
            seen.add(name)
            parts = name.split("/")
            if len(parts) != 4 or parts[:2] != ["lib", "wine"] or parts[2] not in (
                    "aarch64-windows", "i386-windows", "x86_64-windows"):
                continue
            if item.issym() or item.islnk():
                target = posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname)
                    if item.issym() else item.linkname.removeprefix("./"))
                if not safe_path(target) or not target.startswith("lib/wine/"):
                    raise ValueError(f"Unsafe Wine builtin link: {name}")
                links[name] = target
            elif item.isfile():
                raw = source.extractfile(item).read()
                offset = struct.unpack_from("<I", raw, 60)[0] if raw[:2] == b"MZ" and len(raw) >= 64 else None
                machine = (struct.unpack_from("<H", raw, offset + 4)[0]
                           if offset is not None and raw[offset:offset + 4] == b"PE\0\0" and len(raw) >= offset + 6
                           else None)
                files[name] = {"sha256": hashlib.sha256(raw).hexdigest(), "machine": machine, "regular": True}
    def resolve(name, seen_links=()):
        if name in files:
            return files[name]
        if name not in links or name in seen_links:
            raise ValueError(f"Unresolved or cyclic Wine builtin link: {name}")
        value = dict(resolve(links[name], (*seen_links, name)))
        value["regular"] = False
        files[name] = value
        return value
    for name in links:
        resolve(name)
    if not all(f"lib/wine/{arch}/ntdll.dll" in files for arch in ("aarch64-windows", "i386-windows")):
        raise ValueError("Wine archive is missing native or i386 ntdll")
    return files


def prefix_inventory(archive):
    """Hash only Windows builtins in the exact public v0.1 prefix template."""
    prefix = ".wine/drive_c/windows/"
    result = {}
    decoder = subprocess.Popen(["zstd", "-q", "-dc", str(archive)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=decoder.stdout, mode="r|") as tar:
            for item in tar:
                path = item.name.removeprefix("./")
                if not path.startswith(prefix) or not item.isfile():
                    continue
                relative = path[len(prefix):]
                if not safe_path(relative):
                    raise ValueError(f"Unsafe prefix template member: {path}")
                if not relative.startswith(("system32/", "sysarm32/", "syswow64/")):
                    continue
                key = relative.lower()
                if key in result:
                    raise ValueError(f"Duplicate prefix template member: {relative}")
                sha = hashlib.sha256()
                source = tar.extractfile(item)
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    sha.update(chunk)
                result[key] = sha.hexdigest()
        if decoder.wait() != 0:
            raise RuntimeError("Cannot decompress public prefix template")
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()
    return result


def managed_elsewhere(name):
    name = name.lower()
    return (name == "dxgi.dll" or name.startswith("d3d") and name.endswith(".dll") or
            name.startswith("xinput") and name.endswith(".dll"))


def mappings(wine, old_wine):
    mapped = {}
    for path, identity in sorted(wine.items()):
        _, _, arch, name = path.split("/")
        if arch == "x86_64-windows" or managed_elsewhere(name) or name in ("tabtip.exe", "icu.dll"):
            continue
        machine = identity["machine"]
        if arch == "aarch64-windows":
            previous = old_wine.get(path, {}).get("machine")
            if machine is not None and machine not in (0xaa64, 0xa641, 0xa64e) and not (
                    machine == 0x8664 and (name == "winedevice-x64.exe" or previous == 0x8664)):
                raise ValueError(f"Unexpected native PE architecture: {path}")
            destinations = ["system32", "sysarm32"] if name != "winedevice-x64.exe" else ["system32"]
            if name.endswith(".sys"):
                destinations.append("system32/drivers")
        else:
            if machine is not None and machine != 0x14c:
                raise ValueError(f"Unexpected i386 PE architecture: {path}")
            destinations = ["syswow64"]
        for directory in destinations:
            target = f"{directory}/{name}"
            key = target.lower()
            if key in mapped:
                raise ValueError(f"Duplicate prefix mapping: {target}")
            mapped[key] = {"prefixPath": target, "runtimePath": path, **identity}
    return mapped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-wine", type=Path, required=True, help="v0.1.0 public Wine archive")
    parser.add_argument("--new-wine", type=Path, required=True, help="v0.2 mainline open-font Wine archive")
    parser.add_argument("--old-prefix", type=Path, required=True, help="v0.1.0 public prefix template")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if (file_hash(args.old_wine) != OLD_SHA or file_hash(args.new_wine) != NEW_SHA or
            file_hash(args.old_prefix) != OLD_PREFIX_SHA):
        raise SystemExit("Wine or prefix archive does not match the approved release identity")
    old = inventory(args.old_wine)
    new = inventory(args.new_wine)
    template = prefix_inventory(args.old_prefix)
    previous = mappings(old, old)
    candidate = mappings(new, old)
    wineboot_optional = verified_wineboot_optional_paths(args.new_wine)
    removed = set(previous) - set(candidate)
    if removed:
        raise SystemExit(f"Previously mapped Wine builtins removed; review required: {sorted(removed)[:10]}")
    entries, additions = [], []
    for key in sorted(candidate):
        entry = candidate[key]
        if not entry["regular"]:
            raise SystemExit(f"Wine candidate source is not a regular file: {entry['runtimePath']}")
        base = previous.get(key)
        row = {"prefixPath": entry["prefixPath"], "runtimePath": entry["runtimePath"]}
        if base is None:
            row["candidateSha256"] = entry["sha256"]
            additions.append(row)
        elif base["sha256"] != entry["sha256"]:
            row.update(baselineSha256=base["sha256"], candidateSha256=entry["sha256"])
            if key in wineboot_optional:
                row["winebootMayRemove"] = True
            alternates = []
            if key in template:
                alternates.append(template[key])
            if key == "system32/iexplore.exe":
                # Older prefixes can contain either architecture. A fresh
                # prefix may also contain the new runtime's i386 helper before
                # reconciliation selects its native ARM system32 counterpart.
                alternates.extend([old["lib/wine/i386-windows/iexplore.exe"]["sha256"],
                                   new["lib/wine/i386-windows/iexplore.exe"]["sha256"]])
            if alternates:
                row["baselineSha256Alternates"] = list(dict.fromkeys(
                    value for value in alternates if value not in (base["sha256"], entry["sha256"])))
                if not row["baselineSha256Alternates"]:
                    del row["baselineSha256Alternates"]
            entries.append(row)
    flagged = {row["prefixPath"].lower() for row in entries if row.get("winebootMayRemove")}
    if flagged != wineboot_optional:
        raise SystemExit(f"Wine INF optional paths differ from release delta: {sorted(flagged ^ wineboot_optional)}")
    if [item["prefixPath"] for item in additions] != ["system32/winedevice-x64.exe"]:
        raise SystemExit("Unexpected newly introduced Wine prefix builtins")
    manifest = {"schemaVersion": 1, "runtimeIdentifier": RUNTIME_ID, "entries": entries, "additions": additions}
    raw = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(raw)
    print(json.dumps({"entries": len(entries), "additions": len(additions), "sha256": hashlib.sha256(raw).hexdigest()}))


if __name__ == "__main__":
    main()
