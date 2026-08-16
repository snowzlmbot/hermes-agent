from __future__ import annotations

import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
VERIFIER = ROOT / "apps/mobile/test-server/verify_ios_xcarchive.py"


class IOSXCArchiveVerifierTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.archive = Path(self.temporary_directory.name) / "HermesMobile.xcarchive"
        self.app = self.archive / "Products/Applications/HermesMobile.app"
        self.app.mkdir(parents=True)
        self._write_plist(
            self.archive / "Info.plist",
            {
                "ArchiveVersion": 2,
                "Name": "HermesMobile",
                "SchemeName": "HermesMobile",
                "ApplicationProperties": {
                    "ApplicationPath": "Applications/HermesMobile.app",
                    "CFBundleIdentifier": "com.snowzlmbot.hermes.mobile",
                    "CFBundleShortVersionString": "0.1.0",
                    "CFBundleVersion": "1",
                },
            },
        )
        self._write_plist(
            self.app / "Info.plist",
            {
                "CFBundleExecutable": "HermesMobile",
                "CFBundleIdentifier": "com.snowzlmbot.hermes.mobile",
                "CFBundlePackageType": "APPL",
                "HermesAllowsInsecureTransport": False,
                "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": False},
            },
        )
        executable = self.app / "HermesMobile"
        executable.write_bytes(b"unsigned-ios-binary")
        executable.chmod(0o755)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_unsigned_release_application_archive(self) -> None:
        result = self._verify()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("unsigned iOS xcarchive verified", result.stdout)

    def test_rejects_embedded_profile_or_code_signature(self) -> None:
        (self.app / "embedded.mobileprovision").write_text("not-a-real-profile")
        (self.app / "_CodeSignature").mkdir()

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must be unsigned", result.stderr)

    def test_rejects_non_release_transport_policy(self) -> None:
        self._write_plist(
            self.app / "Info.plist",
            {
                "CFBundleExecutable": "HermesMobile",
                "CFBundleIdentifier": "com.snowzlmbot.hermes.mobile",
                "CFBundlePackageType": "APPL",
                "HermesAllowsInsecureTransport": True,
                "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True},
            },
        )

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release transport policy", result.stderr)

    def test_rejects_application_path_outside_products(self) -> None:
        self._write_plist(
            self.archive / "Info.plist",
            {
                "ArchiveVersion": 2,
                "Name": "HermesMobile",
                "SchemeName": "HermesMobile",
                "ApplicationProperties": {
                    "ApplicationPath": "../../HermesMobile.app",
                    "CFBundleIdentifier": "com.snowzlmbot.hermes.mobile",
                    "CFBundleShortVersionString": "0.1.0",
                    "CFBundleVersion": "1",
                },
            },
        )

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ApplicationPath", result.stderr)

    def _verify(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VERIFIER), str(self.archive)],
            check=False,
            capture_output=True,
            text=True,
        )

    @staticmethod
    def _write_plist(path: Path, value: dict[str, object]) -> None:
        with path.open("wb") as handle:
            plistlib.dump(value, handle)


if __name__ == "__main__":
    unittest.main()
