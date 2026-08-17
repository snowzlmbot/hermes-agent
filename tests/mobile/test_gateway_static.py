from __future__ import annotations

import ast
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
GATEWAY = REPOSITORY_ROOT / "apps" / "mobile" / "gateway"
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "mobile-official-compat.yml"
OFFICIAL_COMMIT = "f80f453ae0679347e38abc917c7f94f717bf96c5"


def test_gateway_python_sources_parse_and_removal_has_no_recursive_delete() -> None:
    for name in ("install.py", "sidecar.py", "supervisor.py"):
        source = (GATEWAY / name).read_text(encoding="utf-8")
        ast.parse(source, filename=name)
    installer = (GATEWAY / "install.py").read_text(encoding="utf-8")
    assert "shutil.rmtree" not in installer
    assert "resolve(strict=False)" not in installer
    assert '"websocket_query_parameter": "ticket"' in installer
    assert '"websocket_query_parameter": "token"' not in installer


def test_external_protocol_uses_header_then_ticket_only() -> None:
    sidecar = (GATEWAY / "sidecar.py").read_text(encoding="utf-8")
    mobile_readme = (REPOSITORY_ROOT / "apps" / "mobile" / "README.md").read_text(encoding="utf-8")
    gateway_readme = (GATEWAY / "README.md").read_text(encoding="utf-8")
    android_readme = (REPOSITORY_ROOT / "apps" / "mobile" / "android" / "README.md").read_text(encoding="utf-8")
    assert 'urlencode({"token": self.internal_token})' in sidecar
    assert "self.external_token" not in sidecar.split("def upstream_websocket_url", 1)[1].split("class TicketStore", 1)[0]
    assert "/api/ws?ticket=" in mobile_readme
    assert "/api/ws?ticket=" in gateway_readme
    assert "/api/ws?token=" not in mobile_readme
    assert "/api/ws?token=" not in gateway_readme
    assert "/releases" in mobile_readme
    assert "actions/workflows" not in mobile_readme
    assert "--show-pairing" in android_readme
    assert "--allow-loopback-http" in android_readme


def test_official_workflow_is_fixed_to_tag_commit_and_collects_integration_test() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert "ref: v2026.8.13" in workflow
    assert workflow.count(OFFICIAL_COMMIT) >= 4
    assert "refs/tags/${OFFICIAL_HERMES_TAG}^{commit}" in workflow
    assert "-m integration --collect-only" in workflow
    assert "test_official_gateway_mobile_http_websocket_compatibility" in workflow
