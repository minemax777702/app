import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pixso_sync", ROOT / "scripts/pixso-sync.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

class SyncTests(unittest.TestCase):
    def setUp(self):
        self.base = sync.load(sync.BASELINE)
        self.live = copy.deepcopy(self.base)
        self.source = sync.THEME.read_text(encoding="utf-8")
        self.code = sync.code_values(self.source)

    def test_canonical_registry(self):
        cfg = sync.config(sync.load(sync.REGISTRY))
        self.assertEqual(len(cfg["targets"]), len(self.base["targets"]))
        self.assertEqual(len({x["frame_id"] for x in cfg["targets"]}), len(cfg["targets"]))

    def test_initial_mapping(self):
        for token, modes in self.code.items():
            self.assertIn(token, self.base["tokens"])
            for mode, value in modes.items():
                self.assertRegex(value, r"^[0-9A-F]{6}$")

    def test_roundtrip(self):
        self.assertEqual(sync.code_values(sync.render(self.source, self.base["tokens"])), {
            token: {mode: self.base["tokens"][token][mode] for mode in modes}
            for token, modes in self.code.items()
        })

    def test_design_to_code(self):
        name = "Theme/Background"
        code = {k: {m: self.base["tokens"][k][m] for m in v} for k, v in self.code.items()}
        self.live["tokens"][name]["light"] = "112233"
        report, merged = sync.compare(self.base, self.live, code)
        self.assertFalse(report["blocked"])
        self.assertEqual(len(report["pulls"]), 1)
        self.assertEqual(sync.code_values(sync.render(self.source, merged))[name]["light"], "112233")

    def test_code_to_design(self):
        name = "Theme/Surface"
        code = {k: {m: self.base["tokens"][k][m] for m in v} for k, v in self.code.items()}
        code[name]["dark"] = "445566"
        report, merged = sync.compare(self.base, self.live, code)
        self.assertFalse(report["blocked"])
        self.assertEqual(len(report["pushes"]), 1)
        self.assertEqual(merged[name]["dark"], "445566")

    def test_conflicting_edit(self):
        name = "Theme/Surface"
        code = {k: {m: self.base["tokens"][k][m] for m in v} for k, v in self.code.items()}
        self.live["tokens"][name]["dark"] = "112233"
        code[name]["dark"] = "445566"
        report, _ = sync.compare(self.base, self.live, code)
        self.assertTrue(report["blocked"])
        self.assertEqual(len(report["conflicts"]), 1)

    def test_structural_and_unmapped_changes_block(self):
        self.live["targets"][next(iter(self.live["targets"]))]["hash"] = "edited"
        self.live["tokens"]["Theme/Scrim"]["dark"] = "222222"
        report, _ = sync.compare(self.base, self.live, self.code)
        self.assertTrue(report["blocked"])
        self.assertTrue(report["structural"]["targets"])
        self.assertTrue(report["unmapped"])

if __name__ == "__main__":
    unittest.main()
