#!/usr/bin/env python3
"""Prepare the public open-font Wine archive from the pinned mainline build.

Only four font substitutions in share/wine/wine.inf may change. The source
archive is never modified. Pass the matching loader/wine.inf.in to generate
the corresponding-source patch. See the emitted receipt for input/output SHA.
"""
import argparse
import copy
import difflib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

PINNED_SHA = "d2e1ed2f22825904a0acc8dacd6e4d534482133cf16952d11928951fe23ce313"
SUBSTITUTIONS = {
    "Palatino Linotype": "DejaVu Serif",
    "Verdana": "DejaVu Sans",
    "Segoe UI Semibold": "DejaVu Sans",
    "Segoe UI": "DejaVu Sans",
}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def openfont_inf(data):
    text = data.decode("utf-8")
    for family, target in SUBSTITUTIONS.items():
        old = f'HKCU,Software\\Wine\\Fonts\\Replacements,"{family}",,"Times New Roman"'
        new = f'HKCU,Software\\Wine\\Fonts\\Replacements,"{family}",,"{target}"'
        if text.count(old) != 1:
            raise ValueError(f"Expected one original font row for {family}")
        text = text.replace(old, new)
    return text.encode("utf-8")


class HashReader:
    def __init__(self, source):
        self.source = source
        self.sha = hashlib.sha256()

    def read(self, size):
        data = self.source.read(size)
        self.sha.update(data)
        return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-inf", type=Path, required=True)
    parser.add_argument("--source-patch", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if digest(args.input) != PINNED_SHA:
        raise SystemExit("Mainline Wine input checksum does not match the pinned build")
    if args.input.resolve() == args.output.resolve():
        raise SystemExit("Input and output must differ")
    src = args.source_inf.read_bytes()
    patched = openfont_inf(src)
    patch = "".join(difflib.unified_diff(
        src.decode("utf-8").splitlines(keepends=True),
        patched.decode("utf-8").splitlines(keepends=True),
        fromfile="a/loader/wine.inf.in", tofile="b/loader/wine.inf.in"))
    args.source_patch.parent.mkdir(parents=True, exist_ok=True)
    args.source_patch.write_text(patch, encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_name(args.output.name + ".tmp")
    members = []
    changed = []
    decoder = subprocess.Popen(["xz", "-dc", str(args.input)], stdout=subprocess.PIPE)
    with temp.open("wb") as output:
        encoder = subprocess.Popen(["xz", "-T2", "-3", "-c"], stdin=subprocess.PIPE, stdout=output)
        try:
            with tarfile.open(fileobj=decoder.stdout, mode="r|") as source, \
                    tarfile.open(fileobj=encoder.stdin, mode="w|", format=tarfile.PAX_FORMAT) as target:
                for item in source:
                    info = copy.copy(item)
                    path = item.name.removeprefix("./")
                    row = {"path": path, "type": item.type.decode("latin1"), "size": item.size}
                    if item.isfile() and path == "share/wine/wine.inf":
                        before = source.extractfile(item).read()
                        after = openfont_inf(before)
                        info.size = len(after)
                        target.addfile(info, io.BytesIO(after))
                        row["sourceSha256"] = hashlib.sha256(before).hexdigest()
                        row["outputSha256"] = hashlib.sha256(after).hexdigest()
                        changed.append(path)
                    elif item.isfile():
                        wrapped = HashReader(source.extractfile(item))
                        target.addfile(info, wrapped)
                        row["sourceSha256"] = wrapped.sha.hexdigest()
                        row["outputSha256"] = row["sourceSha256"]
                    else:
                        row["linkname"] = item.linkname
                        target.addfile(info)
                    members.append(row)
            encoder.stdin.close()
            if decoder.wait() != 0 or encoder.wait() != 0:
                raise RuntimeError("Wine archive compression failed")
        except BaseException:
            decoder.kill()
            encoder.kill()
            decoder.wait()
            encoder.wait()
            temp.unlink(missing_ok=True)
            raise
    if changed != ["share/wine/wine.inf"]:
        temp.unlink(missing_ok=True)
        raise RuntimeError(f"Unexpected changed Wine archive entries: {changed}")
    temp.replace(args.output)
    receipt = {
        "schemaVersion": 1,
        "wineCommit": "a545dee5aa1991ba77985a2cfe8d6a490152d166",
        "protonCommit": "212f9e729f7f79a582aa1def2d313f21b4c2181c",
        "inputSha256": PINNED_SHA,
        "outputSha256": digest(args.output),
        "outputBytes": args.output.stat().st_size,
        "sourcePatchSha256": digest(args.source_patch),
        "onlyModifiedMembers": changed,
        "members": members,
    }
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: receipt[k] for k in ("inputSha256", "outputSha256", "outputBytes", "onlyModifiedMembers")}))


if __name__ == "__main__":
    main()
