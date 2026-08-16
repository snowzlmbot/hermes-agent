#!/usr/bin/env python3
"""Install an additive loopback gateway service for the Hermes mobile client.

This manager never installs, upgrades, imports, or edits the official Hermes
package.  Its managed files are confined to ~/.hermes/mobile-gateway.  The
service executes the already-installed ``hermes serve`` command.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, NoReturn
from urllib.parse import urlsplit, urlunsplit


INSTALLER_VERSION = "1.0.0"
MINIMUM_HERMES_VERSION = (0, 20, 1)
DEFAULT_PORT = 9119
BIND_HOST = "127.0.0.1"
SERVICE_NAME = "hermes-mobile-gateway.service"
TOKEN_HEADER = "X-Hermes-Session-Token"
TOKEN_RE = re.compile(r"[0-9a-f]{64}")
VERSION_RE = re.compile(r"(?<!\d)v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)")
NO_RUNNING_SERVER = "No hermes dashboard processes running."
SECRET_MODE = 0o600
EXECUTABLE_MODE = 0o700
DIRECTORY_MODE = 0o700


class InstallError(RuntimeError):
    """An expected, user-actionable compatibility or safety failure."""


def _fail(message: str) -> NoReturn:
    raise InstallError(message)


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _mobile_root() -> Path:
    home = os.environ.get("HOME", "").strip()
    if not home:
        _fail("HOME is not set; refusing to choose an installation directory")
    return Path(home).expanduser() / ".hermes" / "mobile-gateway"


def _source_bytes() -> bytes:
    return Path(__file__).resolve().read_bytes()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _ensure_regular(path: Path, *, label: str) -> None:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        _fail(f"{label} must be an owner-controlled regular file: {path}")
    if metadata.st_uid != os.getuid():
        _fail(f"{label} is not owned by the current user: {path}")


def _ensure_managed_root(root: Path, *, allow_absent: bool = True) -> None:
    try:
        metadata = root.lstat()
    except FileNotFoundError:
        if allow_absent:
            return
        _fail(f"mobile gateway is not installed at {root}")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail(f"managed root must be an owner-controlled directory, not a link: {root}")
    if metadata.st_uid != os.getuid():
        _fail(f"managed root is not owned by the current user: {root}")
    manifest = root / "install.json"
    if not manifest.exists() and any(root.iterdir()):
        _fail(f"refusing to overwrite unmanaged content in {root}")
    _ensure_regular(manifest, label="install manifest")


def _mkdir_managed(path: Path) -> None:
    if path.exists():
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            _fail(f"managed directory path is not a directory: {path}")
        if metadata.st_uid != os.getuid():
            _fail(f"managed directory is not owned by the current user: {path}")
        if stat.S_IMODE(metadata.st_mode) != DIRECTORY_MODE:
            path.chmod(DIRECTORY_MODE)
        return
    path.mkdir(mode=DIRECTORY_MODE, parents=False)


def _atomic_write(path: Path, content: bytes, mode: int) -> bool:
    _ensure_regular(path, label="managed file")
    if path.exists():
        current_mode = stat.S_IMODE(path.stat().st_mode)
        if path.read_bytes() == content:
            if current_mode != mode:
                path.chmod(mode)
                return True
            return False
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
        path.chmod(mode)
        directory_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass
    return True


def _write_json(path: Path, payload: dict[str, Any], mode: int = SECRET_MODE) -> bool:
    content = (json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8")
    return _atomic_write(path, content, mode)


def _read_json(path: Path, *, label: str) -> dict[str, Any]:
    _ensure_regular(path, label=label)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        _fail(f"cannot read {label} at {path}: {error}")
    if not isinstance(value, dict):
        _fail(f"{label} must contain a JSON object: {path}")
    return value


def _resolve_command(value: str | None) -> Path:
    if value:
        candidate = Path(value).expanduser()
        if not candidate.is_absolute() and os.sep not in value:
            located = shutil.which(value)
            if located is None:
                _fail(f"Hermes command is not on PATH: {value}")
            candidate = Path(located)
    else:
        located = shutil.which("hermes")
        if located is None:
            _fail("official Hermes command not found on PATH; pass --hermes /absolute/path/to/hermes")
        candidate = Path(located)
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        _fail(f"cannot resolve Hermes command {candidate}: {error}")
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        _fail(f"Hermes command is not an executable file: {resolved}")
    return resolved


def _sanitized_hermes_environment() -> dict[str, str]:
    env = os.environ.copy()
    env.update(
        {
            "PYTHONDONTWRITEBYTECODE": "1",
            "OPENROUTER_API_KEY": "",
            "OPENAI_API_KEY": "",
            "NOUS_API_KEY": "",
        }
    )
    env.pop("PYTHONPATH", None)
    env.pop("PYTHONHOME", None)
    return env


def _probe_environment() -> tuple[tempfile.TemporaryDirectory[str], dict[str, str]]:
    temporary = tempfile.TemporaryDirectory(prefix="hermes-mobile-probe-")
    probe_home = Path(temporary.name)
    env = _sanitized_hermes_environment()
    env.update({"HOME": str(probe_home), "HERMES_HOME": str(probe_home / ".hermes")})
    return temporary, env


def _run_hermes(command: Path, arguments: list[str], *, timeout: int = 20) -> subprocess.CompletedProcess[str]:
    temporary, env = _probe_environment()
    try:
        return subprocess.run(
            [str(command), *arguments],
            cwd=temporary.name,
            env=env,
            text=True,
            capture_output=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        _fail(f"Hermes capability probe timed out: {shlex.join([str(command), *arguments])}")
    except OSError as error:
        _fail(f"Hermes capability probe failed: {error}")
    finally:
        temporary.cleanup()


def _run_hermes_current_home_status(command: Path) -> subprocess.CompletedProcess[str]:
    env = _sanitized_hermes_environment()
    with tempfile.TemporaryDirectory(prefix="hermes-mobile-status-cwd-") as neutral_cwd:
        try:
            return subprocess.run(
                [str(command), "serve", "--status"],
                cwd=neutral_cwd,
                env=env,
                text=True,
                capture_output=True,
                timeout=20,
            )
        except subprocess.TimeoutExpired:
            _fail("Hermes status probe timed out")
        except OSError as error:
            _fail(f"Hermes status probe failed: {error}")


def _parse_version(output: str) -> tuple[str, tuple[int, int, int]]:
    match = VERSION_RE.search(output)
    if match is None:
        _fail(f"could not parse Hermes version from probe output: {output.strip()!r}")
    version = match.group(1)
    numeric = version.split("-", 1)[0].split("+", 1)[0]
    major, minor, patch = (int(part) for part in numeric.split(".")[:3])
    return version, (major, minor, patch)


def _probe_hermes(value: str | None) -> dict[str, Any]:
    command = _resolve_command(value)
    version_result = _run_hermes(command, ["version"])
    if version_result.returncode != 0:
        version_result = _run_hermes(command, ["--version"])
    if version_result.returncode != 0:
        _fail(
            "official Hermes version probe failed: "
            + (version_result.stderr.strip() or version_result.stdout.strip() or f"exit {version_result.returncode}")
        )
    version, numeric_version = _parse_version(version_result.stdout + "\n" + version_result.stderr)
    if numeric_version < MINIMUM_HERMES_VERSION:
        minimum = ".".join(str(part) for part in MINIMUM_HERMES_VERSION)
        _fail(f"Hermes {version} is too old for secure token pairing; require {minimum} or newer")

    help_result = _run_hermes(command, ["serve", "--help"])
    if help_result.returncode != 0:
        _fail("installed Hermes does not provide a usable 'hermes serve' command")
    help_text = help_result.stdout + "\n" + help_result.stderr
    required_flags = ("--host", "--port", "--status")
    missing = [flag for flag in required_flags if flag not in help_text]
    if missing:
        _fail(f"installed Hermes serve is missing required capabilities: {', '.join(missing)}")

    return {
        "schema_version": 1,
        "compatible": True,
        "minimum_version": ".".join(str(part) for part in MINIMUM_HERMES_VERSION),
        "version": version,
        "command": str(command),
        "command_sha256": _sha256_file(command),
        "serve_flags": list(required_flags),
        "session_token_environment": "HERMES_DASHBOARD_SESSION_TOKEN",
        "bind": BIND_HOST,
        "native_oauth_required": False,
    }


def _assert_no_running_hermes(command: Path) -> None:
    result = _run_hermes_current_home_status(command)
    if result.returncode != 0:
        _fail("could not determine whether an existing Hermes serve process is running; refusing to continue")
    if NO_RUNNING_SERVER not in result.stdout:
        _fail(
            "existing Hermes serve/dashboard process detected; this installer never stops, restarts, or replaces it. "
            "Choose a separate host/user or stop it explicitly before retrying."
        )


def _assert_port_available(port: int) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind((BIND_HOST, port))
        except OSError as error:
            _fail(f"loopback port {port} is unavailable ({error}); no service was created or started")


def _systemctl() -> Path | None:
    located = shutil.which("systemctl")
    return Path(located).resolve() if located else None


def _run_systemctl(systemctl: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            [str(systemctl), "--user", *arguments],
            text=True,
            capture_output=True,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        _fail(f"systemd user service query failed: {error}")
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        _fail(f"systemd user service operation failed ({' '.join(arguments)}): {detail}")
    return result


def _service_info(root: Path, systemctl: Path | None) -> dict[str, Any]:
    expected = (root / SERVICE_NAME).resolve(strict=False)
    if systemctl is None:
        return {
            "available": False,
            "loaded": False,
            "active": False,
            "enabled": False,
            "fragment": "",
            "owned": False,
        }
    load = _run_systemctl(systemctl, "show", SERVICE_NAME, "--property=LoadState", "--value", check=False)
    load_state = load.stdout.strip() if load.returncode == 0 else "not-found"
    fragment_result = _run_systemctl(
        systemctl, "show", SERVICE_NAME, "--property=FragmentPath", "--value", check=False
    )
    fragment = fragment_result.stdout.strip() if fragment_result.returncode == 0 else ""
    loaded = load_state not in {"", "not-found", "error"}
    owned = bool(fragment) and Path(fragment).resolve(strict=False) == expected
    active = _run_systemctl(systemctl, "is-active", "--quiet", SERVICE_NAME, check=False).returncode == 0
    enabled = _run_systemctl(systemctl, "is-enabled", "--quiet", SERVICE_NAME, check=False).returncode == 0
    return {
        "available": True,
        "loaded": loaded,
        "active": active,
        "enabled": enabled,
        "fragment": fragment,
        "owned": owned,
    }


def _assert_service_not_foreign(info: dict[str, Any]) -> None:
    if info["loaded"] and not info["owned"]:
        _fail(
            f"existing user service {SERVICE_NAME} is not managed by this package ({info['fragment'] or 'unknown path'}); "
            "refusing to replace it"
        )


def _normalize_endpoint(endpoint: str) -> tuple[str, str]:
    parsed = urlsplit(endpoint.strip())
    if parsed.scheme.lower() != "https":
        _fail("pairing endpoint must use HTTPS; plaintext HTTP/WS is unsupported by the secure installer")
    if not parsed.hostname or parsed.username is not None or parsed.password is not None:
        _fail("pairing endpoint must be an HTTPS origin without embedded credentials")
    if parsed.query or parsed.fragment:
        _fail("pairing endpoint must not contain a query string or fragment")
    path = parsed.path.rstrip("/")
    normalized = urlunsplit(("https", parsed.netloc, path, "", ""))
    websocket_path = f"{path}/api/ws" if path else "/api/ws"
    websocket = urlunsplit(("wss", parsed.netloc, websocket_path, "", ""))
    return normalized, websocket


def _read_token(path: Path, *, repair_mode: bool) -> str:
    _ensure_regular(path, label="session token")
    try:
        token = path.read_text(encoding="ascii").strip()
    except OSError as error:
        _fail(f"cannot read session token: {error}")
    if TOKEN_RE.fullmatch(token) is None:
        _fail(f"session token is invalid; refusing to replace it silently: {path}")
    if stat.S_IMODE(path.stat().st_mode) != SECRET_MODE:
        if repair_mode:
            path.chmod(SECRET_MODE)
        else:
            _fail(f"session token permissions must be 0600: {path}")
    return token


def _pairing_payload(endpoint: str | None, websocket: str | None, token: str, created_at: str) -> dict[str, Any]:
    return {
        "schema": "hermes-mobile-pairing/v1",
        "created_at": created_at,
        "ready": endpoint is not None,
        "endpoint": endpoint,
        "websocket_url": websocket,
        "auth": {
            "mode": "session_token",
            "header": TOKEN_HEADER,
            "token": token,
            "websocket_query_parameter": "token",
        },
        "gateway": {"bind": BIND_HOST, "tls_terminated_by_ingress": True},
        "native_oauth": {
            "optional": True,
            "required_for_session_token_pairing": False,
            "discovery": "/api/status#auth_flows",
            "authorize_path": "/auth/native/authorize",
            "ws_ticket_path": "/api/auth/ws-ticket",
        },
    }


def _launcher(root: Path, hermes: Path, port: int) -> bytes:
    token_path = shlex.quote(str(root / "session.token"))
    command = shlex.join([str(hermes), "serve", "--host", BIND_HOST, "--port", str(port)])
    script = f"""#!/bin/sh
set -eu
umask 077
unset PYTHONPATH PYTHONHOME
export PYTHONDONTWRITEBYTECODE=1
token_file={token_path}
if [ ! -f "$token_file" ]; then
    printf '%s\\n' "Hermes mobile gateway token file is missing" >&2
    exit 78
fi
IFS= read -r HERMES_DASHBOARD_SESSION_TOKEN < "$token_file"
case "$HERMES_DASHBOARD_SESSION_TOKEN" in
    ''|*[!0-9a-f]*)
        printf '%s\\n' "Hermes mobile gateway token file is invalid" >&2
        exit 78
        ;;
esac
if [ "${{#HERMES_DASHBOARD_SESSION_TOKEN}}" -ne 64 ]; then
    printf '%s\\n' "Hermes mobile gateway token file is invalid" >&2
    exit 78
fi
export HERMES_DASHBOARD_SESSION_TOKEN
exec {command}
"""
    return script.encode("utf-8")


def _unit() -> bytes:
    return f"""[Unit]
Description=Hermes Mobile Gateway (additive loopback service)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/.hermes/mobile-gateway/bin/launch
Restart=no
UMask=0077
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=default.target
""".encode("utf-8")


def _existing_matches(
    root: Path,
    probe: dict[str, Any],
    port: int,
    endpoint: str | None,
    expected_files: dict[Path, tuple[bytes, int]],
) -> bool:
    manifest_path = root / "install.json"
    runtime_path = root / "runtime.json"
    token_path = root / "session.token"
    pairing_path = root / "pairing.json"
    if not all(path.exists() for path in (manifest_path, runtime_path, token_path, pairing_path)):
        return False
    manifest = _read_json(manifest_path, label="install manifest")
    runtime = _read_json(runtime_path, label="runtime manifest")
    token = _read_token(token_path, repair_mode=False)
    pairing = _read_json(pairing_path, label="pairing manifest")
    if endpoint is not None:
        normalized, _ = _normalize_endpoint(endpoint)
        if pairing.get("endpoint") != normalized:
            return False
    expected_identity = {
        "installer_version": INSTALLER_VERSION,
        "installer_source_sha256": hashlib.sha256(_source_bytes()).hexdigest(),
        "bind": BIND_HOST,
        "port": port,
        "hermes_command": probe["command"],
        "hermes_command_sha256": probe["command_sha256"],
        "hermes_version": probe["version"],
    }
    if any(runtime.get(key) != value for key, value in expected_identity.items()):
        return False
    if manifest.get("managed_root") != str(root) or pairing.get("auth", {}).get("token") != token:
        return False
    for path, (content, mode) in expected_files.items():
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            return False
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            return False
        if stat.S_IMODE(metadata.st_mode) != mode or path.read_bytes() != content:
            return False
    return True


def _activate(root: Path, systemctl: Path, info: dict[str, Any]) -> None:
    _assert_service_not_foreign(info)
    if info["active"]:
        return
    unit_path = root / SERVICE_NAME
    if not info["owned"]:
        _run_systemctl(systemctl, "link", str(unit_path))
    _run_systemctl(systemctl, "daemon-reload")
    _run_systemctl(systemctl, "enable", "--now", SERVICE_NAME)


def _install(args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    _ensure_managed_root(root)
    probe = _probe_hermes(args.hermes)
    hermes = Path(probe["command"])
    systemctl = _systemctl()
    info = _service_info(root, systemctl)
    _assert_service_not_foreign(info)
    if not args.no_activate and systemctl is None:
        _fail("systemctl is required to activate the user service; use --no-activate to install resources only")

    endpoint: str | None = None
    websocket: str | None = None
    if args.endpoint:
        endpoint, websocket = _normalize_endpoint(args.endpoint)

    source = _source_bytes()
    expected_files = {
        root / "bin" / "manage": (source, EXECUTABLE_MODE),
        root / "bin" / "launch": (_launcher(root, hermes, args.port), EXECUTABLE_MODE),
        root / SERVICE_NAME: (_unit(), SECRET_MODE),
    }
    if root.exists() and _existing_matches(root, probe, args.port, endpoint, expected_files):
        if info["active"] or args.no_activate:
            return {
                "action": "already-installed",
                "root": str(root),
                "service_active": bool(info["active"]),
                "bind": BIND_HOST,
                "port": args.port,
            }
        _assert_no_running_hermes(hermes)
        _assert_port_available(args.port)
        if args.dry_run:
            return {
                "action": "would-activate",
                "root": str(root),
                "service_active": False,
                "bind": BIND_HOST,
                "port": args.port,
            }
        assert systemctl is not None
        _activate(root, systemctl, info)
        return {
            "action": "activated-existing-install",
            "root": str(root),
            "service_active": True,
            "bind": BIND_HOST,
            "port": args.port,
        }

    if info["active"]:
        _fail(
            f"managed service {SERVICE_NAME} is active but installed resources differ; "
            "this installer never restarts or replaces a running service. Stop it explicitly, then retry."
        )
    _assert_no_running_hermes(hermes)
    _assert_port_available(args.port)
    if args.dry_run:
        return {
            "action": "would-install",
            "root": str(root),
            "service_active": False,
            "activate": not args.no_activate,
            "bind": BIND_HOST,
            "port": args.port,
        }

    if root.exists() and (root / "install.json").exists():
        _fail(
            "installed resources differ from this package; automatic replacement is disabled. "
            "Use status, stop the additive service explicitly if active, then uninstall/rollback before reinstalling."
        )

    if not root.parent.exists():
        root.parent.mkdir(mode=DIRECTORY_MODE, parents=True)
    _mkdir_managed(root)
    bin_dir = root / "bin"
    _mkdir_managed(bin_dir)

    token_path = root / "session.token"
    if token_path.exists():
        token = _read_token(token_path, repair_mode=True)
    else:
        token = secrets.token_hex(32)
        _atomic_write(token_path, (token + "\n").encode("ascii"), SECRET_MODE)

    created_at = _utc_now()
    pairing_path = root / "pairing.json"
    pairing = _pairing_payload(endpoint, websocket, token, created_at)
    _write_json(pairing_path, pairing)
    for path, (content, mode) in expected_files.items():
        _atomic_write(path, content, mode)

    runtime = {
        "schema_version": 1,
        "installer_version": INSTALLER_VERSION,
        "installer_source_sha256": hashlib.sha256(source).hexdigest(),
        "installed_at": created_at,
        "bind": BIND_HOST,
        "port": args.port,
        "hermes_command": probe["command"],
        "hermes_command_sha256": probe["command_sha256"],
        "hermes_version": probe["version"],
    }
    capabilities = dict(probe)
    capabilities["probed_at"] = created_at
    manifest = {
        "schema_version": 1,
        "managed_root": str(root),
        "installed_at": created_at,
        "installer_version": INSTALLER_VERSION,
        "official_files_modified": [],
        "rollback": "remove this additive installation",
    }
    rollback = {
        "schema_version": 1,
        "action": "remove-installation",
        "created_at": created_at,
        "scope": str(root),
    }
    _write_json(root / "runtime.json", runtime)
    _write_json(root / "capabilities.json", capabilities)
    _write_json(root / "rollback.json", rollback)
    _write_json(root / "install.json", manifest)

    if not args.no_activate:
        assert systemctl is not None
        refreshed = _service_info(root, systemctl)
        _activate(root, systemctl, refreshed)
    return {
        "action": "installed",
        "root": str(root),
        "service_active": not args.no_activate,
        "bind": BIND_HOST,
        "port": args.port,
        "pairing_ready": endpoint is not None,
    }


def _status(_args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    _ensure_managed_root(root)
    if not root.exists() or not (root / "install.json").exists():
        return {"installed": False, "root": str(root), "service_active": False}
    runtime = _read_json(root / "runtime.json", label="runtime manifest")
    pairing = _read_json(root / "pairing.json", label="pairing manifest")
    info = _service_info(root, _systemctl())
    _assert_service_not_foreign(info)
    return {
        "installed": True,
        "root": str(root),
        "installer_version": runtime.get("installer_version"),
        "hermes_version": runtime.get("hermes_version"),
        "hermes_command": runtime.get("hermes_command"),
        "bind": runtime.get("bind"),
        "port": runtime.get("port"),
        "pairing_ready": bool(pairing.get("ready")),
        "service_active": bool(info["active"]),
        "service_enabled": bool(info["enabled"]),
    }


def _pair(args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    _ensure_managed_root(root, allow_absent=False)
    endpoint, websocket = _normalize_endpoint(args.endpoint)
    token = _read_token(root / "session.token", repair_mode=not args.dry_run)
    pairing_path = root / "pairing.json"
    existing = _read_json(pairing_path, label="pairing manifest") if pairing_path.exists() else {}
    if existing.get("endpoint") == endpoint and existing.get("auth", {}).get("token") == token:
        return {"action": "already-paired", "path": str(pairing_path), "endpoint": endpoint}
    if args.dry_run:
        return {"action": "would-pair", "path": str(pairing_path), "endpoint": endpoint}
    payload = _pairing_payload(endpoint, websocket, token, _utc_now())
    _write_json(pairing_path, payload)
    return {"action": "paired", "path": str(pairing_path), "endpoint": endpoint}


def _remove(args: argparse.Namespace, *, operation: str) -> dict[str, Any]:
    root = _mobile_root()
    _ensure_managed_root(root)
    if not root.exists():
        return {"action": "not-installed", "root": str(root), "operation": operation}
    systemctl = _systemctl()
    info = _service_info(root, systemctl)
    _assert_service_not_foreign(info)
    if info["active"]:
        _fail(
            f"{SERVICE_NAME} is active. This manager never stops an existing service automatically. "
            f"Stop it explicitly with 'systemctl --user stop {SERVICE_NAME}', verify it is inactive, then retry."
        )
    if args.dry_run:
        return {"action": "would-remove", "root": str(root), "operation": operation}
    if not args.yes:
        _fail(f"{operation} removes the additive service files and pairing secret; rerun with --yes")
    if systemctl is not None and info["owned"]:
        if info["enabled"]:
            _run_systemctl(systemctl, "disable", SERVICE_NAME)
        _run_systemctl(systemctl, "unlink", SERVICE_NAME, check=False)
        _run_systemctl(systemctl, "daemon-reload")
    shutil.rmtree(root)
    return {"action": "removed", "root": str(root), "operation": operation}


def _probe_command(args: argparse.Namespace) -> dict[str, Any]:
    return _probe_hermes(args.hermes)


def _emit(result: dict[str, Any], *, as_json: bool) -> None:
    if as_json:
        print(json.dumps(result, sort_keys=True))
        return
    action = result.get("action")
    if action == "already-installed":
        print("Hermes mobile gateway is already installed; no files or service were changed.")
    elif action == "installed":
        print(f"Installed additive Hermes mobile gateway resources in {result['root']}.")
        print(f"Service bind: {result['bind']}:{result['port']} (loopback only).")
        print("TLS ingress is required before pairing a mobile device.")
    elif action == "would-install":
        print(f"Dry run: would install additive resources in {result['root']}.")
        print(f"Dry run: service would bind {result['bind']}:{result['port']} (loopback only).")
        if result.get("activate"):
            print("Dry run: would link and start a new systemd user service after conflict checks.")
    elif action == "would-activate":
        print(f"Dry run: would activate the existing service on {result['bind']}:{result['port']}.")
    elif action == "activated-existing-install":
        print("Activated the existing additive Hermes mobile gateway installation.")
    elif action in {"paired", "already-paired", "would-pair"}:
        prefix = "Dry run: would write" if action == "would-pair" else "Pairing manifest is at"
        print(f"{prefix} {result['path']} for {result['endpoint']}.")
    elif action in {"would-remove", "removed"}:
        verb = "would remove" if action == "would-remove" else "removed"
        print(f"{result['operation'].capitalize()} {verb} only {result['root']}.")
    elif action == "not-installed":
        print(f"Hermes mobile gateway is not installed at {result['root']}.")
    elif "compatible" in result:
        print(f"Compatible official Hermes {result['version']} at {result['command']}")
        print(f"Capabilities: hermes serve {' '.join(result['serve_flags'])}; loopback session pairing supported.")
    elif "installed" in result:
        if not result["installed"]:
            print(f"Hermes mobile gateway is not installed at {result['root']}.")
        else:
            state = "active" if result["service_active"] else "inactive"
            print(f"Hermes mobile gateway: {state}")
            print(f"Bind: {result['bind']}:{result['port']} (loopback only)")
            print(f"Pairing ready: {'yes' if result['pairing_ready'] else 'no'}")
    else:
        print(json.dumps(result, indent=2, sort_keys=True))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hermes-mobile-gateway",
        description="Manage an additive loopback mobile gateway using the installed official Hermes CLI.",
    )
    parser.add_argument("--version", action="version", version=INSTALLER_VERSION)
    subparsers = parser.add_subparsers(dest="command", required=True)

    probe = subparsers.add_parser("probe", help="check the installed official Hermes version and serve capabilities")
    probe.add_argument("--hermes", help="official Hermes executable (default: resolve hermes on PATH)")
    probe.add_argument("--json", action="store_true", help="emit machine-readable output")
    probe.set_defaults(handler=_probe_command)

    install = subparsers.add_parser("install", help="install additive resources and a loopback user service")
    install.add_argument("--hermes", help="official Hermes executable (default: resolve hermes on PATH)")
    install.add_argument("--port", type=int, default=DEFAULT_PORT, choices=range(1024, 65536))
    install.add_argument("--endpoint", help="public/private HTTPS ingress URL to place in pairing.json")
    install.add_argument("--no-activate", action="store_true", help="write the service but do not link or start it")
    install.add_argument("--dry-run", action="store_true", help="probe and print the plan without changing files/services")
    install.add_argument("--json", action="store_true", help="emit machine-readable output")
    install.set_defaults(handler=_install)

    pair = subparsers.add_parser("pair", help="write a 0600 pairing manifest for an HTTPS ingress")
    pair.add_argument("--endpoint", required=True, help="HTTPS ingress URL visible to the mobile device")
    pair.add_argument("--dry-run", action="store_true", help="validate and print the plan without writing")
    pair.add_argument("--json", action="store_true", help="emit machine-readable output")
    pair.set_defaults(handler=_pair)

    status = subparsers.add_parser("status", help="show additive installation and user-service status")
    status.add_argument("--json", action="store_true", help="emit machine-readable output")
    status.set_defaults(handler=_status)

    for name in ("rollback", "uninstall"):
        remove = subparsers.add_parser(name, help=f"{name} only the additive mobile gateway resources")
        remove.add_argument("--dry-run", action="store_true", help="print the removal plan without changing anything")
        remove.add_argument("--yes", action="store_true", help="confirm removal of the pairing secret and managed root")
        remove.add_argument("--json", action="store_true", help="emit machine-readable output")
        remove.set_defaults(handler=lambda args, operation=name: _remove(args, operation=operation))
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        result = args.handler(args)
    except InstallError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    _emit(result, as_json=bool(getattr(args, "json", False)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
