import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
A = ROOT / "apps/mobile/android/app"


class T(unittest.TestCase):
    def test_android(self):
        ns = "{http://schemas.android.com/apk/res/android}"
        paths = [A / "src/main/AndroidManifest.xml", A / "src/debug/AndroidManifest.xml"]
        apps = [ET.parse(p).getroot().find("application") for p in paths]
        values = []
        for app in apps:
            assert app is not None
            values.append(app.get(ns + "usesCleartextTraffic"))
        self.assertEqual(values, ["false", "true"])


if __name__ == "__main__":
    unittest.main()
