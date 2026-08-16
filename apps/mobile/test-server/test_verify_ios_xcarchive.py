from __future__ import annotations

import plistlib
import struct
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
        self._write_archive_info()
        self._write_app_info()
        self._write_macho()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_unsigned_release_device_archive(self) -> None:
        result = self._verify()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("unsigned iPhoneOS xcarchive verified", result.stdout)
        self.assertIn("bundle_id=com.snowzlmbot.hermes.mobile", result.stdout)
        self.assertIn("executable=HermesMobile", result.stdout)

    def test_rejects_non_macho_and_simulator_executables(self) -> None:
        executable = self.app / "HermesMobile"
        executable.write_bytes(b"not a Mach-O executable")
        result = self._verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Mach-O", result.stderr)

        self._write_macho(platform=7)
        result = self._verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("device iOS", result.stderr)

    def test_rejects_signing_and_provisioning_metadata(self) -> None:
        for key, value in (
            ("SigningIdentity", "Apple Development"),
            ("Team", "ABCDE12345"),
            ("ProvisionedDevices", ["device-id"]),
        ):
            with self.subTest(key=key):
                self._write_archive_info(**{key: value})
                result = self._verify()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)
                self._write_archive_info()

    def test_rejects_embedded_signing_material(self) -> None:
        (self.app / "embedded.mobileprovision").write_text("profile")
        result = self._verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("embedded.mobileprovision", result.stderr)

        (self.app / "embedded.mobileprovision").unlink()
        (self.app / "_CodeSignature").mkdir()
        result = self._verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("_CodeSignature", result.stderr)

    def test_rejects_macho_code_signature_command(self) -> None:
        self._write_macho(include_code_signature=True)

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("code signature", result.stderr)

    def test_rejects_release_arbitrary_load_opt_in(self) -> None:
        self._write_app_info(arbitrary_loads=True)

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("arbitrary loads", result.stderr)

    def test_rejects_wrong_bundle_or_executable(self) -> None:
        self._write_app_info(bundle_id="example.invalid", executable="Other")

        result = self._verify()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bundle identifier", result.stderr)

    def _verify(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VERIFIER), str(self.archive)],
            check=False,
            capture_output=True,
            text=True,
        )

    def _write_archive_info(self, **properties: object) -> None:
        application_properties: dict[str, object] = {
            "ApplicationPath": "Applications/HermesMobile.app",
            "CFBundleIdentifier": "com.snowzlmbot.hermes.mobile",
            "CFBundleShortVersionString": "0.1.0",
            "CFBundleVersion": "1",
        }
        application_properties.update(properties)
        self._write_plist(
            self.archive / "Info.plist",
            {
                "ArchiveVersion": 2,
                "Name": "HermesMobile",
                "SchemeName": "HermesMobile",
                "ApplicationProperties": application_properties,
            },
        )

    def _write_app_info(
        self,
        *,
        bundle_id: str = "com.snowzlmbot.hermes.mobile",
        executable: str = "HermesMobile",
        arbitrary_loads: bool = False,
    ) -> None:
        self._write_plist(
            self.app / "Info.plist",
            {
                "CFBundleExecutable": executable,
                "CFBundleIdentifier": bundle_id,
                "CFBundlePackageType": "APPL",
                "HermesAllowsInsecureTransport": False,
                "NSAppTransportSecurity": {
                    "NSAllowsArbitraryLoads": arbitrary_loads
                },
            },
        )

    def _write_macho(
        self, *, platform: int = 2, include_code_signature: bool = False
    ) -> None:
        commands = [struct.pack("<IIIIII", 0x32, 24, platform, 0, 0, 0)]
        if include_code_signature:
            commands.append(struct.pack("<IIII", 0x1D, 16, 0, 0))
        command_data = b"".join(commands)
        header = struct.pack(
            "<IIIIIIII",
            0xFEEDFACF,
            0x0100000C,
            0,
            2,
            len(commands),
            len(command_data),
            0,
            0,
        )
        executable = self.app / "HermesMobile"
        executable.write_bytes(header + command_data)
        executable.chmod(0o755)

    @staticmethod
    def _write_plist(path: Path, value: dict[str, object]) -> None:
        with path.open("wb") as handle:
            plistlib.dump(value, handle)


if __name__ == "__main__":
    unittest.main()
