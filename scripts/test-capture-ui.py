"""Tests for disposable Android capture recovery. No ADB/device required."""
import importlib.util
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("capture_ui", Path(__file__).with_name("capture-ui.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

def dialog(title, button_id="android:id/aerr_close", package="android"):
    root = ET.Element("hierarchy")
    ET.SubElement(root, "node", {"package": "android", "resource-id": "android:id/alertTitle", "text": title})
    ET.SubElement(root, "node", {"package": package, "resource-id": button_id, "clickable": "true",
                                 "enabled": "true", "bounds": "[70,1170][1010,1296]"})
    return root

class CaptureTests(unittest.TestCase):
    def test_recognize_launcher_anr(self):
        self.assertEqual(capture.launcher_close_target(dialog("Pixel Launcher isn't responding")), (540, 1233))

    def test_do_not_dismiss_app_anr(self):
        self.assertIsNone(capture.launcher_close_target(dialog("Список покупок isn't responding")))

    def test_do_not_dismiss_unknown_dialog(self):
        self.assertIsNone(capture.launcher_close_target(dialog("Allow camera access?")))

    def test_require_native_close_button(self):
        self.assertIsNone(capture.launcher_close_target(dialog("Pixel Launcher isn't responding", "android:id/aerr_wait")))
        self.assertIsNone(capture.launcher_close_target(dialog("Pixel Launcher isn't responding", package="com.mok.buylist.capture")))

    def test_fresh_hierarchy_error_never_reads_old_xml(self):
        with patch.object(capture, "shell", side_effect=["", "ERROR: could not get idle state"]) as shell, patch.object(capture, "adb") as adb:
            with self.assertRaises(RuntimeError):
                capture.hierarchy()
            shell.assert_any_call("rm", "-f", "/sdcard/buylist-ui.xml")
            adb.assert_not_called()

    def test_no_recovery_on_non_fixture(self):
        with patch.object(capture, "FIXTURE", False), patch.object(capture, "shell") as shell:
            self.assertFalse(capture.disposable_emulator())
            shell.assert_not_called()

    def test_recovery_requires_emulator(self):
        with patch.object(capture, "FIXTURE", True), patch.object(capture, "shell", return_value="0"):
            self.assertFalse(capture.disposable_emulator())

    def test_launcher_recovery_then_app(self):
        app = ET.Element("hierarchy")
        ET.SubElement(app, "node", {"package": capture.PKG})
        with patch.object(capture, "disposable_emulator", return_value=True), patch.object(capture, "shell", return_value="Status: ok") as shell, patch.object(capture, "hierarchy", side_effect=[(b"", dialog("Pixel Launcher isn't responding")), (b"", app)]), patch.object(capture, "save_launch_diagnostics") as diag, patch.object(capture.time, "sleep"):
            capture.start()
            shell.assert_any_call("input", "tap", "540", "1233")
            diag.assert_called_once()

    def test_app_anr_fails_without_clicking(self):
        with patch.object(capture, "disposable_emulator", return_value=True), patch.object(capture, "shell", return_value="Status: ok") as shell, patch.object(capture, "hierarchy", return_value=(b"", dialog("Список покупок isn't responding"))), patch.object(capture, "save_launch_diagnostics"), patch.object(capture.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "System error dialog"):
                capture.start()
            self.assertFalse(any(call.args[:2] == ("input", "tap") for call in shell.call_args_list))

if __name__ == "__main__":
    unittest.main()
