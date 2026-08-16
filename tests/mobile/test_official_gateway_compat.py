"""Opt-in black-box compatibility test for the pinned official Hermes release.

The launched process uses an isolated HOME, a neutral working directory, and a
sanitized import environment.  This module intentionally imports no Hermes
source.  CI must install the official release separately and provide either
HERMES_OFFICIAL_EXECUTABLE or HERMES_OFFICIAL_PYTHON.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import pytest


OFFICIAL_RELEASE_TAG = "v2026.8.13"
OFFICIAL_COMMIT = "f80f453ae0679347e38abc917c7f94f717bf96c5"
OFFICIAL_VERSION = "0.20.1"
TOKEN_HEADER = "X-Hermes-Session-Token"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _official_command() -> list[str] | None:
    """Resolve the pinned official CLI without silently testing fork code."""
    raw = os.environ.get("HERMES_OFFICIAL_EXECUTABLE", "").strip()
    python = os.environ.get("HERMES_OFFICIAL_PYTHON", "").strip()
    if raw and python:
        raise AssertionError("set only one of HERMES_OFFICIAL_EXECUTABLE or HERMES_OFFICIAL_PYTHON")
    selected = raw or python
    if not selected:
        return None
    resolved = Path(selected).expanduser().resolve(strict=True)

    source_raw = os.environ.get("HERMES_OFFICIAL_SOURCE", "").strip()
    if source_raw:
        source = Path(source_raw).expanduser().resolve(strict=True)
        if source == REPOSITORY_ROOT or not (source / ".git").exists():
            raise AssertionError("official Hermes source must be a distinct Git checkout")
        if source not in resolved.parents:
            raise AssertionError("official Hermes executable must belong to the verified official checkout")
        head = subprocess.run(
            ["git", "-C", str(source), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        remote = subprocess.run(
            ["git", "-C", str(source), "remote", "get-url", "origin"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip().removesuffix(".git")
        if head != OFFICIAL_COMMIT or remote not in {
            "https://github.com/NousResearch/hermes-agent",
            "git@github.com:NousResearch/hermes-agent",
        }:
            raise AssertionError("official Hermes checkout identity does not match the pinned release")
    elif REPOSITORY_ROOT == resolved or REPOSITORY_ROOT in resolved.parents:
        raise AssertionError("official Hermes executable must not come from the fork checkout")

    if python:
        return [str(resolved), "-I", "-m", "hermes_cli.main"]
    return [str(resolved)]

def _run(command: list[str], *args: str, env: dict[str, str], timeout: float = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [*command, *args],
        cwd=env["HERMES_OFFICIAL_NEUTRAL_CWD"],
        env=env,
        text=True,
        capture_output=True,
        timeout=timeout,
    )


def _version_from(output: str) -> str:
    match = re.search(r"(?<!\d)v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)", output)
    if match is None:
        raise AssertionError(f"could not parse official Hermes version from: {output!r}")
    return match.group(1)


def _free_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _json_request(
    url: str,
    *,
    token: str | None = None,
    method: str = "GET",
    body: dict[str, Any] | None = None,
) -> tuple[int, dict[str, Any]]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header(TOKEN_HEADER, token)
        request.add_header("Authorization", f"Bearer {token}")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=5) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        response_body = error.read()
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError:
            parsed = {"raw": response_body.decode("utf-8", "replace")}
        return error.code, parsed


def _wait_ready(process: subprocess.Popen[bytes], base_url: str, log_path: Path) -> dict[str, Any]:
    deadline = time.monotonic() + 60
    last_error = "not attempted"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
            raise AssertionError(f"official hermes serve exited with {process.returncode}: {output}")
        try:
            status, payload = _json_request(f"{base_url}/api/status")
            if status == 200:
                return payload
            last_error = f"HTTP {status}: {payload}"
        except (OSError, ValueError) as error:
            last_error = repr(error)
        time.sleep(0.1)
    output = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
    raise AssertionError(f"official hermes serve did not become ready ({last_error}): {output}")


def _assert_websocket_upgrade(host: str, port: int, ticket: str) -> None:
    websocket_key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
    request = (
        f"GET /api/ws?ticket={ticket} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {websocket_key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    ).encode("ascii")
    with socket.create_connection((host, port), timeout=10) as connection:
        connection.sendall(request)
        response = b""
        while b"\r\n\r\n" not in response and len(response) < 16384:
            chunk = connection.recv(4096)
            if not chunk:
                break
            response += chunk
    status_line = response.split(b"\r\n", 1)[0]
    assert status_line == b"HTTP/1.1 101 Switching Protocols", response[:1000]


def _assert_pinned_identity(command: list[str], env: dict[str, str]) -> dict[str, str]:
    supplied_tag = os.environ.get("HERMES_OFFICIAL_TAG", "")
    supplied_commit = os.environ.get("HERMES_OFFICIAL_COMMIT", "")
    assert supplied_tag == OFFICIAL_RELEASE_TAG, (
        f"HERMES_OFFICIAL_TAG must pin {OFFICIAL_RELEASE_TAG}, got {supplied_tag!r}"
    )
    assert supplied_commit == OFFICIAL_COMMIT, (
        f"HERMES_OFFICIAL_COMMIT must pin {OFFICIAL_COMMIT}, got {supplied_commit!r}"
    )
    result = _run(command, "version", env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    version = _version_from(result.stdout + result.stderr)
    assert version == OFFICIAL_VERSION
    executable_hash = hashlib.sha256(Path(command[0]).read_bytes()).hexdigest()
    return {
        "release_tag": supplied_tag,
        "commit": supplied_commit,
        "version": version,
        "launcher_sha256": executable_hash,
    }


def test_pinned_official_gateway_supports_loopback_session_http_and_websocket() -> None:
    command = _official_command()
    if command is None:
        pytest.skip("set HERMES_OFFICIAL_EXECUTABLE or HERMES_OFFICIAL_PYTHON for official compatibility proof")
    assert command is not None

    with TemporaryDirectory(prefix="hermes-official-mobile-compat-") as directory:
        root = Path(directory)
        home = root / "home"
        hermes_home = home / ".hermes"
        neutral_cwd = root / "neutral-cwd"
        home.mkdir(mode=0o700)
        hermes_home.mkdir(mode=0o700)
        neutral_cwd.mkdir(mode=0o700)
        token = secrets.token_hex(32)
        port = _free_loopback_port()
        base_url = f"http://127.0.0.1:{port}"
        log_path = root / "official-hermes.log"

        env = os.environ.copy()
        env.update(
            {
                "HOME": str(home),
                "HERMES_HOME": str(hermes_home),
                "HERMES_DASHBOARD_SESSION_TOKEN": token,
                "HERMES_OFFICIAL_NEUTRAL_CWD": str(neutral_cwd),
                "PYTHONDONTWRITEBYTECODE": "1",
                "OPENROUTER_API_KEY": "",
                "OPENAI_API_KEY": "",
                "NOUS_API_KEY": "",
            }
        )
        env.pop("PYTHONPATH", None)
        env.pop("PYTHONHOME", None)

        identity = _assert_pinned_identity(command, env)
        initial_status = _run(command, "serve", "--status", env=env)
        assert initial_status.returncode == 0, initial_status.stdout + initial_status.stderr
        assert "No hermes dashboard processes running." in initial_status.stdout

        with log_path.open("wb") as log:
            process = subprocess.Popen(
                [*command, "serve", "--host", "127.0.0.1", "--port", str(port)],
                cwd=neutral_cwd,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        try:
            status_payload = _wait_ready(process, base_url, log_path)
            assert "auth_flows" in status_payload

            unauthorized_status, _ = _json_request(f"{base_url}/api/sessions")
            assert unauthorized_status == 401
            ticket_status, ticket_payload = _json_request(
                f"{base_url}/api/auth/ws-ticket",
                token=token,
                method="POST",
                body={},
            )
            assert ticket_status == 200, ticket_payload
            ticket = ticket_payload.get("ticket")
            assert isinstance(ticket, str) and ticket
            _assert_websocket_upgrade("127.0.0.1", port, ticket)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

        assert not any(REPOSITORY_ROOT == path or REPOSITORY_ROOT in path.parents for path in map(Path, command))
        assert identity == {
            "release_tag": OFFICIAL_RELEASE_TAG,
            "commit": OFFICIAL_COMMIT,
            "version": OFFICIAL_VERSION,
            "launcher_sha256": identity["launcher_sha256"],
        }
