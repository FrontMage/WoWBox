import importlib.util
import io
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("package_public_xinput", ROOT / "scripts/package-public-xinput.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ARCHIVE = ROOT / "app/src/main/assets/winhandler/winhandler-xinput-wow-filtered-arm64x-149a9ca4.tzst"


class PublicInputBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not ARCHIVE.is_file():
            raise unittest.SkipTest("Fetch public runtime assets before testing the input bridge")
        data = subprocess.check_output(["zstd", "-dc", str(ARCHIVE)])
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            cls.images = {member.name: archive.extractfile(member).read() for member in archive if member.isfile()}

    def test_public_images_are_builtins_without_user_paths(self):
        self.assertEqual(set(self.images), set(MODULE.FILES))
        for name, data in self.images.items():
            self.assertEqual(MODULE.sha(data), MODULE.FILES[name][1])
            self.assertNotIn(b"/Users/", data)
            _, sections = MODULE.pe(data)
            self.assertEqual(set(sections), MODULE.RUNTIME_SECTIONS)

    def test_changed_runtime_code_is_rejected(self):
        data = self.images["xinput1_3.dll"]
        _, sections = MODULE.pe(data)
        modified = bytearray(data)
        modified[sections[".text"][4]] ^= 1
        with tempfile.TemporaryDirectory() as directory:
            original, changed = Path(directory) / "original.dll", Path(directory) / "changed.dll"
            original.write_bytes(data)
            changed.write_bytes(modified)
            with self.assertRaisesRegex(ValueError, "Runtime virtual section changed"):
                MODULE.inspect(original, changed, Path(directory) / "unused-readobj")

    def test_changed_loader_directories_are_rejected(self):
        data = self.images["xinput1_4.dll"]
        modified = bytearray(data)
        header = struct.unpack_from("<I", modified, 60)[0]
        modified[header + 24 + 112] ^= 1
        with tempfile.TemporaryDirectory() as directory:
            original, changed = Path(directory) / "original.dll", Path(directory) / "changed.dll"
            original.write_bytes(data)
            changed.write_bytes(modified)
            with self.assertRaisesRegex(ValueError, "PE loader directory"):
                MODULE.inspect(original, changed, Path(directory) / "unused-readobj")

    def test_missing_wine_builtin_marker_is_rejected(self):
        data = bytearray(self.images["xinput1_3.dll"])
        data[64] ^= 1
        with self.assertRaisesRegex(ValueError, "Wine builtin"):
            MODULE.pe(data)


if __name__ == "__main__":
    unittest.main()
