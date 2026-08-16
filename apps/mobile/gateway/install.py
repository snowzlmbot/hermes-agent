#!/usr/bin/env python3
"""Install an independent credential-isolating mobile gateway sidecar.

The package never edits the official Hermes checkout, package, configuration,
or state. It manages one user service below ~/.hermes/mobile-gateway. That
service starts a pinned official ``hermes serve`` child on loopback and a
separate Hermes Mobile sidecar on a second loopback port.
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


INSTALLER_VERSION = "2.0.0"
PACKAGE_ID = "hermes.mobile.sidecar"
INSTALL_SCHEMA = "hermes.mobile-sidecar-install/v2"
PAIRING_SCHEMA = "hermes.mobile-sidecar-pairing/v2"
OFFICIAL_RELEASE_TAG = "v2026.8.13"
OFFICIAL_COMMIT = "f80f453ae0679347e38abc917c7f94f717bf96c5"
OFFICIAL_VERSION = (0, 20, 1)
DEFAULT_UPSTREAM_PORT = 9119
DEFAULT_SIDECAR_PORT = 9120
BIND_HOST = "127.0.0.1"
SERVICE_NAME = "hermes-mobile-gateway.service"
TOKEN_HEADER = "X-Hermes-Session-Token"
TICKET_TTL_SECONDS = 30
NO_RUNNING_SERVER = "No hermes dashboard processes running."
SECRET_MODE = 0o600
EXECUTABLE_MODE = 0o700
DIRECTORY_MODE = 0o700
TOKEN_RE = re.compile(r"[0-9a-f]{64}")
INSTALLATION_ID_RE = re.compile(r"[0-9a-f]{32}")
VERSION_RE = re.compile(r"(?<!\d)v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)")

_DIRECTORY_LAYOUT = {"bin": DIRECTORY_MODE}
_FILE_LAYOUT = {
    "bin/manage": EXECUTABLE_MODE,
    "bin/launch": EXECUTABLE_MODE,
    "bin/sidecar.py": EXECUTABLE_MODE,
    "bin/supervisor.py": EXECUTABLE_MODE,
    "external.token": SECRET_MODE,
    "internal.token": SECRET_MODE,
    "pairing.json": SECRET_MODE,
    "runtime.json": SECRET_MODE,
    "capabilities.json": SECRET_MODE,
    "rollback.json": SECRET_MODE,
    SERVICE_NAME: SECRET_MODE,
    "install.json": SECRET_MODE,
}
_ROOT_CHILDREN = {
    "bin",
    "external.token",
    "internal.token",
    "pairing.json",
    "runtime.json",
    "capabilities.json",
    "rollback.json",
    SERVICE_NAME,
    "install.json",
}
_BIN_CHILDREN = {"manage", "launch", "sidecar.py", "supervisor.py"}


class InstallError(RuntimeError):
    """An expected, user-actionable compatibility or safety failure."""


def _fail(message: str) -> NoReturn:
    raise InstallError(message)


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _canonical_home() -> Path:
    raw = os.environ.get("HOME", "").strip()
    if not raw:
        _fail("HOME is not set; refusing to choose an installation directory")
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        _fail("HOME must be an absolute canonical path")
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        _fail(f"HOME is unavailable: {error}")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail("HOME must be a real directory, not a symlink")
    if resolved != candidate:
        _fail("HOME must already be canonical; symlinked path components are not allowed")
    if metadata.st_uid != os.getuid():
        _fail("HOME is not owned by the current user")
    if stat.S_IMODE(metadata.st_mode) & 0o022:
        _fail("HOME must not be group/world writable")
    return resolved


def _mobile_root() -> Path:
    return _canonical_home() / ".hermes" / "mobile-gateway"


def _source_bytes() -> bytes:
    return Path(__file__).resolve().read_bytes()


def _asset_bytes(name: str) -> bytes:
    path = Path(__file__).resolve().parent / name
    try:
        return path.read_bytes()
    except OSError as error:
        _fail(f"required installer asset is unavailable ({name}): {error}")


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _lstat(path: Path, *, label: str) -> os.stat_result:
    try:
        return path.lstat()
    except OSError as error:
        _fail(f"cannot inspect {label} at {path}: {error}")


def _require_directory(path: Path, *, label: str, mode: int | None = None) -> None:
    metadata = _lstat(path, label=label)
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail(f"{label} must be a real directory, not a symlink: {path}")
    if metadata.st_uid != os.getuid():
        _fail(f"{label} is not owned by the current user: {path}")
    if mode is not None and stat.S_IMODE(metadata.st_mode) != mode:
        _fail(f"{label} permissions must be {mode:04o}: {path}")


def _require_file(path: Path, *, label: str, mode: int) -> None:
    metadata = _lstat(path, label=label)
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        _fail(f"{label} must be a regular file, not a symlink: {path}")
    if metadata.st_uid != os.getuid():
        _fail(f"{label} is not owned by the current user: {path}")
    if stat.S_IMODE(metadata.st_mode) != mode:
        _fail(f"{label} permissions must be {mode:04o}: {path}")


def _ensure_parent(root: Path, *, create: bool) -> None:
    parent = root.parent
    if parent.exists():
        _require_directory(parent, label="Hermes home directory")
        if stat.S_IMODE(parent.lstat().st_mode) & 0o022:
            _fail(f"Hermes home directory must not be group/world writable: {parent}")
        return
    if not create:
        return
    parent.mkdir(mode=DIRECTORY_MODE, parents=False)
    _require_directory(parent, label="Hermes home directory", mode=DIRECTORY_MODE)


def _classify_root(root: Path) -> str:
    _ensure_parent(root, create=False)
    try:
        metadata = root.lstat()
    except FileNotFoundError:
        return "absent"
    except OSError as error:
        _fail(f"cannot inspect managed root: {error}")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        _fail(f"managed root must be a real directory, not a symlink: {root}")
    if metadata.st_uid != os.getuid():
        _fail(f"managed root is not owned by the current user: {root}")
    if stat.S_IMODE(metadata.st_mode) != DIRECTORY_MODE:
        _fail(f"managed root permissions must be 0700: {root}")
    children = {entry.name for entry in os.scandir(root)}
    if not children:
        return "empty"
    if "install.json" not in children:
        _fail(f"refusing to treat unmanaged content as this package: {root}")
    return "installed"


def _mkdir_exact(path: Path) -> None:
    path.mkdir(mode=DIRECTORY_MODE, parents=False)
    _require_directory(path, label="managed directory", mode=DIRECTORY_MODE)


def _atomic_write(path: Path, content: bytes, mode: int) -> bool:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        metadata = None
    except OSError as error:
        _fail(f"cannot inspect managed file {path}: {error}")
    if metadata is not None:
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            _fail(f"managed file must be a regular file, not a symlink: {path}")
        if metadata.st_uid != os.getuid():
            _fail(f"managed file is not owned by the current user: {path}")
        if stat.S_IMODE(metadata.st_mode) != mode:
            _fail(f"managed file permissions must be {mode:04o}: {path}")
        if path.read_bytes() == content:
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
    _require_file(path, label="managed file", mode=mode)
    return True


def _write_json(path: Path, payload: dict[str, Any]) -> bool:
    content = (json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8")
    return _atomic_write(path, content, SECRET_MODE)


def _read_json_verified(path: Path, *, label: str) -> dict[str, Any]:
    # The caller verifies type, owner, and mode before this function. This
    # ordering prevents read-only status from reading an insecure secret file.
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        _fail(f"cannot read {label} at {path}: {error}")
    if not isinstance(value, dict):
        _fail(f"{label} must contain a JSON object: {path}")
    return value


def _layout_metadata(root: Path) -> None:
    _require_directory(root, label="managed root", mode=DIRECTORY_MODE)
    actual_root = {entry.name for entry in os.scandir(root)}
    if actual_root != _ROOT_CHILDREN:
        _fail(f"managed root inventory differs from {PACKAGE_ID}; refusing to continue")
    bin_dir = root / "bin"
    _require_directory(bin_dir, label="managed bin directory", mode=DIRECTORY_MODE)
    actual_bin = {entry.name for entry in os.scandir(bin_dir)}
    if actual_bin != _BIN_CHILDREN:
        _fail(f"managed bin inventory differs from {PACKAGE_ID}; refusing to continue")
    for relative, mode in _FILE_LAYOUT.items():
        _require_file(root / relative, label=f"managed inventory entry {relative}", mode=mode)


def _inventory_for(root: Path) -> dict[str, dict[str, Any]]:
    inventory: dict[str, dict[str, Any]] = {
        relative: {"type": "directory", "mode": f"{mode:04o}"}
        for relative, mode in _DIRECTORY_LAYOUT.items()
    }
    for relative, mode in _FILE_LAYOUT.items():
        entry: dict[str, Any] = {"type": "file", "mode": f"{mode:04o}"}
        if relative != "install.json":
            entry["sha256"] = _sha256_file(root / relative)
        inventory[relative] = entry
    return inventory


def _write_install_manifest(root: Path, *, installation_id: str, installed_at: str) -> None:
    manifest = {
        "schema": INSTALL_SCHEMA,
        "schema_version": 2,
        "package_id": PACKAGE_ID,
        "installation_id": installation_id,
        "managed_root": str(root),
        "installed_at": installed_at,
        "installer_version": INSTALLER_VERSION,
        "inventory": _inventory_for(root),
        "official_files_modified": [],
    }
    _write_json(root / "install.json", manifest)


def _validate_installation(root: Path) -> dict[str, Any]:
    _layout_metadata(root)
    manifest = _read_json_verified(root / "install.json", label="install manifest")
    installation_id = manifest.get("installation_id")
    if (
        manifest.get("schema") != INSTALL_SCHEMA
        or manifest.get("schema_version") != 2
        or manifest.get("package_id") != PACKAGE_ID
        or not isinstance(installation_id, str)
        or INSTALLATION_ID_RE.fullmatch(installation_id) is None
    ):
        _fail("install manifest does not identify a valid Hermes Mobile sidecar installation")
    if manifest.get("managed_root") != str(root) or Path(str(manifest.get("managed_root"))) != root:
        _fail("install manifest managed_root does not match the canonical package root")
    inventory = manifest.get("inventory")
    if not isinstance(inventory, dict) or set(inventory) != set(_DIRECTORY_LAYOUT) | set(_FILE_LAYOUT):
        _fail("install manifest inventory is incomplete or contains unowned paths")
    actual = _inventory_for(root)
    if inventory != actual:
        _fail("managed inventory digest, type, or mode mismatch; refusing to continue")
    return manifest


def _read_token_verified(path: Path, *, label: str) -> str:
    try:
        token = path.read_text(encoding="ascii").strip()
    except OSError as error:
        _fail(f"cannot read {label}: {error}")
    if TOKEN_RE.fullmatch(token) is None:
        _fail(f"{label} is invalid")
    return token


def _resolve_command(value: str | None) -> Path:
    selected = value or "hermes"
    candidate = Path(selected).expanduser()
    if not candidate.is_absolute() and os.sep not in selected:
        located = shutil.which(selected)
        if located is None:
            _fail(f"official Hermes command is not on PATH: {selected}")
        candidate = Path(located)
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        _fail(f"cannot resolve official Hermes command {candidate}: {error}")
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        _fail(f"official Hermes command is not executable: {resolved}")
    return resolved


def _python_from_shebang(command: Path) -> Path | None:
    try:
        first_line = command.open("rb").readline(4096).decode("utf-8", "strict").strip()
    except (OSError, UnicodeDecodeError):
        return None
    if not first_line.startswith("#!"):
        return None
    words = shlex.split(first_line[2:])
    if len(words) != 1 or not Path(words[0]).is_absolute():
        return None
    return Path(words[0])


def _resolve_python(value: str | None, hermes: Path) -> Path:
    candidate = Path(value).expanduser() if value else _python_from_shebang(hermes)
    if candidate is None:
        _fail("cannot identify the official Hermes Python; pass --python /absolute/path/to/its/python")
    if not candidate.is_absolute():
        _fail("official Hermes Python must be an absolute path")
    try:
        resolved_target = candidate.resolve(strict=True)
    except OSError as error:
        _fail(f"cannot resolve official Hermes Python {candidate}: {error}")
    if not resolved_target.is_file() or not os.access(candidate, os.X_OK):
        _fail(f"official Hermes Python is not executable: {candidate}")
    # Preserve the venv entry path. Resolving its normal python symlink to the
    # base interpreter would discard pyvenv.cfg and lose official dependencies.
    return candidate


def _sanitized_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "PYTHONDONTWRITEBYTECODE": "1",
            "OPENROUTER_API_KEY": "",
            "OPENAI_API_KEY": "",
            "NOUS_API_KEY": "",
        }
    )
    environment.pop("PYTHONPATH", None)
    environment.pop("PYTHONHOME", None)
    return environment


def _run_isolated(command: list[str], *, timeout: int = 20) -> subprocess.CompletedProcess[str]:
    with tempfile.TemporaryDirectory(prefix="hermes-mobile-probe-") as directory:
        home = Path(directory)
        environment = _sanitized_environment()
        environment.update({"HOME": str(home), "HERMES_HOME": str(home / ".hermes")})
        try:
            return subprocess.run(
                command,
                cwd=directory,
                env=environment,
                text=True,
                capture_output=True,
                timeout=timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            _fail(f"official Hermes capability probe failed: {error}")


def _run_current_home_status(command: Path) -> subprocess.CompletedProcess[str]:
    environment = _sanitized_environment()
    with tempfile.TemporaryDirectory(prefix="hermes-mobile-status-cwd-") as directory:
        try:
            return subprocess.run(
                [str(command), "serve", "--status"],
                cwd=directory,
                env=environment,
                text=True,
                capture_output=True,
                timeout=20,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            _fail(f"official Hermes status probe failed: {error}")


def _parse_version(output: str) -> tuple[str, tuple[int, int, int]]:
    match = VERSION_RE.search(output)
    if match is None:
        _fail(f"could not parse official Hermes version from: {output.strip()!r}")
    version = match.group(1)
    numeric = version.split("-", 1)[0].split("+", 1)[0]
    return version, tuple(int(part) for part in numeric.split(".")[:3])  # type: ignore[return-value]


def _probe_hermes(command_value: str | None, python_value: str | None) -> dict[str, Any]:
    command = _resolve_command(command_value)
    python = _resolve_python(python_value, command)
    version_result = _run_isolated([str(command), "version"])
    if version_result.returncode != 0:
        version_result = _run_isolated([str(command), "--version"])
    if version_result.returncode != 0:
        _fail("official Hermes version probe failed")
    version, numeric = _parse_version(version_result.stdout + "\n" + version_result.stderr)
    if numeric != OFFICIAL_VERSION:
        expected = ".".join(str(part) for part in OFFICIAL_VERSION)
        _fail(f"this compatibility package is pinned to official Hermes {expected}; found {version}")

    help_result = _run_isolated([str(command), "serve", "--help"])
    if help_result.returncode != 0:
        _fail("installed official Hermes does not provide a usable 'hermes serve' command")
    help_text = help_result.stdout + "\n" + help_result.stderr
    required_flags = ("--host", "--port", "--status")
    missing = [flag for flag in required_flags if flag not in help_text]
    if missing:
        _fail(f"official Hermes serve is missing required flags: {', '.join(missing)}")

    dependency_probe = _run_isolated(
        [
            str(python),
            "-I",
            "-c",
            "import fastapi,httpx,uvicorn,websockets; print('sidecar-dependencies-ok')",
        ]
    )
    if dependency_probe.returncode != 0 or dependency_probe.stdout.strip() != "sidecar-dependencies-ok":
        _fail("official Hermes Python does not provide the sidecar's pinned runtime dependencies")

    return {
        "schema_version": 2,
        "compatible": True,
        "official_release_tag": OFFICIAL_RELEASE_TAG,
        "official_commit": OFFICIAL_COMMIT,
        "version": version,
        "command": str(command),
        "command_sha256": _sha256_file(command),
        "python": str(python),
        "python_sha256": _sha256_file(python),
        "serve_flags": list(required_flags),
        "bind": BIND_HOST,
    }


def _assert_no_running_hermes(command: Path) -> None:
    result = _run_current_home_status(command)
    if result.returncode != 0:
        _fail("could not determine whether an existing Hermes gateway is running; refusing to continue")
    if NO_RUNNING_SERVER not in result.stdout:
        _fail(
            "an existing Hermes serve/dashboard process is running; this package never stops, restarts, "
            "or replaces it"
        )


def _assert_port_available(port: int, *, label: str) -> None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((BIND_HOST, port))
    except OSError as error:
        _fail(f"{label} loopback port {port} cannot be verified ({error})")


def _port_listening(port: int) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
            connection.settimeout(0.25)
            return connection.connect_ex((BIND_HOST, port)) == 0
    except OSError as error:
        _fail(f"cannot verify whether loopback port {port} is in use: {error}")


def _systemctl() -> Path | None:
    located = shutil.which("systemctl")
    return Path(located).resolve() if located else None


def _run_systemctl(systemctl: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    try:
        result = subprocess.run(
            [str(systemctl), "--user", *arguments],
            env=environment,
            text=True,
            capture_output=True,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        _fail(f"systemd user service operation failed: {error}")
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        _fail(f"systemd user service operation failed ({' '.join(arguments)}): {detail}")
    return result


def _verified_not_found(result: subprocess.CompletedProcess[str]) -> bool:
    if result.returncode == 0:
        return False
    detail = (result.stderr.strip() or result.stdout.strip()).rstrip(".")
    return detail in {
        f"Unit {SERVICE_NAME} could not be found",
        f"Unit {SERVICE_NAME} not found",
    }


def _service_info(root: Path, systemctl: Path | None) -> dict[str, Any]:
    if systemctl is None:
        return {"certainty": "unknown", "reason": "systemctl unavailable"}
    result = _run_systemctl(
        systemctl,
        "show",
        SERVICE_NAME,
        "--property=LoadState",
        "--property=FragmentPath",
        "--property=ActiveState",
        "--property=UnitFileState",
        check=False,
    )
    if result.returncode != 0:
        if _verified_not_found(result):
            return {"certainty": "known", "loaded": False, "active": False, "enabled": False, "owned": False}
        return {"certainty": "unknown", "reason": "systemctl show failed"}
    properties: dict[str, str] = {}
    for line in result.stdout.splitlines():
        key, separator, value = line.partition("=")
        if separator:
            properties[key] = value
    load_state = properties.get("LoadState", "")
    if load_state == "not-found":
        return {"certainty": "known", "loaded": False, "active": False, "enabled": False, "owned": False}
    required = {"LoadState", "FragmentPath", "ActiveState", "UnitFileState"}
    if load_state != "loaded" or not required.issubset(properties):
        return {"certainty": "unknown", "reason": "incomplete or unsupported systemctl state"}
    fragment = properties["FragmentPath"]
    expected = str(root / SERVICE_NAME)
    owned = fragment == expected
    active_state = properties["ActiveState"]
    return {
        "certainty": "known",
        "loaded": True,
        "active": active_state == "active",
        "active_state": active_state,
        "enabled": properties["UnitFileState"] in {"enabled", "enabled-runtime", "linked", "linked-runtime"},
        "fragment": fragment,
        "owned": owned,
    }


def _require_known_service(info: dict[str, Any], *, operation: str) -> None:
    if info.get("certainty") != "known":
        _fail(f"systemd service state is UNKNOWN; {operation} is blocked until systemctl queries succeed")


def _assert_service_not_foreign(info: dict[str, Any]) -> None:
    if info.get("certainty") == "known" and info.get("loaded") and not info.get("owned"):
        _fail(
            f"existing user service {SERVICE_NAME} is not the exact package-owned unit "
            f"({info.get('fragment') or 'unknown path'}); refusing to replace it"
        )


def _normalize_endpoint(endpoint: str) -> tuple[str, str]:
    parsed = urlsplit(endpoint.strip())
    if parsed.scheme.lower() != "https":
        _fail("pairing endpoint must use HTTPS")
    if not parsed.hostname or parsed.username is not None or parsed.password is not None:
        _fail("pairing endpoint must be an HTTPS origin without embedded credentials")
    if parsed.query or parsed.fragment:
        _fail("pairing endpoint must not contain a query string or fragment")
    path = parsed.path.rstrip("/")
    endpoint_url = urlunsplit(("https", parsed.netloc, path, "", ""))
    websocket_path = f"{path}/api/ws" if path else "/api/ws"
    websocket_url = urlunsplit(("wss", parsed.netloc, websocket_path, "", ""))
    return endpoint_url, websocket_url


def _pairing_payload(
    *,
    installation_id: str,
    endpoint: str | None,
    websocket: str | None,
    external_token: str,
    created_at: str,
    sidecar_port: int,
    upstream_port: int,
) -> dict[str, Any]:
    return {
        "schema": PAIRING_SCHEMA,
        "installation_id": installation_id,
        "created_at": created_at,
        "ready": endpoint is not None,
        "endpoint": endpoint,
        "websocket_url": websocket,
        "auth": {
            "mode": "session_token",
            "header": TOKEN_HEADER,
            "token": external_token,
            "ws_ticket_path": "/api/auth/ws-ticket",
            "websocket_query_parameter": "ticket",
            "ticket_ttl_seconds": TICKET_TTL_SECONDS,
        },
        "gateway": {
            "sidecar_bind": BIND_HOST,
            "sidecar_port": sidecar_port,
            "official_upstream_bind": BIND_HOST,
            "official_upstream_port": upstream_port,
            "tls_terminated_by_ingress": True,
        },
    }


def _launch_script(root: Path, probe: dict[str, Any], upstream_port: int, sidecar_port: int) -> bytes:
    command = [
        probe["python"],
        "-I",
        str(root / "bin" / "supervisor.py"),
        "--hermes",
        probe["command"],
        "--sidecar",
        str(root / "bin" / "sidecar.py"),
        "--internal-token-file",
        str(root / "internal.token"),
        "--external-token-file",
        str(root / "external.token"),
        "--upstream-port",
        str(upstream_port),
        "--sidecar-port",
        str(sidecar_port),
    ]
    return (
        "#!/bin/sh\n"
        "set -eu\n"
        "umask 077\n"
        "unset PYTHONPATH PYTHONHOME\n"
        f"exec {shlex.join(command)}\n"
    ).encode("utf-8")


def _systemd_quote(path: Path) -> str:
    value = str(path)
    if any(character in value for character in "\r\n\0"):
        _fail("managed root contains characters unsupported by systemd")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%") + '"'


def _unit(root: Path) -> bytes:
    return f"""[Unit]
Description=Hermes Mobile credential-isolating sidecar
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart={_systemd_quote(root / "bin" / "launch")}
Restart=no
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
KillMode=mixed
TimeoutStopSec=20

[Install]
WantedBy=default.target
""".encode("utf-8")


def _expected_files(root: Path, probe: dict[str, Any], upstream_port: int, sidecar_port: int) -> dict[str, bytes]:
    return {
        "bin/manage": _source_bytes(),
        "bin/launch": _launch_script(root, probe, upstream_port, sidecar_port),
        "bin/sidecar.py": _asset_bytes("sidecar.py"),
        "bin/supervisor.py": _asset_bytes("supervisor.py"),
        SERVICE_NAME: _unit(root),
    }


def _existing_matches(
    root: Path,
    manifest: dict[str, Any],
    probe: dict[str, Any],
    upstream_port: int,
    sidecar_port: int,
    endpoint: str | None,
) -> bool:
    runtime = _read_json_verified(root / "runtime.json", label="runtime manifest")
    pairing = _read_json_verified(root / "pairing.json", label="pairing manifest")
    installation_id = manifest["installation_id"]
    expected_identity = {
        "schema_version": 2,
        "package_id": PACKAGE_ID,
        "installation_id": installation_id,
        "installer_version": INSTALLER_VERSION,
        "installer_source_sha256": _sha256_bytes(_source_bytes()),
        "bind": BIND_HOST,
        "upstream_port": upstream_port,
        "sidecar_port": sidecar_port,
        "hermes_command": probe["command"],
        "hermes_command_sha256": probe["command_sha256"],
        "hermes_python": probe["python"],
        "hermes_python_sha256": probe["python_sha256"],
        "hermes_version": probe["version"],
        "official_release_tag": OFFICIAL_RELEASE_TAG,
        "official_commit": OFFICIAL_COMMIT,
    }
    if any(runtime.get(key) != value for key, value in expected_identity.items()):
        return False
    if pairing.get("installation_id") != installation_id:
        return False
    if endpoint is not None and pairing.get("endpoint") != _normalize_endpoint(endpoint)[0]:
        return False
    expected_files = _expected_files(root, probe, upstream_port, sidecar_port)
    return all((root / relative).read_bytes() == content for relative, content in expected_files.items())


def _activate(root: Path, systemctl: Path, info: dict[str, Any]) -> None:
    _require_known_service(info, operation="activation")
    _assert_service_not_foreign(info)
    if info.get("active"):
        return
    if info.get("loaded") and info.get("active_state") != "inactive":
        _fail(f"service state {info.get('active_state')} is not safe for activation")
    if not info.get("loaded"):
        _run_systemctl(systemctl, "link", str(root / SERVICE_NAME))
        _run_systemctl(systemctl, "daemon-reload")
    _run_systemctl(systemctl, "enable", "--now", SERVICE_NAME)
    refreshed = _service_info(root, systemctl)
    _require_known_service(refreshed, operation="activation verification")
    if not refreshed.get("loaded") or not refreshed.get("owned") or not refreshed.get("active"):
        _fail("service activation could not be verified against the exact package-owned unit")


def _install(args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    root_state = _classify_root(root)
    manifest = _validate_installation(root) if root_state == "installed" else None
    probe = _probe_hermes(args.hermes, args.python)
    hermes = Path(probe["command"])
    systemctl = _systemctl()
    info = _service_info(root, systemctl)
    _assert_service_not_foreign(info)
    if not args.no_activate:
        _require_known_service(info, operation="activation")
        if systemctl is None:
            _fail("systemctl is required for activation")

    endpoint: str | None = None
    websocket: str | None = None
    if args.endpoint:
        endpoint, websocket = _normalize_endpoint(args.endpoint)

    if manifest is not None and _existing_matches(
        root,
        manifest,
        probe,
        args.upstream_port,
        args.sidecar_port,
        endpoint,
    ):
        if info.get("active") or args.no_activate:
            return {
                "action": "already-installed",
                "root": str(root),
                "service_state": "active" if info.get("active") else info.get("certainty", "unknown"),
                "upstream_port": args.upstream_port,
                "sidecar_port": args.sidecar_port,
            }
        _assert_no_running_hermes(hermes)
        _assert_port_available(args.upstream_port, label="official upstream")
        _assert_port_available(args.sidecar_port, label="sidecar")
        if args.dry_run:
            return {"action": "would-activate", "root": str(root), "sidecar_port": args.sidecar_port}
        assert systemctl is not None
        _activate(root, systemctl, info)
        return {"action": "activated-existing-install", "root": str(root), "sidecar_port": args.sidecar_port}

    if manifest is not None:
        if info.get("active"):
            _fail("the owned service is active but its resources differ; this package never restarts it")
        _fail("installed resources differ from this package; automatic replacement is disabled")
    if root_state not in {"absent", "empty"}:
        _fail("managed root is not safe for installation")
    if info.get("loaded"):
        _fail("a service registration already exists without a verified package installation")

    _assert_no_running_hermes(hermes)
    _assert_port_available(args.upstream_port, label="official upstream")
    _assert_port_available(args.sidecar_port, label="sidecar")
    if args.dry_run:
        return {
            "action": "would-install",
            "root": str(root),
            "activate": not args.no_activate,
            "upstream_port": args.upstream_port,
            "sidecar_port": args.sidecar_port,
        }

    _ensure_parent(root, create=True)
    if root_state == "absent":
        _mkdir_exact(root)
    _mkdir_exact(root / "bin")

    installation_id = secrets.token_hex(16)
    internal_token = secrets.token_hex(32)
    external_token = secrets.token_hex(32)
    installed_at = _utc_now()
    _atomic_write(root / "internal.token", (internal_token + "\n").encode("ascii"), SECRET_MODE)
    _atomic_write(root / "external.token", (external_token + "\n").encode("ascii"), SECRET_MODE)

    for relative, content in _expected_files(root, probe, args.upstream_port, args.sidecar_port).items():
        _atomic_write(root / relative, content, _FILE_LAYOUT[relative])

    pairing = _pairing_payload(
        installation_id=installation_id,
        endpoint=endpoint,
        websocket=websocket,
        external_token=external_token,
        created_at=installed_at,
        sidecar_port=args.sidecar_port,
        upstream_port=args.upstream_port,
    )
    runtime = {
        "schema_version": 2,
        "package_id": PACKAGE_ID,
        "installation_id": installation_id,
        "installer_version": INSTALLER_VERSION,
        "installer_source_sha256": _sha256_bytes(_source_bytes()),
        "installed_at": installed_at,
        "bind": BIND_HOST,
        "upstream_port": args.upstream_port,
        "sidecar_port": args.sidecar_port,
        "hermes_command": probe["command"],
        "hermes_command_sha256": probe["command_sha256"],
        "hermes_python": probe["python"],
        "hermes_python_sha256": probe["python_sha256"],
        "hermes_version": probe["version"],
        "official_release_tag": OFFICIAL_RELEASE_TAG,
        "official_commit": OFFICIAL_COMMIT,
    }
    capabilities = dict(probe)
    capabilities.update(
        {
            "package_id": PACKAGE_ID,
            "installation_id": installation_id,
            "probed_at": installed_at,
            "external_auth": "HTTPS header",
            "websocket_auth": "30-second single-use ticket",
        }
    )
    rollback = {
        "schema_version": 2,
        "package_id": PACKAGE_ID,
        "installation_id": installation_id,
        "action": "remove-verified-inventory",
        "created_at": installed_at,
        "scope": str(root),
    }
    _write_json(root / "pairing.json", pairing)
    _write_json(root / "runtime.json", runtime)
    _write_json(root / "capabilities.json", capabilities)
    _write_json(root / "rollback.json", rollback)
    _write_install_manifest(root, installation_id=installation_id, installed_at=installed_at)
    _validate_installation(root)

    if not args.no_activate:
        assert systemctl is not None
        refreshed = _service_info(root, systemctl)
        _activate(root, systemctl, refreshed)
    return {
        "action": "installed",
        "root": str(root),
        "service_active": not args.no_activate,
        "upstream_port": args.upstream_port,
        "sidecar_port": args.sidecar_port,
        "pairing_ready": endpoint is not None,
    }


def _status(_args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    root_state = _classify_root(root)
    if root_state in {"absent", "empty"}:
        return {"installed": False, "root": str(root), "service_state": "not-installed"}
    manifest = _validate_installation(root)
    runtime = _read_json_verified(root / "runtime.json", label="runtime manifest")
    pairing = _read_json_verified(root / "pairing.json", label="pairing manifest")
    if runtime.get("installation_id") != manifest["installation_id"]:
        _fail("runtime manifest installation ID mismatch")
    info = _service_info(root, _systemctl())
    _assert_service_not_foreign(info)
    service_state = "unknown"
    if info.get("certainty") == "known":
        service_state = info.get("active_state", "absent") if info.get("loaded") else "absent"
    return {
        "installed": True,
        "root": str(root),
        "package_id": PACKAGE_ID,
        "installation_id": manifest["installation_id"],
        "installer_version": runtime.get("installer_version"),
        "hermes_version": runtime.get("hermes_version"),
        "bind": runtime.get("bind"),
        "upstream_port": runtime.get("upstream_port"),
        "sidecar_port": runtime.get("sidecar_port"),
        "pairing_ready": bool(pairing.get("ready")),
        "service_state": service_state,
        "service_active": info.get("active") if info.get("certainty") == "known" else None,
        "service_enabled": info.get("enabled") if info.get("certainty") == "known" else None,
    }


def _pair(args: argparse.Namespace) -> dict[str, Any]:
    root = _mobile_root()
    if _classify_root(root) != "installed":
        _fail(f"mobile sidecar is not installed at {root}")
    manifest = _validate_installation(root)
    runtime = _read_json_verified(root / "runtime.json", label="runtime manifest")
    endpoint, websocket = _normalize_endpoint(args.endpoint)
    external_token = _read_token_verified(root / "external.token", label="external token")
    existing = _read_json_verified(root / "pairing.json", label="pairing manifest")
    if existing.get("endpoint") == endpoint:
        return {"action": "already-paired", "path": str(root / "pairing.json"), "endpoint": endpoint}
    if args.dry_run:
        return {"action": "would-pair", "path": str(root / "pairing.json"), "endpoint": endpoint}
    payload = _pairing_payload(
        installation_id=manifest["installation_id"],
        endpoint=endpoint,
        websocket=websocket,
        external_token=external_token,
        created_at=_utc_now(),
        sidecar_port=int(runtime["sidecar_port"]),
        upstream_port=int(runtime["upstream_port"]),
    )
    _write_json(root / "pairing.json", payload)
    _write_install_manifest(
        root,
        installation_id=manifest["installation_id"],
        installed_at=str(manifest["installed_at"]),
    )
    _validate_installation(root)
    return {"action": "paired", "path": str(root / "pairing.json"), "endpoint": endpoint}


def _remove_verified_tree(root: Path) -> None:
    for relative in sorted(_FILE_LAYOUT, key=lambda value: (value.count("/"), value), reverse=True):
        (root / relative).unlink()
    (root / "bin").rmdir()
    root.rmdir()


def _remove(args: argparse.Namespace, *, operation: str) -> dict[str, Any]:
    root = _mobile_root()
    root_state = _classify_root(root)
    if root_state in {"absent", "empty"}:
        if root_state == "empty":
            _fail("an empty managed root exists but is not a verified installation")
        return {"action": "not-installed", "root": str(root), "operation": operation}
    manifest = _validate_installation(root)
    runtime = _read_json_verified(root / "runtime.json", label="runtime manifest")
    if runtime.get("installation_id") != manifest["installation_id"]:
        _fail("runtime manifest installation ID mismatch")

    systemctl = _systemctl()
    info = _service_info(root, systemctl)
    _require_known_service(info, operation=operation)
    _assert_service_not_foreign(info)
    if info.get("loaded") and info.get("active_state") != "inactive":
        _fail(
            f"{SERVICE_NAME} is {info.get('active_state')}; this manager never stops a process automatically. "
            "Stop this exact package-owned service and verify it is inactive before retrying."
        )
    for key in ("upstream_port", "sidecar_port"):
        port = runtime.get(key)
        if not isinstance(port, int):
            _fail(f"runtime manifest has invalid {key}")
        if _port_listening(port):
            _fail(f"loopback port {port} still has a listener; refusing to remove files used by a process")

    if args.dry_run:
        return {"action": "would-remove", "root": str(root), "operation": operation}
    if not args.yes:
        _fail(f"{operation} removes the verified sidecar credentials and files; rerun with --yes")
    assert systemctl is not None
    if info.get("loaded"):
        if info.get("enabled"):
            _run_systemctl(systemctl, "disable", SERVICE_NAME)
        _run_systemctl(systemctl, "unlink", SERVICE_NAME)
        _run_systemctl(systemctl, "daemon-reload")
        refreshed = _service_info(root, systemctl)
        _require_known_service(refreshed, operation=f"{operation} verification")
        if refreshed.get("loaded"):
            _fail("service unlink could not be verified; managed files were retained")

    _validate_installation(root)
    _remove_verified_tree(root)
    return {"action": "removed", "root": str(root), "operation": operation}


def _probe_command(args: argparse.Namespace) -> dict[str, Any]:
    return _probe_hermes(args.hermes, args.python)


def _emit(result: dict[str, Any], *, as_json: bool) -> None:
    if as_json:
        print(json.dumps(result, sort_keys=True))
        return
    action = result.get("action")
    if action == "already-installed":
        print("The Hermes Mobile sidecar is already installed; nothing changed.")
    elif action == "installed":
        print(f"Installed the additive sidecar in {result['root']}.")
        print(f"Official upstream: {BIND_HOST}:{result['upstream_port']} (loopback only).")
        print(f"TLS ingress target: {BIND_HOST}:{result['sidecar_port']} (loopback only).")
    elif action == "would-install":
        print(f"Dry run: would install additive resources in {result['root']}.")
        print(f"Dry run: official upstream would bind {BIND_HOST}:{result['upstream_port']}.")
        print(f"Dry run: sidecar would bind {BIND_HOST}:{result['sidecar_port']}.")
    elif action == "would-activate":
        print(f"Dry run: would activate the verified sidecar on {BIND_HOST}:{result['sidecar_port']}.")
    elif action == "activated-existing-install":
        print("Activated the verified Hermes Mobile sidecar installation.")
    elif action in {"paired", "already-paired", "would-pair"}:
        prefix = "Dry run: would write" if action == "would-pair" else "Pairing manifest is at"
        print(f"{prefix} {result['path']} for {result['endpoint']}.")
    elif action in {"would-remove", "removed"}:
        verb = "would remove" if action == "would-remove" else "removed"
        print(f"{result['operation'].capitalize()} {verb} only the verified inventory at {result['root']}.")
    elif action == "not-installed":
        print(f"The Hermes Mobile sidecar is not installed at {result['root']}.")
    elif "compatible" in result:
        print(f"Compatible official Hermes {result['version']} at {result['command']}")
        print(f"Pinned proof baseline: {result['official_release_tag']} / {result['official_commit']}")
    elif "installed" in result:
        if not result["installed"]:
            print(f"The Hermes Mobile sidecar is not installed at {result['root']}.")
        else:
            print(f"Hermes mobile sidecar service: {result['service_state']}")
            print(f"Official upstream: {BIND_HOST}:{result['upstream_port']} (loopback only)")
            print(f"TLS ingress target: {BIND_HOST}:{result['sidecar_port']} (loopback only)")
            print(f"Pairing ready: {'yes' if result['pairing_ready'] else 'no'}")
    else:
        print(json.dumps(result, indent=2, sort_keys=True))


def _add_runtime_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--hermes", help="official Hermes executable")
    parser.add_argument("--python", help="Python from the official Hermes environment")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hermes-mobile-gateway",
        description="Manage the independent Hermes Mobile sidecar for official Hermes v0.20.1.",
    )
    parser.add_argument("--version", action="version", version=INSTALLER_VERSION)
    subparsers = parser.add_subparsers(dest="command", required=True)

    probe = subparsers.add_parser("probe", help="verify the pinned official Hermes runtime")
    _add_runtime_options(probe)
    probe.add_argument("--json", action="store_true")
    probe.set_defaults(handler=_probe_command)

    install = subparsers.add_parser("install", help="install the official-loopback plus sidecar service")
    _add_runtime_options(install)
    install.add_argument("--upstream-port", type=int, default=DEFAULT_UPSTREAM_PORT, choices=range(1024, 65536))
    install.add_argument("--sidecar-port", type=int, default=DEFAULT_SIDECAR_PORT, choices=range(1024, 65536))
    install.add_argument("--endpoint", help="HTTPS ingress URL placed in pairing.json")
    install.add_argument("--no-activate", action="store_true")
    install.add_argument("--dry-run", action="store_true")
    install.add_argument("--json", action="store_true")
    install.set_defaults(handler=_install)

    pair = subparsers.add_parser("pair", help="write the HTTPS/header/ticket pairing manifest")
    pair.add_argument("--endpoint", required=True)
    pair.add_argument("--dry-run", action="store_true")
    pair.add_argument("--json", action="store_true")
    pair.set_defaults(handler=_pair)

    status = subparsers.add_parser("status", help="validate inventory and show service state")
    status.add_argument("--json", action="store_true")
    status.set_defaults(handler=_status)

    for name in ("rollback", "uninstall"):
        remove = subparsers.add_parser(name, help=f"{name} only the verified package inventory")
        remove.add_argument("--dry-run", action="store_true")
        remove.add_argument("--yes", action="store_true")
        remove.add_argument("--json", action="store_true")
        remove.set_defaults(handler=lambda args, operation=name: _remove(args, operation=operation))
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if getattr(args, "upstream_port", None) == getattr(args, "sidecar_port", object()):
        print("error: upstream and sidecar ports must differ", file=sys.stderr)
        return 1
    try:
        result = args.handler(args)
    except InstallError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    _emit(result, as_json=bool(getattr(args, "json", False)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
