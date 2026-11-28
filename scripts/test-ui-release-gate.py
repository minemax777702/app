"""Unit tests for strict capture release evidence."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("ui-release-gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class GateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        png = b"\x89PNG\r\n\x1a\nfixture"
        (self.root / "a.png").write_bytes(png)
        (self.root / "a.xml").write_text("<hierarchy/>")
        (self.root / "a.json").write_text('{"schema":1,"roots":[{}]}')
        self.registry = {"screens": [{"key": t + "/shopping"} for t in ("light", "dark")]}
        self.manifest = {"commit": "current", "coverage_complete": True, "auth_complete": True,
                         "screens": [{"theme": t, "screen": "shopping", "status": "captured",
                                      "screenshot": "a.png", "hierarchy": "a.xml", "layout": "a.json",
                                      "sha256": hashlib.sha256(png).hexdigest()} for t in ("light", "dark")]}

    def check(self):
        return gate.check(self.registry, self.manifest, self.root, "current", 2)

    def test_complete(self):
        report = self.check()
        self.assertTrue(report["passed"])
        self.assertFalse(report["visual_parity_verified"])

    def test_missing(self):
        self.manifest["screens"].pop()
        self.assertFalse(self.check()["passed"])

    def test_wrong_commit(self):
        self.manifest["commit"] = "old"
        self.assertFalse(self.check()["passed"])

    def test_hash(self):
        (self.root / "a.png").write_bytes(b"changed")
        self.assertFalse(self.check()["passed"])

    def test_duplicate(self):
        self.manifest["screens"].append(self.manifest["screens"][0])
        self.assertFalse(self.check()["passed"])

    def test_not_reached(self):
        self.manifest["screens"][0]["status"] = "not_reached"
        self.assertFalse(self.check()["passed"])

    def test_missing_layout(self):
        (self.root / "a.json").unlink()
        self.assertFalse(self.check()["passed"])


if __name__ == "__main__":
    unittest.main()
