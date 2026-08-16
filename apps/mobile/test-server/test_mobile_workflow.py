from __future__ import annotations

import unittest
from pathlib import Path

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "mobile.yml"


class MobileWorkflowTests(unittest.TestCase):
    def test_mobile_workflow_tests_simulator_and_archives_generic_ios(self) -> None:
        workflow = yaml.safe_load(WORKFLOW.read_text())
        self.assertIn("workflow_dispatch", workflow[True])
        self.assertIn("push", workflow[True])
        jobs = workflow["jobs"]
        self.assertIn("ios", jobs)
        self.assertIn("ios_archive", jobs)
        self.assertEqual(jobs["ios"]["runs-on"], "macos-26")
        self.assertEqual(jobs["ios_archive"]["runs-on"], "macos-26")
        self.assertNotIn("needs", jobs["ios_archive"])

        ios_commands = "\n".join(
            step.get("run", "") for step in jobs["ios"]["steps"]
        )
        self.assertIn("xcodegen generate", ios_commands)
        self.assertIn("xcodebuild", ios_commands)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", ios_commands)

        archive_steps = jobs["ios_archive"]["steps"]
        archive = next(
            step
            for step in archive_steps
            if step.get("name") == "Create unsigned generic iOS archive"
        )
        archive_command = archive["run"]
        self.assertIn("-configuration Release", archive_command)
        self.assertIn("-destination 'generic/platform=iOS'", archive_command)
        self.assertIn(
            "-archivePath \"$RUNNER_TEMP/HermesMobile.xcarchive\"",
            archive_command,
        )
        self.assertIn("CODE_SIGNING_ALLOWED=NO", archive_command)
        self.assertIn("CODE_SIGNING_REQUIRED=NO", archive_command)
        self.assertTrue(archive_command.rstrip().endswith("archive"))

        upload = next(
            step
            for step in archive_steps
            if step.get("name") == "Upload unsigned iOS archive"
        )
        verifier_index = next(
            index
            for index, step in enumerate(archive_steps)
            if step.get("name") == "Verify unsigned iOS archive artifact"
            and "verify_ios_xcarchive.py" in step.get("run", "")
        )
        upload_index = archive_steps.index(upload)
        self.assertLess(verifier_index, upload_index)
        self.assertNotIn("if", upload)
        self.assertEqual(upload["with"]["if-no-files-found"], "error")
        self.assertEqual(
            upload["with"]["path"], "${{ runner.temp }}/HermesMobile.xcarchive"
        )

        uses = [
            step["uses"]
            for job in jobs.values()
            for step in job["steps"]
            if "uses" in step
        ]
        self.assertTrue(uses)
        self.assertTrue(
            all(
                "@" in action and len(action.rsplit("@", 1)[1]) == 40
                for action in uses
            )
        )


if __name__ == "__main__":
    unittest.main()
