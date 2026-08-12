"""No-secret E2E against the real headless Hermes gateway composition.

This test starts ``hermes serve`` in a temporary HERMES_HOME and talks to its
real HTTP and WebSocket routes. It never starts a fake gateway, invokes a
provider, or persists a credential outside a 0600 one-shot token file.
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import pytest
import websockets

from hermes_state import SessionDB


READY = "HERMES_BACKEND_READY port="
TOKEN_HEADER = "X-Hermes-Session-Token"


def _json_request(url: str, *, token: str | None = None, method: str = "GET", body: Any = None) -> tuple[int, dict[str, Any]]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            body_json = json.loads(payload)
        except json.JSONDecodeError:
            body_json = {"raw": payload.decode("utf-8", "replace")}
        return error.code, body_json


def _rpc_message(request_id: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}


async def _rpc(ws, request_id: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
    await ws.send(json.dumps(_rpc_message(request_id, method, params)))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        message = json.loads(await ws.recv())
        if str(message.get("id")) == request_id:
            if "error" in message:
                raise AssertionError(f"RPC {method} failed: {message['error']}")
            return message.get("result", {})
    raise AssertionError(f"Timed out waiting for RPC response: {method}")


class _GatewayProcess:
    def __init__(self, home: Path, token: str) -> None:
        self.home = home
        self.token = token
        self.process: subprocess.Popen[str] | None = None
        self.port: int | None = None
        self.token_file: Path | None = None

    def start(self) -> None:
        runtime_dir = self.home / ".hermes" / "desktop-ssh" / ("a" * 32)
        runtime_dir.mkdir(parents=True, mode=0o700)
        token_file = runtime_dir / (("b" * 16) + ".token")
        token_file.write_text(self.token, encoding="utf-8")
        token_file.chmod(0o600)
        self.token_file = token_file
        env = os.environ.copy()
        env.update(
            {
                "HOME": str(self.home),
                "HERMES_HOME": str(self.home),
                "OPENROUTER_API_KEY": "",
                "OPENAI_API_KEY": "",
                "NOUS_API_KEY": "",
                "HERMES_DASHBOARD_SESSION_TOKEN": "",
            }
        )
        self.process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "hermes_cli.main",
                "serve",
                "--host",
                "127.0.0.1",
                "--port",
                "0",
                "--skip-build",
                "--ssh-session-token-file",
                str(token_file),
            ],
            cwd=Path(__file__).parents[2],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        deadline = time.monotonic() + 45
        lines: list[str] = []
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError(f"Hermes serve exited before readiness: {self.process.returncode}; output={lines[-12:]}")
            line = self.process.stdout.readline() if self.process.stdout else ""
            if line:
                lines.append(line.rstrip())
                if READY in line:
                    self.port = int(line.split("port=", 1)[1].strip())
                    assert self.token_file is not None and not self.token_file.exists()
                    return
            else:
                time.sleep(0.05)
        raise AssertionError(f"Timed out waiting for Hermes serve readiness; output={lines[-12:]}")

    def stop(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.send_signal(signal.SIGTERM)
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)

    @property
    def base_url(self) -> str:
        if self.port is None:
            raise AssertionError("Gateway is not ready")
        return f"http://127.0.0.1:{self.port}"


@pytest.mark.integration
def test_real_gateway_mobile_http_websocket_session_and_audio_contract() -> None:
    with TemporaryDirectory(prefix="hermes-mobile-e2e-") as directory:
        home = Path(directory)
        stored_id = "mobile-e2e-stored"
        token = secrets.token_hex(32)
        db = SessionDB(db_path=home / "state.db")
        try:
            db.create_session(stored_id, source="mobile")
            assert db.set_session_title(stored_id, "Mobile E2E")
        finally:
            db.close()

        gateway = _GatewayProcess(home, token)
        try:
            gateway.start()
            status, status_body = _json_request(f"{gateway.base_url}/api/status")
            assert status == 200
            assert "auth_flows" in status_body

            unauthorized, _ = _json_request(f"{gateway.base_url}/api/sessions")
            assert unauthorized == 401
            unauthorized_audio, _ = _json_request(
                f"{gateway.base_url}/api/audio/speak",
                method="POST",
                body={"text": "no provider"},
            )
            assert unauthorized_audio == 401

            listed_status, listed = _json_request(
                f"{gateway.base_url}/api/sessions?limit=100&archived=include",
                token=token,
            )
            assert listed_status == 200
            listed_rows = listed.get("sessions", [])
            assert any(row.get("id") == stored_id for row in listed_rows)

            import asyncio

            asyncio.run(_exercise_websocket_resume(gateway.base_url, token, stored_id))

            valid_audio, audio_body = _json_request(
                f"{gateway.base_url}/api/audio/speak",
                token=token,
                method="POST",
                body={"text": "provider boundary"},
            )
            assert valid_audio in {400, 404, 422, 500, 503}
            assert "provider boundary" not in json.dumps(audio_body)
        finally:
            gateway.stop()


async def _exercise_websocket_resume(base_url: str, token: str, stored_id: str) -> None:
    ws_url = base_url.replace("http://", "ws://") + f"/api/ws?token={token}"
    async with websockets.connect(ws_url, open_timeout=10, close_timeout=5) as first:
        created = await _rpc(first, "create", "session.create", {"source": "mobile", "profile": "default"})
        assert created.get("session_id")
        assert created.get("stored_session_id")
        assert created["session_id"] != created["stored_session_id"]

        resumed = await _rpc(first, "resume-one", "session.resume", {"session_id": stored_id, "source": "mobile"})
        first_runtime_id = resumed.get("session_id")
        assert resumed.get("resumed") == stored_id or resumed.get("stored_session_id") == stored_id
        assert first_runtime_id
        await _rpc(first, "close-one", "session.close", {"session_id": first_runtime_id})

    async with websockets.connect(ws_url, open_timeout=10, close_timeout=5) as second:
        resumed_again = await _rpc(
            second,
            "resume-two",
            "session.resume",
            {"session_id": stored_id, "source": "mobile"},
        )
        assert resumed_again.get("resumed") == stored_id or resumed_again.get("stored_session_id") == stored_id
        assert resumed_again.get("session_id")
        assert resumed_again["session_id"] != first_runtime_id

        await _rpc(second, "close", "session.close", {"session_id": resumed_again["session_id"]})
        deleted = await _rpc(second, "delete", "session.delete", {"session_id": stored_id})
        assert deleted.get("deleted") == stored_id

    listed_status, listed = _json_request(f"{base_url}/api/sessions?limit=100&archived=include", token=token)
    assert listed_status == 200
    assert not any(row.get("id") == stored_id for row in listed.get("sessions", []))
