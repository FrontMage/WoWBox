import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


SOURCE_SCRIPT = Path(__file__).resolve().parents[1] / "scripts/fetch-runtime-assets.py"
EXISTING = "app/src/main/assets/helper.exe"
NEW = "app/src/main/assets/bundle/runtime.txz"


def sha(data):
    return hashlib.sha256(data).hexdigest()


class RuntimeBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "project"
        (self.root / "scripts").mkdir(parents=True)
        shutil.copy2(SOURCE_SCRIPT, self.root / "scripts/fetch-runtime-assets.py")
        self.existing = self.root / EXISTING
        self.existing.parent.mkdir(parents=True)
        self.existing.write_bytes(b"tracked executable")
        self.existing.chmod(0o755)
        self.new_bytes = b"downloaded runtime"
        self.bundle = Path(self.temp.name) / "runtime.tar.gz"
        self.payload = [(EXISTING, b"tracked executable"), (NEW, self.new_bytes)]
        self.make_bundle(self.payload)
        self.manifest = {
            "bundle": {"filename": self.bundle.name, "url": "unused", "sha256": sha(self.bundle.read_bytes())},
            "files": [
                {"path": EXISTING, "sha256": sha(b"tracked executable"), "sizeBytes": 18, "mode": 0o644},
                {"path": NEW, "sha256": sha(self.new_bytes), "sizeBytes": len(self.new_bytes), "mode": 0o644},
            ],
        }
        self.save_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def make_bundle(self, entries):
        with tarfile.open(self.bundle, "w:gz") as archive:
            for name, content in entries:
                member = tarfile.TarInfo(name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))

    def save_manifest(self):
        (self.root / "runtime-assets.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def run_fetch(self, *arguments):
        return subprocess.run([sys.executable, str(self.root / "scripts/fetch-runtime-assets.py"), *map(str, arguments)],
                              text=True, capture_output=True, cwd=self.root, check=False)

    def test_partial_bootstrap_keeps_existing_inode_mode_and_mtime(self):
        before = self.existing.stat()
        result = self.run_fetch("--bundle", self.bundle)
        self.assertEqual(0, result.returncode, result.stderr)
        after = self.existing.stat()
        self.assertEqual(before.st_ino, after.st_ino)
        self.assertEqual(before.st_mtime_ns, after.st_mtime_ns)
        self.assertEqual(0o755, after.st_mode & 0o777)
        self.assertEqual(b"tracked executable", self.existing.read_bytes())
        self.assertEqual(self.new_bytes, (self.root / NEW).read_bytes())
        self.assertEqual(0o644, (self.root / NEW).stat().st_mode & 0o777)

    def test_bad_bundle_hash_leaves_all_originals_untouched(self):
        before = self.existing.stat()
        self.manifest["bundle"]["sha256"] = "0" * 64
        self.save_manifest()
        result = self.run_fetch("--bundle", self.bundle)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Runtime bundle checksum mismatch", result.stderr)
        self.assertEqual(before.st_ino, self.existing.stat().st_ino)
        self.assertEqual(0o755, self.existing.stat().st_mode & 0o777)
        self.assertFalse((self.root / NEW).exists())

    def test_bad_late_member_does_not_install_earlier_staged_file(self):
        first = "app/src/main/assets/new-a.dat"
        bad = "app/src/main/assets/new-z.dat"
        self.make_bundle([(EXISTING, b"tracked executable"), (first, b"valid"), (bad, b"wrong")])
        self.manifest["bundle"]["sha256"] = sha(self.bundle.read_bytes())
        self.manifest["files"] = [self.manifest["files"][0],
                                  {"path": first, "sha256": sha(b"valid"), "sizeBytes": 5},
                                  {"path": bad, "sha256": sha(b"right"), "sizeBytes": 5}]
        self.save_manifest()
        result = self.run_fetch("--bundle", self.bundle)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Asset checksum mismatch", result.stderr)
        self.assertFalse((self.root / first).exists())
        self.assertFalse((self.root / bad).exists())
        self.assertEqual(0o755, self.existing.stat().st_mode & 0o777)

    def test_existing_modified_asset_is_preserved(self):
        self.existing.write_bytes(b"developer changes")
        before = self.existing.stat()
        result = self.run_fetch("--bundle", self.bundle)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Existing runtime asset mismatch; preserved", result.stderr)
        self.assertEqual(b"developer changes", self.existing.read_bytes())
        self.assertEqual(before.st_ino, self.existing.stat().st_ino)
        self.assertFalse((self.root / NEW).exists())

    def test_complete_assets_with_explicit_bundle_still_verify_bundle(self):
        (self.root / NEW).parent.mkdir(parents=True)
        (self.root / NEW).write_bytes(self.new_bytes)
        self.manifest["bundle"]["sha256"] = "1" * 64
        self.save_manifest()
        result = self.run_fetch("--bundle", self.bundle)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Runtime bundle checksum mismatch", result.stderr)
        self.assertEqual(0o755, self.existing.stat().st_mode & 0o777)
        self.assertEqual(self.new_bytes, (self.root / NEW).read_bytes())

    def test_unexpected_and_duplicate_bundle_entries_fail_before_install(self):
        for extra in ([("app/src/main/assets/extra", b"x")], [(NEW, self.new_bytes)]):
            with self.subTest(extra=extra):
                self.make_bundle(self.payload + extra)
                self.manifest["bundle"]["sha256"] = sha(self.bundle.read_bytes())
                self.save_manifest()
                result = self.run_fetch("--bundle", self.bundle)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Unexpected or duplicate", result.stderr)
                self.assertFalse((self.root / NEW).exists())

    def test_symlinked_parent_cannot_redirect_asset_write(self):
        outside = Path(self.temp.name) / "outside"
        outside.mkdir()
        (self.root / "app/src/main/assets/bundle").symlink_to(outside, target_is_directory=True)
        result = self.run_fetch("--bundle", self.bundle)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsafe runtime asset parent", result.stderr)
        self.assertEqual([], list(outside.iterdir()))

    def test_verify_mode_detects_missing_without_creating_files(self):
        result = self.run_fetch("--verify")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing runtime assets", result.stderr)
        self.assertFalse((self.root / NEW).exists())
        self.assertEqual(0o755, self.existing.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
