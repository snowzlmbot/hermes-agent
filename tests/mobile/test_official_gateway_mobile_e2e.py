"""Black-box compatibility E2E for an installed official Hermes gateway.

The mobile clients deliberately depend on the public ``hermes serve``
contract, not on Python modules from the mobile checkout.  The workflow sets
``HERMES_OFFICIAL_HERMES`` to an executable from a separate official checkout;
this test then talks to that process only through HTTP and WebSocket routes.

The test never imports Hermes internals, invokes a model provider, or writes
outside a temporary ``HERMES_HOME``.  The only credential is a random,
one-shot session token passed through a mode-0600 token file and then sent
over loopback.
"""

from __future__ import annotations

import asyncio
import json
import os
import secrets
import selectors
import shlex
import shutil
import signal
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import pytest
import websockets


READY = "HERMES_BACKEND_READY port="
TOKEN_HEADER = "X-Hermes-Session-Token"


def _official_command() -> list[str]:
    """Resolve the official CLI without silently testing this checkout."""
    raw = os.environ.get("HERMES_OFFICIAL_HERMES", "").strip()
    if not raw:
        pytest.skip("set HERMES_OFFICIAL_HERMES to an installed official hermes executable")

    command = shlex.split(raw)
    if len(command) != 1:
        raise AssertionError(
            "HERMES_OFFICIAL_HERMES must name one executable; put wrapper arguments in the workflow command"
        )
    executable = shutil.which(command[0]) if not os.path.isabs(command[0]) else command[0]
    if not executable:
        raise AssertionError(f"official Hermes executable not found: {command[0]}")
    path = Path(executable).resolve()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise AssertionError(f"official Hermes executable is not executable: {path}")
    return [str(path)]


def _isolated_environment(home: Path) -> dict[str, str]:
    env = os.environ.copy()
    for key in (
        "PYTHONPATH",
        "VIRTUAL_ENV",
        "HERMES_OFFICIAL_HERMES",
        "HERMES_DESKTOP",
        "HERMES_SERVE_HEADLESS",
        "HERMES_PROFILE",
    ):
        env.pop(key, None)
    env.update(
        {
            "HOME": str(home),
            "HERMES_HOME": str(home),
            "XDG_CONFIG_HOME": str(home / ".config"),
            "XDG_DATA_HOME": str(home / ".local" / "share"),
            "XDG_CACHE_HOME": str(home / ".cache"),
            "XDG_STATE_HOME": str(home / ".local" / "state"),
            "OPENROUTER_API_KEY": "",
            "OPENAI_API_KEY": "",
            "NOUS_API_KEY": "",
            "HERMES_DASHBOARD_SESSION_TOKEN": "",
        }
    )
    return env


def _assert_official_version(executable: list[str], home: Path, env: dict[str, str]) -> None:
    observed = subprocess.run(
        [*executable, "--version"],
        cwd=home,
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    version = f"{observed.stdout}\n{observed.stderr}".strip()
    assert observed.returncode == 0, f"official hermes --version failed: {version}"
    assert "v0.20.1" in version and "2026.8.13" in version, (
        "unexpected official Hermes release from hermes --version: "
        f"{version}"
    )


def _json_request(
    url: str,
    *,
    token: str | None = None,
    method: str = "GET",
    body: Any = None,
) -> tuple[int, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = response.read()
            return response.status, json.loads(payload)
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            parsed: Any = json.loads(payload)
        except json.JSONDecodeError:
            parsed = {"raw": payload.decode("utf-8", "replace")}
        return error.code, parsed


def _rpc_message(request_id: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}


async def _rpc(ws: Any, request_id: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
    await ws.send(json.dumps(_rpc_message(request_id, method, params)))
    deadline = time.monotonic() + 20
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AssertionError(f"timed out waiting for RPC response: {method}")
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        message = json.loads(raw)
        if str(message.get("id")) != request_id:
            continue
        if "error" in message:
            raise AssertionError(f"RPC {method} failed: {message['error']}")
        result = message.get("result", {})
        if not isinstance(result, dict):
            raise AssertionError(f"RPC {method} returned a non-object result: {result!r}")
        return result


class _OfficialGatewayProcess:
    def __init__(self, home: Path, token: str) -> None:
        self.home = home
        self.token = token
        self.process: subprocess.Popen[bytes] | None = None
        self.port: int | None = None
        self.token_file: Path | None = None
        self.output: list[str] = []

    def start(self) -> None:
        executable = _official_command()
        env = _isolated_environment(self.home)
        _assert_official_version(executable, self.home, env)

        runtime_dir = self.home / ".hermes" / "desktop-ssh" / ("a" * 32)
        runtime_dir.mkdir(parents=True, mode=0o700)
        token_file = runtime_dir / (("b" * 16) + ".token")
        token_file.write_text(self.token, encoding="utf-8")
        token_file.chmod(0o600)
        assert token_file.stat().st_mode & 0o777 == 0o600
        self.token_file = token_file

        self.process = subprocess.Popen(
            [
                *executable,
                "serve",
                "--host",
                "127.0.0.1",
                "--port",
                "0",
                "--skip-build",
                "--ssh-session-token-file",
                str(token_file),
            ],
            cwd=self.home,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

        assert self.process.stdout is not None
        stdout_fd = self.process.stdout.fileno()
        os.set_blocking(stdout_fd, False)
        selector = selectors.DefaultSelector()
        selector.register(stdout_fd, selectors.EVENT_READ)
        pending = bytearray()
        deadline = time.monotonic() + 60
        try:
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    break
                events = selector.select(timeout=0.25)
                for key, _ in events:
                    try:
                        chunk = os.read(key.fd, 65536)
                    except BlockingIOError:
                        continue
                    if not chunk:
                        continue
                    pending.extend(chunk)
                    while b"\n" in pending:
                        raw_line, _, remainder = pending.partition(b"\n")
                        pending = bytearray(remainder)
                        line = raw_line.decode("utf-8", "replace").rstrip("\r")
                        self.output.append(line)
                        if READY in line:
                            self.port = int(line.split("port=", 1)[1].strip())
                            assert self.token_file is not None
                            assert not self.token_file.exists(), "official gateway did not consume token file"
                            return
        finally:
            selector.close()

        returncode = self.process.poll()
        raise AssertionError(
            "official hermes serve did not become ready "
            f"(returncode={returncode}, output={self.output[-20:]})"
        )

    def stop(self) -> None:
        process = self.process
        if process is None or process.poll() is not None:
            return
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.send_signal(signal.SIGTERM)
            process.wait(timeout=15)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            if process.poll() is None:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                process.wait(timeout=5)
        finally:
            if self.token_file is not None and self.token_file.exists():
                self.token_file.unlink()

    @property
    def base_url(self) -> str:
        if self.port is None:
            raise AssertionError("official gateway is not ready")
        return f"http://127.0.0.1:{self.port}"


@pytest.mark.integration
def test_official_gateway_mobile_http_websocket_compatibility() -> None:
    """Exercise the mobile contract against the official gateway boundary."""
    with TemporaryDirectory(prefix="hermes-official-mobile-compat-") as directory:
        home = Path(directory)
        token = secrets.token_hex(32)
        gateway = _OfficialGatewayProcess(home, token)
        try:
            gateway.start()

            status, status_body = _json_request(f"{gateway.base_url}/api/status")
            assert status == 200
            assert isinstance(status_body, dict)
            assert any(key in status_body for key in ("version", "gateway_running", "gateway_state"))

            unauthorized, _ = _json_request(f"{gateway.base_url}/api/sessions")
            assert unauthorized == 401
            unauthorized_audio, _ = _json_request(
                f"{gateway.base_url}/api/audio/speak",
                method="POST",
                body={"text": "mobile compatibility provider boundary"},
            )
            assert unauthorized_audio == 401

            listed_status, listed = _json_request(
                f"{gateway.base_url}/api/sessions?limit=100&archived=include",
                token=token,
            )
            assert listed_status == 200
            assert isinstance(listed, dict)
            assert isinstance(listed.get("sessions", []), list)

            asyncio.run(_exercise_websocket_session_contract(gateway.base_url, token))

            valid_audio, audio_body = _json_request(
                f"{gateway.base_url}/api/audio/speak",
                token=token,
                method="POST",
                body={"text": "mobile compatibility provider boundary"},
            )
            assert valid_audio in {400, 404, 422, 500, 503}
            assert "mobile compatibility provider boundary" not in json.dumps(audio_body)
        finally:
            gateway.stop()


async def _exercise_websocket_session_contract(base_url: str, token: str) -> None:
    ws_url = base_url.replace("http://", "ws://", 1) + f"/api/ws?token={token}"
    title = f"mobile-official-compat-{secrets.token_hex(8)}"

    async with websockets.connect(ws_url, open_timeout=15, close_timeout=5) as first:
        created = await _rpc(
            first,
            "create",
            "session.create",
            {"source": "mobile", "profile": "default"},
        )
        runtime_id = created.get("session_id")
        stored_id = created.get("stored_session_id")
        assert runtime_id and stored_id and runtime_id != stored_id

        titled = await _rpc(
            first,
            "title",
            "session.title",
            {"session_id": runtime_id, "title": title},
        )
        assert titled.get("title") == title
        assert titled.get("session_key") == stored_id
        closed = await _rpc(first, "close", "session.close", {"session_id": runtime_id})
        assert closed.get("closed") is True

    listed_status, listed = _json_request(
        f"{base_url}/api/sessions?limit=100&archived=include",
        token=token,
    )
    assert listed_status == 200
    rows = listed.get("sessions", [])
    assert any(row.get("id") == stored_id for row in rows)

    async with websockets.connect(ws_url, open_timeout=15, close_timeout=5) as second:
        resumed = await _rpc(
            second,
            "resume",
            "session.resume",
            {"session_id": stored_id, "source": "mobile"},
        )
        resumed_runtime_id = resumed.get("session_id")
        assert resumed_runtime_id and resumed_runtime_id != stored_id
        assert resumed.get("resumed") == stored_id or resumed.get("stored_session_id") == stored_id

        closed = await _rpc(second, "close-resumed", "session.close", {"session_id": resumed_runtime_id})
        assert closed.get("closed") is True
        deleted = await _rpc(second, "delete", "session.delete", {"session_id": stored_id})
        assert deleted.get("deleted") == stored_id

    listed_status, listed = _json_request(
        f"{base_url}/api/sessions?limit=100&archived=include",
        token=token,
    )
    assert listed_status == 200
    assert not any(row.get("id") == stored_id for row in listed.get("sessions", []))
