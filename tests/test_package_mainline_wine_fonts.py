import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "package_mainline_wine", ROOT / "scripts/package-mainline-wine.py"
)
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


def source_inf(newline="\n"):
    rows = ["[Fonts]"] + [
        f'HKCU,Software\\Wine\\Fonts\\Replacements,"{family}",,"Times New Roman"'
        for family in PACKAGE.SUBSTITUTIONS
    ]
    return ("[Version]" + newline + "Signature=$Chicago$" + newline
            + newline.join(rows) + newline + "[Next]" + newline).encode()


class MainlineFontPackagingTest(unittest.TestCase):
    def test_adds_noclobber_aliases_and_keeps_existing_substitutions(self):
        result = PACKAGE.openfont_inf(source_inf()).decode()
        for family, alias in zip(PACKAGE.FALLBACK_FAMILIES, PACKAGE.FALLBACK_ALIASES):
            self.assertEqual(result.count(alias), 1)
            self.assertIn(f'"{family}",0x00000002,"Source Han Sans CN"', result)
        for family, target in PACKAGE.SUBSTITUTIONS.items():
            self.assertIn(f'"{family}",,"{target}"', result)
        self.assertTrue(result.endswith("[Next]\n"))

    def test_preserves_windows_line_endings(self):
        result = PACKAGE.openfont_inf(source_inf("\r\n"))
        self.assertNotIn(b"\n", result.replace(b"\r\n", b""))
        for alias in PACKAGE.FALLBACK_ALIASES:
            self.assertIn(alias.encode() + b"\r\n", result)

    def test_rejects_existing_mapping_instead_of_adding_a_duplicate(self):
        for family in PACKAGE.FALLBACK_FAMILIES:
            duplicate = f'  HKCU,Software\\Wine\\Fonts\\Replacements,"{family.lower()}",,"Other"\n[Next]'.encode()
            source = source_inf().replace(b"[Next]", duplicate)
            with self.subTest(family=family):
                with self.assertRaisesRegex(ValueError, f"already defines {family}"):
                    PACKAGE.openfont_inf(source)

    def test_rejects_missing_and_duplicate_expected_rows(self):
        source = source_inf().decode()
        original = 'HKCU,Software\\Wine\\Fonts\\Replacements,"Verdana",,"Times New Roman"'
        for changed in (source.replace(original, ""), source.replace(original, original + "\n" + original)):
            with self.subTest(changed=changed):
                with self.assertRaisesRegex(ValueError, "Expected one original font row"):
                    PACKAGE.openfont_inf(changed.encode())

    def test_rejects_substitutions_outside_fonts_section(self):
        source = source_inf().decode().replace("[Fonts]", "[Other]")
        with self.assertRaisesRegex(ValueError, "Fonts section"):
            PACKAGE.openfont_inf(source.encode())


if __name__ == "__main__":
    unittest.main()
