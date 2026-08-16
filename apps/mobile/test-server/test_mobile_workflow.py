from __future__ import annotations

import unittest
from pathlib import Path

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "mobile.yml"


class MobileWorkflowTests(unittest.TestCase):
    def test_mobile_workflow_builds_both_native_platforms_and_contract(self) -> None:
        workflow = yaml.safe_load(WORKFLOW.read_text())
        self.assertIn("workflow_dispatch", workflow[True])
        self.assertIn("push", workflow[True])
        jobs = workflow["jobs"]
        self.assertEqual(set(jobs), {"contract", "android", "ios"})
        self.assertEqual(jobs["android"]["runs-on"], "ubuntu-24.04")
        self.assertEqual(jobs["ios"]["runs-on"], "macos-26")

        android_commands = "\n".join(
            step.get("run", "") for step in jobs["android"]["steps"]
        )
        self.assertIn("testDebugUnitTest", android_commands)
        self.assertIn("lintDebug", android_commands)
        self.assertIn("assembleDebug", android_commands)

        ios_commands = "\n".join(step.get("run", "") for step in jobs["ios"]["steps"])
        self.assertIn("xcodegen generate", ios_commands)
        self.assertIn("xcodebuild", ios_commands)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", ios_commands)

        uses = [
            step["uses"]
            for job in jobs.values()
            for step in job["steps"]
            if "uses" in step
        ]
        self.assertTrue(uses)
        self.assertTrue(all("@" in action and len(action.rsplit("@", 1)[1]) == 40 for action in uses))


if __name__ == "__main__":
    unittest.main()
