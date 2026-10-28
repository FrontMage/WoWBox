#!/usr/bin/env python3
"""Export the public source corresponding to the v0.2 runtime inputs.

Wine/FEX are exported from exact Git commits, without worktree edits or Git
history. Unchanged v0.1 Vulkan-wrapper and integration sources are reused from
the verified public v0.1 source bundle; binary test submodules are not needed
to build the selected FEX Release libraries and are identified in the receipt.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import posixpath
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
WINE = "a545dee5aa1991ba77985a2cfe8d6a490152d166"
FEX = "7519b9605209e9e19eaf7be669dae7c1848fe5f1"
PROTON = "212f9e729f7f79a582aa1def2d313f21b4c2181c"
OLD_SOURCE_SHA = "2e1c0328e08ef6848b43d5b367710738bf21b59525bae2c8a7f44e84c37e243a"
BASE = "WoWBox-runtime-sources/"
EXCLUDED = {
    "External/fex-gcc-target-tests-bins",
    "External/fex-gvisor-tests-bins",
    "External/fex-posixtest-bins",
    "External/range-v3/doc/gh-pages",
}


def run(*cmd):
    return subprocess.check_output(cmd, text=True).rstrip("\n")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_checkout(path, commit):
    if run("git", "-C", str(path), "rev-parse", "HEAD") != commit:
        raise ValueError(f"Source checkout does not match pinned commit: {path.name}")
    if run("git", "-C", str(path), "status", "--porcelain", "--untracked-files=no"):
        raise ValueError(f"Tracked source changes present: {path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-git", type=Path, required=True)
    parser.add_argument("--fex-git", type=Path, required=True)
    parser.add_argument("--v010-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    validate_checkout(args.wine_git, WINE)
    validate_checkout(args.fex_git, FEX)
    if digest(args.v010_sources) != OLD_SOURCE_SHA:
        raise SystemExit("Previously published source bundle SHA mismatch")
    modules = []
    lines = run("git", "-C", str(args.fex_git), "submodule", "status", "--recursive").splitlines()
    for line in lines:
        if not line.startswith(" "):
            raise SystemExit("FEX source dependency is unavailable or not at its pinned revision")
        match = re.match(r" ([0-9a-f]{40}) ([^ ]+)", line)
        if not match:
            raise SystemExit("Unexpected FEX submodule status")
        commit, path = match.groups()
        checkout = args.fex_git / path
        validate_checkout(checkout, commit)
        url = run("git", "-C", str(checkout), "remote", "get-url", "origin")
        if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?", url):
            raise ValueError(f"Unsupported or private source origin: {path}")
        modules.append({"path": path, "commit": commit, "url": url, "included": path not in EXCLUDED})

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    seen = set()
    omitted_previous_links = []
    counts = {"wine": 0, "fex": 0, "dependencies": 0, "previousPublic": 0}
    with temporary.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=6) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as out:
                def add(item, stream, destination, counter):
                    name = item.name.removeprefix("./").rstrip("/")
                    if not name or name.startswith("/") or "\\" in name or any(p in ("", ".", "..") for p in name.split("/")):
                        raise ValueError(f"Unsafe source archive member: {name}")
                    target = destination + name
                    if target in seen:
                        if item.isdir():
                            return
                        raise ValueError(f"Duplicate source archive member: {target}")
                    seen.add(target)
                    info = copy.copy(item)
                    info.name = target
                    info.uid = info.gid = info.mtime = 0
                    info.uname = info.gname = ""
                    if item.isfile():
                        out.addfile(info, stream.extractfile(item))
                    elif item.isdir() or item.issym():
                        if item.issym():
                            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(target), item.linkname))
                            if item.linkname.startswith("/") or not resolved.startswith(BASE):
                                raise ValueError(f"Unsafe source symlink: {target}")
                        out.addfile(info)
                    else:
                        raise ValueError(f"Unsupported source member type: {target}")
                    counts[counter] += 1

                def add_git(path, commit, destination, counter):
                    proc = subprocess.Popen(["git", "-C", str(path), "archive", "--format=tar", commit], stdout=subprocess.PIPE)
                    try:
                        with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
                            for item in archive:
                                add(item, archive, destination, counter)
                        if proc.wait() != 0:
                            raise RuntimeError("Unable to export pinned public source")
                    finally:
                        if proc.poll() is None:
                            proc.kill()
                            proc.wait()

                add_git(args.wine_git, WINE, BASE + "Wine/", "wine")
                add_git(args.fex_git, FEX, BASE + "FEX/", "fex")
                for module in modules:
                    if module["included"]:
                        add_git(args.fex_git / module["path"], module["commit"],
                                BASE + "FEX/" + module["path"] + "/", "dependencies")
                with tarfile.open(args.v010_sources, mode="r|gz") as previous:
                    for item in previous:
                        name = item.name.removeprefix("./").rstrip("/")
                        if name.startswith(BASE + "VulkanWrapper/") or name.startswith(BASE + "Integration/"):
                            if item.issym():
                                component = BASE + ("VulkanWrapper/" if name.startswith(BASE + "VulkanWrapper/") else "Integration/")
                                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname))
                                if item.linkname.startswith("/") or not resolved.startswith(component):
                                    # Old bundles may contain local build-tree symlinks.
                                    # Do not publish their machine-specific targets.
                                    omitted_previous_links.append(name)
                                    continue
                            item = copy.copy(item)
                            item.name = name[len(BASE):]
                            add(item, previous, BASE, "previousPublic")

                def add_bytes(name, content):
                    target = BASE + name
                    if target in seen:
                        raise ValueError(f"Duplicate source metadata: {name}")
                    seen.add(target)
                    info = tarfile.TarInfo(target)
                    info.size = len(content)
                    info.mode = 0o644
                    info.mtime = info.uid = info.gid = 0
                    out.addfile(info, io.BytesIO(content))

                metadata = {
                    "schemaVersion": 1,
                    "protonCommit": PROTON,
                    "wineCommit": WINE,
                    "fexCommit": FEX,
                    "wineSourceUrl": "https://github.com/FrontMage/proton-wine",
                    "fexSourceUrl": "https://github.com/FrontMage/FEX",
                    "fontPatch": "wine-openfonts-mainline.patch",
                    "inputBridge": {
                        "sourceUrl": "https://github.com/brunodev85/wine-9.2-custom",
                        "sourceCommit": "a4ef2bf8963fe4bfab390eebf73336364ce209bc",
                        "originalArchiveSha256": "437d68591aace9cd599e7e2653abe0333fa7726df1d65d20fc2ffa33a2beff4d",
                        "publicArchiveSha256": "149a9ca40b1119d01de5599faead07a9748cba511b0039fafaa48b4e0e10078c",
                        "publicationChange": "LLVM --strip-debug removes DWARF only; runtime sections and ARM64X loader metadata are verified unchanged.",
                        "packagingScript": "Integration/scripts/package-public-xinput.py",
                    },
                    "fexDependencies": modules,
                    "previousPublicBundleSha256": OLD_SOURCE_SHA,
                    "omittedPreviousSourceSymlinks": omitted_previous_links,
                    "omittedDependencies": "Test-binary repositories and range-v3 generated documentation only; exact public URLs and commits are listed above.",
                }
                add_bytes("SOURCE_MANIFEST.json", (json.dumps(metadata, indent=2) + "\n").encode())
                add_bytes("wine-openfonts-mainline.patch", (ROOT / "tools/wine-openfonts-mainline.patch").read_bytes())
                add_bytes("Integration/scripts/package-public-xinput.py", (ROOT / "scripts/package-public-xinput.py").read_bytes())
                add_bytes("README.md", (
                    "# WoW Box v0.2 runtime source inputs\n\n"
                    "`Wine/` and `FEX/` are exported from the exact public commits in SOURCE_MANIFEST.json. "
                    "The Wine binary was built from that Wine commit, with the four font substitutions "
                    "in wine-openfonts-mainline.patch applied to the distributed INF data. "
                    "FEX submodule source is included except test-binary repositories and generated docs. "
                    "The XInput bridge source and filter patch are referenced in SOURCE_MANIFEST.json; "
                    "its public packaging script removes only DWARF from the original v0.1 build. "
                    "VulkanWrapper/ and Integration/ are unchanged from the v0.1 public source bundle. "
                    "Consult WoW Box THIRD_PARTY_NOTICES.md for component licenses and the known "
                    "provenance limits of inherited base-image libraries.\n"
                ).encode())
    temporary.replace(args.output)
    receipt = {"schemaVersion": 1, "archiveSha256": digest(args.output), "archiveBytes": args.output.stat().st_size,
               "protonCommit": PROTON, "wineCommit": WINE, "fexCommit": FEX, "counts": counts,
               "omittedPreviousSourceSymlinks": omitted_previous_links, "fexDependencies": modules}
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sha256": receipt["archiveSha256"], "bytes": receipt["archiveBytes"], "counts": counts}))


if __name__ == "__main__":
    main()
