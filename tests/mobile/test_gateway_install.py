from __future__ import annotations

import hashlib
import json
import os
import socket
import stat
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
INSTALLER = REPOSITORY_ROOT / "apps" / "mobile" / "gateway" / "install.py"
MOBILE_ROOT = Path(".hermes/mobile-gateway")
PACKAGE_ID = "hermes.mobile.sidecar"
TOKEN_HEADER = "X-Hermes-Session-Token"
SERVICE_NAME = "hermes-mobile-gateway.service"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def _snapshot(path: Path, *, exclude: Path | None = None) -> dict[str, tuple[str, int, int]]:
    result: dict[str, tuple[str, int, int]] = {}
    if not path.exists():
        return result
    for candidate in sorted((path, *path.rglob("*"))):
        if exclude is not None and (candidate == exclude or exclude in candidate.parents):
            continue
        relative = str(candidate.relative_to(path)) or "."
        metadata = candidate.lstat()
        mode = stat.S_IMODE(metadata.st_mode)
        if candidate.is_symlink():
            digest = f"symlink:{os.readlink(candidate)}"
            mtime_ns = metadata.st_mtime_ns
        elif candidate.is_file():
            digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
            mtime_ns = metadata.st_mtime_ns
        else:
            digest = "directory"
            mtime_ns = 0
        result[relative] = (digest, mode, mtime_ns)
    return result


@pytest.fixture
def installation_fixture(tmp_path: Path) -> dict[str, Any]:
    home = tmp_path / "home"
    hermes_home = tmp_path / "official-hermes-home"
    default_hermes_home = home / ".hermes"
    home.mkdir(mode=0o700)
    default_hermes_home.mkdir(mode=0o700)
    hermes_home.mkdir(mode=0o700)
    official_files = {
        default_hermes_home / "config.yaml": "default-home-state: unchanged\n",
        hermes_home / ".env": "NOUS_API_KEY=official-unchanged\n",
        hermes_home / "config.yaml": "model: official/unchanged\n",
        hermes_home / "state.db": "official-state-sentinel\n",
        hermes_home / "profiles" / "default" / "config.yaml": "profile: unchanged\n",
    }
    for path, value in official_files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")

    fake_root = tmp_path / "official-install"
    fake_bin = fake_root / "bin"
    fake_package = fake_root / "site-packages" / "hermes_cli"
    fake_bin.mkdir(parents=True)
    fake_package.mkdir(parents=True)
    (fake_package / "__init__.py").write_text('__version__ = "0.20.1"\n', encoding="utf-8")
    fake_python = fake_bin / "python"
    _write_executable(
        fake_python,
        f"""#!/usr/bin/env python3
import os
import subprocess
import sys
if any("sidecar-dependencies-ok" in argument for argument in sys.argv):
    print("sidecar-dependencies-ok")
    raise SystemExit(0)
raise SystemExit(subprocess.call([{sys.executable!r}, *sys.argv[1:]]))
""",
    )

    hermes_log = tmp_path / "hermes-commands.jsonl"
    conflict_file = tmp_path / "hermes-conflict"
    hermes = fake_bin / "hermes"
    _write_executable(
        hermes,
        """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

log = Path(os.environ["FAKE_HERMES_LOG"])
with log.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps({"argv": sys.argv[1:], "home": os.environ.get("HOME"), "hermes_home": os.environ.get("HERMES_HOME"), "pythonpath": os.environ.get("PYTHONPATH"), "pythonhome": os.environ.get("PYTHONHOME")}) + "\\n")
args = sys.argv[1:]
if args in (["version"], ["--version"]):
    print("Hermes Agent v0.20.1 (2026.8.13)")
    raise SystemExit(0)
if args == ["serve", "--help"]:
    print("usage: hermes serve [--host HOST] [--port PORT] [--status]")
    raise SystemExit(0)
if args == ["serve", "--status"]:
    conflict = Path(os.environ["FAKE_HERMES_CONFLICT"])
    current_home = os.environ.get("HOME") == os.environ["FAKE_CURRENT_HOME"]
    current_hermes_home = os.environ.get("HERMES_HOME") == os.environ["FAKE_CURRENT_HERMES_HOME"]
    if conflict.exists() and current_home and current_hermes_home:
        print("1 hermes dashboard process(es) running:")
        print("    PID 4242: hermes serve --host 127.0.0.1 --port 9119")
    else:
        print("No hermes dashboard processes running.")
    raise SystemExit(0)
print("unexpected fake hermes invocation", file=sys.stderr)
raise SystemExit(64)
""",
    )

    systemctl_state = tmp_path / "systemctl-state.json"
    systemctl_log = tmp_path / "systemctl-commands.jsonl"
    systemctl = fake_bin / "systemctl"
    _write_executable(
        systemctl,
        """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

state_path = Path(os.environ["FAKE_SYSTEMCTL_STATE"])
log_path = Path(os.environ["FAKE_SYSTEMCTL_LOG"])
state = json.loads(state_path.read_text()) if state_path.exists() else {"unit": None, "active": False, "enabled": False}
args = sys.argv[1:]
with log_path.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(args) + "\\n")
if args and args[0] == "--user":
    args = args[1:]
if state.get("query_error") and args[:1] == ["show"]:
    print("Failed to connect to bus", file=sys.stderr)
    raise SystemExit(1)
if args[:1] == ["show"]:
    if state.get("unit"):
        print("LoadState=loaded")
        print(f"FragmentPath={state['unit']}")
        print(f"ActiveState={'active' if state.get('active') else 'inactive'}")
        print(f"UnitFileState={'enabled' if state.get('enabled') else 'linked'}")
    else:
        print("LoadState=not-found")
        print("FragmentPath=")
        print("ActiveState=inactive")
        print("UnitFileState=disabled")
    raise SystemExit(0)
if args[:1] == ["link"]:
    state["unit"] = args[-1]
elif args[:1] == ["daemon-reload"]:
    pass
elif args[:2] == ["enable", "--now"]:
    state["enabled"] = True
    state["active"] = True
elif args[:1] == ["disable"]:
    state["enabled"] = False
elif args[:1] == ["unlink"]:
    state["unit"] = None
    state["enabled"] = False
else:
    print(f"unexpected fake systemctl invocation: {args}", file=sys.stderr)
    raise SystemExit(64)
state_path.write_text(json.dumps(state), encoding="utf-8")
""",
    )

    env = os.environ.copy()
    env.update(
        {
            "HOME": str(home),
            "HERMES_HOME": str(hermes_home),
            "PATH": f"{fake_bin}{os.pathsep}{Path(sys.executable).parent}{os.pathsep}{env.get('PATH', '')}",
            "FAKE_HERMES_LOG": str(hermes_log),
            "FAKE_HERMES_CONFLICT": str(conflict_file),
            "FAKE_CURRENT_HOME": str(home),
            "FAKE_CURRENT_HERMES_HOME": str(hermes_home),
            "FAKE_SYSTEMCTL_STATE": str(systemctl_state),
            "FAKE_SYSTEMCTL_LOG": str(systemctl_log),
            "PYTHONPATH": str(REPOSITORY_ROOT),
            "PYTHONHOME": sys.base_prefix,
        }
    )
    return {
        "home": home,
        "default_hermes_home": default_hermes_home,
        "hermes_home": hermes_home,
        "hermes": hermes,
        "fake_root": fake_root,
        "fake_bin": fake_bin,
        "fake_python": fake_python,
        "hermes_log": hermes_log,
        "conflict_file": conflict_file,
        "systemctl_state": systemctl_state,
        "systemctl_log": systemctl_log,
        "env": env,
    }


def _run(fixture: dict[str, Any], *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    neutral_cwd = Path(fixture["home"]).parent / "neutral-cwd"
    neutral_cwd.mkdir(exist_ok=True)
    expanded = list(args)
    if expanded and expanded[0] in {"install", "probe"} and "--python" not in expanded:
        expanded.extend(["--python", str(fixture["fake_python"])])
    result = subprocess.run(
        [sys.executable, str(INSTALLER), *expanded],
        cwd=neutral_cwd,
        env=fixture["env"],
        text=True,
        capture_output=True,
        timeout=30,
    )
    if check and result.returncode != 0:
        raise AssertionError(f"command failed ({result.returncode}): stdout={result.stdout!r} stderr={result.stderr!r}")
    return result


def _official_snapshot(
    fixture: dict[str, Any],
) -> tuple[
    dict[str, tuple[str, int, int]],
    dict[str, tuple[str, int, int]],
    dict[str, tuple[str, int, int]],
]:
    mobile_root = Path(fixture["home"]) / MOBILE_ROOT
    return (
        _snapshot(Path(fixture["default_hermes_home"]), exclude=mobile_root),
        _snapshot(Path(fixture["hermes_home"]), exclude=mobile_root),
        _snapshot(Path(fixture["fake_root"])),
    )


def _set_service_inactive(fixture: dict[str, Any]) -> None:
    state_path = Path(fixture["systemctl_state"])
    state = json.loads(state_path.read_text(encoding="utf-8"))
    state["active"] = False
    state_path.write_text(json.dumps(state), encoding="utf-8")


def test_dry_run_is_non_mutating_and_reports_two_loopback_hops(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    result = _run(
        fixture,
        "install",
        "--dry-run",
        "--hermes",
        str(fixture["hermes"]),
        "--endpoint",
        "https://private.example.test/hermes",
    )
    assert "127.0.0.1:9119" in result.stdout
    assert "127.0.0.1:9120" in result.stdout
    assert "0.0.0.0" not in result.stdout
    assert not (Path(fixture["home"]) / MOBILE_ROOT).exists()
    assert _official_snapshot(fixture) == before


def test_install_inventory_pairing_idempotence_and_no_official_mutation(
    installation_fixture: dict[str, Any],
) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    args = (
        "install",
        "--hermes",
        str(fixture["hermes"]),
        "--endpoint",
        "https://private.example.test/hermes",
    )
    first = _run(fixture, *args)
    root = Path(fixture["home"]) / MOBILE_ROOT
    external = (root / "external.token").read_text(encoding="ascii").strip()
    internal = (root / "internal.token").read_text(encoding="ascii").strip()
    pairing = json.loads((root / "pairing.json").read_text(encoding="utf-8"))
    manifest = json.loads((root / "install.json").read_text(encoding="utf-8"))

    assert len(external) == len(internal) == 64
    assert external != internal
    assert external not in first.stdout + first.stderr
    assert internal not in first.stdout + first.stderr
    assert pairing["endpoint"] == "https://private.example.test/hermes"
    assert pairing["websocket_url"] == "wss://private.example.test/hermes/api/ws"
    assert "?" not in pairing["websocket_url"]
    assert pairing["auth"] == {
        "mode": "session_token",
        "header": TOKEN_HEADER,
        "token": external,
        "ws_ticket_path": "/api/auth/ws-ticket",
        "websocket_query_parameter": "ticket",
        "ticket_ttl_seconds": 30,
    }
    assert manifest["package_id"] == PACKAGE_ID
    assert len(manifest["installation_id"]) == 32
    assert manifest["managed_root"] == str(root)
    assert set(manifest["inventory"]) == {
        "bin",
        "bin/manage",
        "bin/launch",
        "bin/sidecar.py",
        "bin/supervisor.py",
        "external.token",
        "internal.token",
        "pairing.json",
        "runtime.json",
        "capabilities.json",
        "rollback.json",
        SERVICE_NAME,
        "install.json",
    }
    for relative, entry in manifest["inventory"].items():
        path = root / relative
        assert stat.S_IMODE(path.lstat().st_mode) == int(entry["mode"], 8)
        if relative != "install.json" and entry["type"] == "file":
            assert entry["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()

    unit = (root / SERVICE_NAME).read_text(encoding="utf-8")
    launch = (root / "bin/launch").read_text(encoding="utf-8")
    assert "%h" not in unit
    assert str(root / "bin/launch") in unit
    assert "--upstream-port 9119" in launch
    assert "--sidecar-port 9120" in launch
    assert external not in unit + launch
    assert internal not in unit + launch
    assert "Restart=no" in unit

    installed_snapshot = _snapshot(root)
    second = _run(fixture, *args)
    assert "already installed" in second.stdout.lower()
    assert _snapshot(root) == installed_snapshot

    status = _run(fixture, "status", "--json")
    payload = json.loads(status.stdout)
    assert payload["installed"] is True
    assert payload["service_state"] == "active"
    assert payload["upstream_port"] == 9119
    assert payload["sidecar_port"] == 9120
    assert external not in status.stdout + status.stderr
    assert internal not in status.stdout + status.stderr
    assert "token" not in status.stdout.lower()
    assert _official_snapshot(fixture) == before


def test_existing_process_ports_and_foreign_service_fail_before_mutation(
    installation_fixture: dict[str, Any],
) -> None:
    fixture = installation_fixture
    root = Path(fixture["home"]) / MOBILE_ROOT
    before = _official_snapshot(fixture)
    Path(fixture["conflict_file"]).write_text("running", encoding="utf-8")
    running = _run(
        fixture,
        "install",
        "--hermes",
        str(fixture["hermes"]),
        "--no-activate",
        check=False,
    )
    assert running.returncode != 0
    assert "existing hermes" in running.stderr.lower()
    assert not root.exists()
    Path(fixture["conflict_file"]).unlink()

    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        port = occupied.getsockname()[1]
        blocked = _run(
            fixture,
            "install",
            "--hermes",
            str(fixture["hermes"]),
            "--sidecar-port",
            str(port),
            "--no-activate",
            check=False,
        )
    assert blocked.returncode != 0
    assert "sidecar loopback port" in blocked.stderr.lower()
    assert not root.exists()

    foreign_unit = Path(fixture["home"]).parent / "foreign.service"
    Path(fixture["systemctl_state"]).write_text(
        json.dumps({"unit": str(foreign_unit), "active": False, "enabled": False}),
        encoding="utf-8",
    )
    foreign = _run(fixture, "install", "--hermes", str(fixture["hermes"]), check=False)
    assert foreign.returncode != 0
    assert "exact package-owned unit" in foreign.stderr.lower()
    assert not root.exists()
    assert _official_snapshot(fixture) == before


def test_status_fails_before_reading_insecure_or_linked_inventory(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    _run(fixture, "install", "--hermes", str(fixture["hermes"]), "--no-activate")
    root = Path(fixture["home"]) / MOBILE_ROOT
    pairing = root / "pairing.json"
    external = (root / "external.token").read_text(encoding="ascii").strip()

    pairing.write_bytes(b"not-json-and-must-not-be-read")
    pairing.chmod(0o644)
    insecure = _run(fixture, "status", "--json", check=False)
    assert insecure.returncode != 0
    assert "permissions must be 0600" in insecure.stderr
    assert "cannot read pairing" not in insecure.stderr.lower()
    assert external not in insecure.stdout + insecure.stderr

    pairing.unlink()
    pairing.symlink_to(root / "runtime.json")
    linked = _run(fixture, "status", "--json", check=False)
    assert linked.returncode != 0
    assert "not a symlink" in linked.stderr.lower()


def test_uninstall_requires_known_inactive_exact_service_and_verified_inventory(
    installation_fixture: dict[str, Any],
) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    root = Path(fixture["home"]) / MOBILE_ROOT
    _run(fixture, "install", "--hermes", str(fixture["hermes"]))
    external = (root / "external.token").read_text(encoding="ascii").strip()

    log_path = Path(fixture["systemctl_log"])
    before_lines = log_path.read_text(encoding="utf-8").splitlines()
    active = _run(fixture, "uninstall", "--yes", check=False)
    assert active.returncode != 0
    assert "never stops" in active.stderr.lower()
    new_commands = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()[len(before_lines) :]]
    assert not any(command[1:2] in (["stop"], ["restart"], ["disable"]) for command in new_commands)
    assert root.exists()

    _set_service_inactive(fixture)
    original_manifest = (root / "install.json").read_bytes()
    manifest = json.loads(original_manifest)
    manifest["installation_id"] = "wrong-installation"
    (root / "install.json").write_text(json.dumps(manifest), encoding="utf-8")
    invalid_id = _run(fixture, "uninstall", "--yes", check=False)
    assert invalid_id.returncode != 0
    assert "installation" in invalid_id.stderr.lower()
    assert root.exists()
    (root / "install.json").write_bytes(original_manifest)
    (root / "install.json").chmod(0o600)

    extra = root / "foreign.txt"
    extra.write_text("not owned by the package", encoding="utf-8")
    extra.chmod(0o600)
    extra_file = _run(fixture, "uninstall", "--yes", check=False)
    assert extra_file.returncode != 0
    assert "inventory differs" in extra_file.stderr.lower()
    assert extra.exists()
    extra.unlink()

    state_path = Path(fixture["systemctl_state"])
    state = json.loads(state_path.read_text(encoding="utf-8"))
    state["query_error"] = True
    state_path.write_text(json.dumps(state), encoding="utf-8")
    unknown = _run(fixture, "uninstall", "--yes", check=False)
    assert unknown.returncode != 0
    assert "unknown" in unknown.stderr.lower()
    assert root.exists()
    state.pop("query_error")
    state_path.write_text(json.dumps(state), encoding="utf-8")

    dry_run = _run(fixture, "rollback", "--dry-run")
    assert root.exists()
    assert external not in dry_run.stdout + dry_run.stderr
    removed = _run(fixture, "uninstall", "--yes")
    assert "verified inventory" in removed.stdout.lower()
    assert not root.exists()
    assert _official_snapshot(fixture) == before


def test_systemctl_unavailable_is_unknown_and_blocks_activate_and_remove(
    installation_fixture: dict[str, Any],
) -> None:
    fixture = installation_fixture
    no_systemctl_bin = Path(fixture["home"]).parent / "no-systemctl-bin"
    no_systemctl_bin.mkdir(mode=0o700)
    (no_systemctl_bin / "python3").symlink_to("/usr/bin/python3")
    fixture["env"]["PATH"] = str(no_systemctl_bin)
    installed = _run(
        fixture,
        "install",
        "--hermes",
        str(fixture["hermes"]),
        "--no-activate",
    )
    assert installed.returncode == 0
    root = Path(fixture["home"]) / MOBILE_ROOT
    status = json.loads(_run(fixture, "status", "--json").stdout)
    assert status["service_state"] == "unknown"
    assert status["service_active"] is None

    activate = _run(fixture, "install", "--hermes", str(fixture["hermes"]), check=False)
    assert activate.returncode != 0
    assert "unknown" in activate.stderr.lower()
    remove = _run(fixture, "uninstall", "--yes", check=False)
    assert remove.returncode != 0
    assert "unknown" in remove.stderr.lower()
    assert root.exists()
