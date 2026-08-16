from __future__ import annotations

import unittest
from pathlib import Path
from typing import Any

import yaml


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = REPOSITORY_ROOT / ".github" / "workflows"
IOS_WORKFLOW = WORKFLOWS / "mobile.yml"
ANDROID_WORKFLOW = WORKFLOWS / "mobile-android.yml"
CONTRACT_WORKFLOW = WORKFLOWS / "mobile-contract.yml"


def load_workflow(path: Path) -> dict[Any, Any]:
    return yaml.safe_load(path.read_text())


class MobileWorkflowTests(unittest.TestCase):
    def setUp(self) -> None:
        self.ios = load_workflow(IOS_WORKFLOW)
        self.android = load_workflow(ANDROID_WORKFLOW)
        self.contract = load_workflow(CONTRACT_WORKFLOW)

    def test_split_mobile_workflows_have_required_lanes(self) -> None:
        for workflow in (self.ios, self.android, self.contract):
            self.assertIn("workflow_dispatch", workflow[True])
            self.assertIn("push", workflow[True])

        self.assertIn("ios", self.ios["jobs"])
        self.assertIn("ios_archive", self.ios["jobs"])
        self.assertIn("verify", self.android["jobs"])
        self.assertIn("contract", self.contract["jobs"])
        self.assertEqual(self.ios["jobs"]["ios"]["runs-on"], "macos-26")
        archive_job = self.ios["jobs"]["ios_archive"]
        self.assertEqual(archive_job["runs-on"], "macos-26")
        self.assertNotIn("needs", archive_job)

    def test_ios_unsigned_release_archive_command_contract(self) -> None:
        archive_job = self.ios["jobs"]["ios_archive"]
        archive_step = next(
            step
            for step in archive_job["steps"]
            if step.get("name") == "Create unsigned iPhoneOS archive"
        )
        command = archive_step["run"]

        self.assertIn("-configuration Release", command)
        self.assertIn("-sdk iphoneos", command)
        self.assertIn("-destination 'generic/platform=iOS'", command)
        self.assertIn(
            '-archivePath "$RUNNER_TEMP/HermesMobile-${{ github.sha }}.xcarchive"',
            command,
        )
        self.assertIn("CODE_SIGNING_ALLOWED=NO", command)
        self.assertIn("CODE_SIGNING_REQUIRED=NO", command)
        self.assertIn('CODE_SIGN_IDENTITY=""', command)
        self.assertTrue(command.rstrip().endswith("archive"))
        self.assertNotIn("iphonesimulator", command)

    def test_ios_unsigned_release_archive_is_verified_and_uploaded(self) -> None:
        steps = self.ios["jobs"]["ios_archive"]["steps"]
        verification = next(
            step for step in steps if step.get("name") == "Verify unsigned iOS archive"
        )
        verification_command = verification["run"]
        self.assertIn("verify_ios_xcarchive.py", verification_command)
        self.assertIn("xcrun vtool -show-build", verification_command)
        self.assertIn("HermesMobile-${{ github.sha }}.xcarchive", verification_command)
        self.assertIn(
            "HermesMobile-${{ github.sha }}-xcarchive-verification.txt",
            verification_command,
        )

        upload = next(
            step for step in steps if step.get("name") == "Upload unsigned iOS archive"
        )
        self.assertLess(steps.index(verification), steps.index(upload))
        self.assertEqual(
            upload["with"]["name"], "hermes-mobile-ios-archive-${{ github.sha }}"
        )
        upload_paths = upload["with"]["path"]
        self.assertIn("HermesMobile-${{ github.sha }}.xcarchive", upload_paths)
        self.assertIn(
            "HermesMobile-${{ github.sha }}-xcarchive-verification.txt",
            upload_paths,
        )
        self.assertEqual(upload["with"]["if-no-files-found"], "error")

    def test_mobile_contract_executes_workflow_and_verifier_tests(self) -> None:
        steps = self.contract["jobs"]["contract"]["steps"]
        commands = "\n".join(step.get("run", "") for step in steps)
        self.assertIn("pyyaml==6.0.3", commands)
        self.assertIn("test_mobile_workflow.py", commands)
        self.assertIn("test_verify_ios_xcarchive.py", commands)

    def test_mobile_workflow_actions_are_commit_pinned(self) -> None:
        uses = [
            step["uses"]
            for workflow in (self.ios, self.android, self.contract)
            for job in workflow["jobs"].values()
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
