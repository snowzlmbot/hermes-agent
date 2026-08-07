from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class MobileVerificationScriptTests(unittest.TestCase):
    def test_contract_only_mode_runs_the_protocol_suite_and_self_test(self) -> None:
        result = subprocess.run(
            ["bash", "apps/mobile/scripts/verify.sh", "--contract-only"],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        combined = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, combined)
        self.assertIn("CONTRACT_OK", combined)
        self.assertIn('"status": "ok"', combined)


if __name__ == "__main__":
    unittest.main()
