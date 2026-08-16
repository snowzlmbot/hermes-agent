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
            # Creating/removing the one allowed child necessarily changes the
            # parent directory timestamp; content and mode remain protected.
            mtime_ns = 0
        result[relative] = (digest, mode, mtime_ns)
    return result


@pytest.fixture
def installation_fixture(tmp_path: Path) -> dict[str, Any]:
    home = tmp_path / "home"
    hermes_home = tmp_path / "official-hermes-home"
    default_hermes_home = home / ".hermes"
    home.mkdir()
    default_hermes_home.mkdir()
    hermes_home.mkdir()
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
    print("Hermes Agent v0.20.1")
    raise SystemExit(0)
if args == ["serve", "--help"]:
    print("usage: hermes serve [--host HOST] [--port PORT] [--status] [--skip-build]")
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
if args[:1] == ["show"]:
    prop = next((value.split("=", 1)[1] for value in args if value.startswith("--property=")), "")
    values = {
        "LoadState": "loaded" if state.get("unit") else "not-found",
        "FragmentPath": state.get("unit") or "",
        "ActiveState": "active" if state.get("active") else "inactive",
    }
    print(values.get(prop, ""))
    raise SystemExit(0)
if args[:2] == ["is-active", "--quiet"]:
    raise SystemExit(0 if state.get("active") else 3)
if args[:2] == ["is-enabled", "--quiet"]:
    raise SystemExit(0 if state.get("enabled") else 1)
if args[:1] == ["link"]:
    state["unit"] = str(Path(args[-1]).resolve())
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
            "PATH": f"{fake_bin}{os.pathsep}{env.get('PATH', '')}",
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
        "hermes_log": hermes_log,
        "conflict_file": conflict_file,
        "systemctl_state": systemctl_state,
        "systemctl_log": systemctl_log,
        "env": env,
    }


def _run(fixture: dict[str, Any], *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    neutral_cwd = Path(fixture["home"]).parent / "neutral-cwd"
    neutral_cwd.mkdir(exist_ok=True)
    result = subprocess.run(
        [sys.executable, str(INSTALLER), *args],
        cwd=neutral_cwd,
        env=fixture["env"],
        text=True,
        capture_output=True,
        timeout=20,
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


def test_dry_run_install_is_non_mutating_and_reports_loopback_plan(installation_fixture: dict[str, Any]) -> None:
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
    assert "0.0.0.0" not in result.stdout
    assert not (Path(fixture["home"]) / MOBILE_ROOT).exists()
    assert _official_snapshot(fixture) == before


def test_install_status_idempotence_and_no_official_mutation(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    install_args = (
        "install",
        "--hermes",
        str(fixture["hermes"]),
        "--endpoint",
        "https://private.example.test/hermes",
    )

    first = _run(fixture, *install_args)
    install_help = _run(fixture, "install", "--help")
    assert "--token" not in install_help.stdout + install_help.stderr
    mobile_root = Path(fixture["home"]) / MOBILE_ROOT
    token_file = mobile_root / "session.token"
    pairing_file = mobile_root / "pairing.json"
    unit_file = mobile_root / SERVICE_NAME
    launcher = mobile_root / "bin" / "launch"
    token = token_file.read_text(encoding="ascii").strip()
    pairing = json.loads(pairing_file.read_text(encoding="utf-8"))

    assert len(token) == 64
    assert all(character in "0123456789abcdef" for character in token)
    assert token not in first.stdout + first.stderr
    assert stat.S_IMODE(token_file.stat().st_mode) == 0o600
    assert stat.S_IMODE(pairing_file.stat().st_mode) == 0o600
    assert stat.S_IMODE(mobile_root.stat().st_mode) == 0o700
    assert pairing["endpoint"] == "https://private.example.test/hermes"
    assert pairing["websocket_url"] == "wss://private.example.test/hermes/api/ws"
    assert pairing["auth"]["mode"] == "session_token"
    assert pairing["auth"]["header"] == TOKEN_HEADER
    assert pairing["auth"]["token"] == token
    assert pairing["native_oauth"]["optional"] is True

    unit = unit_file.read_text(encoding="utf-8")
    launch = launcher.read_text(encoding="utf-8")
    assert "127.0.0.1" in launch
    assert "--port 9119" in launch
    assert "0.0.0.0" not in unit + launch
    assert str(Path(fixture["hermes"]).resolve()) in launch
    assert "unset PYTHONPATH PYTHONHOME" in launch
    assert token not in unit + launch
    assert "Restart=no" in unit

    installed_snapshot = _snapshot(mobile_root)
    second = _run(fixture, *install_args)
    assert "already installed" in second.stdout.lower()
    assert _snapshot(mobile_root) == installed_snapshot

    status = _run(fixture, "status", "--json")
    status_payload = json.loads(status.stdout)
    assert status_payload["installed"] is True
    assert status_payload["bind"] == "127.0.0.1"
    assert status_payload["port"] == 9119
    assert status_payload["service_active"] is True
    assert "token" not in status.stdout.lower()
    assert token not in status.stdout + status.stderr

    commands = [json.loads(line) for line in Path(fixture["hermes_log"]).read_text().splitlines()]
    status_commands = [entry for entry in commands if entry["argv"] == ["serve", "--status"]]
    capability_commands = [entry for entry in commands if entry["argv"] != ["serve", "--status"]]
    assert status_commands
    assert all(entry["pythonpath"] is None for entry in commands)
    assert all(entry["pythonhome"] is None for entry in commands)
    assert all(entry["home"] == str(fixture["home"]) for entry in status_commands)
    assert all(entry["hermes_home"] == str(fixture["hermes_home"]) for entry in status_commands)
    assert all(Path(entry["home"]).name.startswith("hermes-mobile-probe-") for entry in capability_commands)
    assert all(
        Path(entry["hermes_home"]) == Path(entry["home"]) / ".hermes" for entry in capability_commands
    )
    assert _official_snapshot(fixture) == before


def test_conflicts_fail_closed_before_install(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    mobile_root = Path(fixture["home"]) / MOBILE_ROOT

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
    assert "existing hermes serve" in running.stderr.lower()
    assert not mobile_root.exists()
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
            "--port",
            str(port),
            "--no-activate",
            check=False,
        )
    assert blocked.returncode != 0
    assert "port" in blocked.stderr.lower()
    assert not mobile_root.exists()
    assert _official_snapshot(fixture) == before


def test_cleartext_endpoint_and_foreign_service_are_rejected(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    mobile_root = Path(fixture["home"]) / MOBILE_ROOT
    insecure = _run(
        fixture,
        "install",
        "--hermes",
        str(fixture["hermes"]),
        "--endpoint",
        "http://192.0.2.10:9119",
        "--no-activate",
        check=False,
    )
    assert insecure.returncode != 0
    assert "https" in insecure.stderr.lower()
    assert not mobile_root.exists()

    foreign_unit = Path(fixture["home"]).parent / "foreign.service"
    Path(fixture["systemctl_state"]).write_text(
        json.dumps({"unit": str(foreign_unit), "active": False, "enabled": False}),
        encoding="utf-8",
    )
    foreign = _run(
        fixture,
        "install",
        "--hermes",
        str(fixture["hermes"]),
        check=False,
    )
    assert foreign.returncode != 0
    assert "existing user service" in foreign.stderr.lower()
    assert not mobile_root.exists()


def test_uninstall_never_stops_service_and_rollback_is_dry_run_testable(installation_fixture: dict[str, Any]) -> None:
    fixture = installation_fixture
    before = _official_snapshot(fixture)
    mobile_root = Path(fixture["home"]) / MOBILE_ROOT
    _run(fixture, "install", "--hermes", str(fixture["hermes"]))
    token = (mobile_root / "session.token").read_text(encoding="ascii").strip()

    systemctl_log = Path(fixture["systemctl_log"])
    before_refusal = systemctl_log.read_text(encoding="utf-8").splitlines()
    refused = _run(fixture, "uninstall", "--yes", check=False)
    assert refused.returncode != 0
    assert "stop" in refused.stderr.lower()
    assert mobile_root.exists()
    refusal_commands = [json.loads(line) for line in systemctl_log.read_text(encoding="utf-8").splitlines()[len(before_refusal) :]]
    assert not any(command[:1] in (["stop"], ["restart"], ["disable"]) for command in refusal_commands)

    state = json.loads(Path(fixture["systemctl_state"]).read_text(encoding="utf-8"))
    state["active"] = False
    Path(fixture["systemctl_state"]).write_text(json.dumps(state), encoding="utf-8")
    dry_run = _run(fixture, "rollback", "--dry-run")
    assert "remove" in dry_run.stdout.lower()
    assert mobile_root.exists()
    assert token not in dry_run.stdout + dry_run.stderr

    _run(fixture, "rollback", "--yes")
    assert not mobile_root.exists()
    assert _official_snapshot(fixture) == before

    _run(fixture, "install", "--hermes", str(fixture["hermes"]), "--no-activate")
    uninstall_dry_run = _run(fixture, "uninstall", "--dry-run")
    assert mobile_root.exists()
    assert "remove" in uninstall_dry_run.stdout.lower()
    _run(fixture, "uninstall", "--yes")
    assert not mobile_root.exists()
    assert _official_snapshot(fixture) == before
