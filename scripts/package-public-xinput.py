#!/usr/bin/env python3
"""Strip debug info from the checked v0.1 ARM64X input bridge for public use.

The input is the exact v0.1 bridge archive. This transformation removes only
DWARF sections, verifies all runtime section bytes and PE loader metadata, then
produces the deterministic v0.2 archive. It does not modify the Wine runtime.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile

OLD_ARCHIVE = "437d68591aace9cd599e7e2653abe0333fa7726df1d65d20fc2ffa33a2beff4d"
NEW_ARCHIVE = "149a9ca40b1119d01de5599faead07a9748cba511b0039fafaa48b4e0e10078c"
FILES = {
    "xinput1_3.dll": ("05ce004a27c25ef13fa60182ae1cc7c993ecf8615891563c59b5e615ede46a45", "9cbc7729a73b004695c0f12f7bd7e5903f67d8aeae6d3c0123e9c6502ecd2842"),
    "xinput1_4.dll": ("b390f99c043ddf0d724607946065dba0cd8dfc2b06744b8edf2714ff300854d0", "a7988b896b2b38809e2a673823efdb67f89c8cfe3aaca7cd446771f158b54000"),
}
RUNTIME_SECTIONS = {".text", ".hexpthk", ".rdata", ".buildid", ".data", ".pdata", ".a64xrm", ".rsrc", ".reloc"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pe(data):
    if len(data) < 256 or data[:2] != b"MZ" or data[64:80] != b"Wine builtin DLL":
        raise ValueError("Not a Wine builtin PE image")
    off = struct.unpack_from("<I", data, 60)[0]
    if data[off:off + 4] != b"PE\0\0":
        raise ValueError("Invalid PE header")
    machine, count = struct.unpack_from("<HH", data, off + 4)
    if machine != 0xAA64 or count > 96:
        raise ValueError("Unexpected ARM64X machine or section count")
    symptr, symcount = struct.unpack_from("<II", data, off + 12)
    optlen = struct.unpack_from("<H", data, off + 20)[0]
    opt = off + 24
    if optlen < 240 or struct.unpack_from("<H", data, opt)[0] != 0x20B:
        raise ValueError("Unexpected PE optional header")
    string_table = symptr + symcount * 18
    sections = {}
    for i in range(count):
        start = opt + optlen + 40 * i
        raw_name = data[start:start + 8].split(b"\0", 1)[0]
        if raw_name.startswith(b"/"):
            name_start = string_table + int(raw_name[1:])
            name = data[name_start:data.index(b"\0", name_start)].decode("ascii")
        else:
            name = raw_name.decode("ascii")
        vsize, vaddr, rsize, rptr = struct.unpack_from("<IIII", data, start + 8)
        flags = struct.unpack_from("<I", data, start + 36)[0]
        if name in sections or rptr + rsize > len(data) or vsize > 16 * 1024 * 1024:
            raise ValueError("Unsafe or unsupported PE section")
        virtual = data[rptr:rptr + min(vsize, rsize)] + b"\0" * max(0, vsize - rsize)
        sections[name] = (vsize, vaddr, flags, virtual, rptr, rsize)
    return data[opt + 112:opt + 240], sections


def inspect(original, stripped, readobj):
    old_dirs, old = pe(original.read_bytes())
    new_dirs, new = pe(stripped.read_bytes())
    if old_dirs != new_dirs or set(new) != RUNTIME_SECTIONS:
        raise ValueError("PE loader directory or runtime sections changed")
    if {k for k in old if not k.startswith(".debug_")} != RUNTIME_SECTIONS:
        raise ValueError("Unexpected original non-debug section")
    for name in RUNTIME_SECTIONS:
        if old[name][:4] != new[name][:4]:
            raise ValueError("Runtime virtual section changed: " + name)
    new_data = stripped.read_bytes()
    if b"/Users/" in new_data:
        raise ValueError("Stripped input bridge contains a user path")
    for opt in ("--coff-exports", "--coff-imports", "--coff-load-config", "--coff-basereloc", "--coff-debug-directory"):
        def report(path):
            text = subprocess.check_output([str(readobj), opt, str(path)], text=True)
            return "\n".join(line for line in text.splitlines() if not line.startswith("File: "))
        if report(original) != report(stripped):
            raise ValueError("PE metadata changed: " + opt)
    header = subprocess.check_output([str(readobj), "--file-headers", str(stripped)], text=True)
    if "Format: COFF-ARM64X" not in header:
        raise ValueError("Stripped image lost ARM64X format")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Original v0.1 bridge archive")
    parser.add_argument("--llvm-bin", type=Path, required=True, help="llvm-mingw 20250920 bin directory")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    archive = args.input.read_bytes()
    if sha(archive) != OLD_ARCHIVE:
        raise SystemExit("Original bridge archive identity mismatch")
    with tempfile.TemporaryDirectory(prefix="wowbox-public-xinput-") as tmp:
        root = Path(tmp)
        uncompressed = subprocess.check_output(["zstd", "-dc", str(args.input)])
        with tarfile.open(fileobj=io.BytesIO(uncompressed), mode="r:") as tar:
            members = tar.getmembers()
            if len(members) != 2 or {m.name for m in members} != set(FILES) or not all(m.isfile() for m in members):
                raise SystemExit("Unexpected bridge archive entries")
            for member in members:
                data = tar.extractfile(member).read()
                if sha(data) != FILES[member.name][0]:
                    raise SystemExit("Original bridge DLL identity mismatch")
                (root / member.name).write_bytes(data)
        for name, (_, after_hash) in FILES.items():
            source = root / name
            stripped = root / (name + ".stripped")
            subprocess.run([str(args.llvm_bin / "llvm-strip"), "--strip-debug", "-o", str(stripped), str(source)], check=True)
            if sha(stripped.read_bytes()) != after_hash:
                raise SystemExit("Stripped DLL identity mismatch; check llvm-mingw version")
            inspect(source, stripped, args.llvm_bin / "llvm-readobj")
            source.write_bytes(stripped.read_bytes())
            source.chmod(0o644)
            os.utime(source, (315504000, 315504000))
        env = dict(os.environ, COPYFILE_DISABLE="1")
        tar = subprocess.check_output(["tar", "--format", "ustar", "--uid", "0", "--gid", "0", "--uname", "root", "--gname", "root", "-C", str(root), "-cf", "-", *FILES], env=env)
        output = subprocess.check_output(["zstd", "-19", "-T1", "-q", "-c"], input=tar)
        if sha(output) != NEW_ARCHIVE:
            raise SystemExit("Archive identity mismatch; check tar/zstd version")
        if args.output.exists() and args.output.read_bytes() != output:
            raise SystemExit("Output exists with different contents; preserved")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(output)
        print("Verified public bridge SHA256 " + NEW_ARCHIVE)


if __name__ == "__main__":
    main()
