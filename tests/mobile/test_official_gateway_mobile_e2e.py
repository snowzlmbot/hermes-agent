"""Black-box mobile lifecycle through the sidecar and pinned official gateway."""

from __future__ import annotations

import asyncio
import json
import os
import secrets
import selectors
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

import pytest

websockets = pytest.importorskip("websockets")


READY = "HERMES_BACKEND_READY port="
TOKEN_HEADER = "X-Hermes-Session-Token"
OFFICIAL_RELEASE_TAG = "v2026.8.13"
OFFICIAL_COMMIT = "f80f453ae0679347e38abc917c7f94f717bf96c5"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SIDECAR = REPOSITORY_ROOT / "apps" / "mobile" / "gateway" / "sidecar.py"


def _official_command() -> list[str]:
    raw = os.environ.get("HERMES_OFFICIAL_HERMES", "").strip()
    source_raw = os.environ.get("HERMES_OFFICIAL_SOURCE", "").strip()
    if not raw:
        pytest.skip("set HERMES_OFFICIAL_HERMES to the pinned official executable")
    if not source_raw:
        raise AssertionError("HERMES_OFFICIAL_SOURCE must name the distinct official checkout")
    if os.environ.get("HERMES_OFFICIAL_TAG") != OFFICIAL_RELEASE_TAG:
        raise AssertionError(f"HERMES_OFFICIAL_TAG must be {OFFICIAL_RELEASE_TAG}")
    if os.environ.get("HERMES_OFFICIAL_COMMIT") != OFFICIAL_COMMIT:
        raise AssertionError(f"HERMES_OFFICIAL_COMMIT must be {OFFICIAL_COMMIT}")

    command = shlex.split(raw)
    if len(command) != 1:
        raise AssertionError("HERMES_OFFICIAL_HERMES must name one executable")
    executable = shutil.which(command[0]) if not os.path.isabs(command[0]) else command[0]
    if not executable:
        raise AssertionError(f"official Hermes executable not found: {command[0]}")
    path = Path(os.path.abspath(executable))
    assert path.resolve(strict=True).is_file() and os.access(path, os.X_OK)
    source = Path(source_raw).resolve(strict=True)
    if source == REPOSITORY_ROOT or not (source / ".git").exists() or source not in path.parents:
        raise AssertionError("official executable must belong to a distinct official Git checkout")

    def git(*arguments: str) -> str:
        return subprocess.run(
            ["git", "-C", str(source), *arguments],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    remote = git("remote", "get-url", "origin").removesuffix(".git").removesuffix("/")
    if (
        git("rev-parse", "HEAD") != OFFICIAL_COMMIT
        or git("rev-parse", f"refs/tags/{OFFICIAL_RELEASE_TAG}^{{commit}}") != OFFICIAL_COMMIT
        or remote not in {
            "https://github.com/NousResearch/hermes-agent",
            "git@github.com:NousResearch/hermes-agent",
        }
    ):
        raise AssertionError("official checkout does not match the pinned tag and commit")

    first_line = path.open("rb").readline(4096).decode("utf-8").strip()
    if not first_line.startswith("#!") or len(first_line[2:].split()) != 1:
        raise AssertionError("official launcher has an ambiguous interpreter")
    interpreter = Path(os.path.abspath(first_line[2:].split()[0]))
    if source not in interpreter.parents:
        raise AssertionError("official interpreter must belong to the pinned checkout")
    assert interpreter.resolve(strict=True).is_file() and os.access(interpreter, os.X_OK)
    imported = subprocess.run(
        [str(interpreter), "-I", "-c", "import hermes_cli; print(hermes_cli.__file__)"],
        cwd=source,
        env={key: value for key, value in os.environ.items() if key not in {"PYTHONPATH", "PYTHONHOME"}},
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if source not in Path(imported).resolve(strict=True).parents:
        raise AssertionError("hermes_cli imported outside the pinned official checkout")
    return [str(path)]


def _isolated_environment(home: Path) -> dict[str, str]:
    environment = os.environ.copy()
    for key in (
        "PYTHONPATH",
        "PYTHONHOME",
        "VIRTUAL_ENV",
        "HERMES_OFFICIAL_HERMES",
        "HERMES_DESKTOP",
        "HERMES_SERVE_HEADLESS",
        "HERMES_PROFILE",
    ):
        environment.pop(key, None)
    environment.update(
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
            "PYTHONDONTWRITEBYTECODE": "1",
        }
    )
    return environment


def _assert_official_version(executable: list[str], home: Path, environment: dict[str, str]) -> None:
    observed = subprocess.run(
        [*executable, "--version"],
        cwd=home,
        env=environment,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    version = f"{observed.stdout}\n{observed.stderr}".strip()
    assert observed.returncode == 0, f"official hermes --version failed: {version}"
    assert "v0.20.1" in version and "2026.8.13" in version, version


def _json_request(
    url: str,
    *,
    external_token: str | None = None,
    method: str = "GET",
    body: Any = None,
) -> tuple[int, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if external_token is not None:
        request.add_header(TOKEN_HEADER, external_token)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=15) as response:
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
        message = json.loads(await asyncio.wait_for(ws.recv(), timeout=remaining))
        if str(message.get("id")) != request_id:
            continue
        if "error" in message:
            raise AssertionError(f"RPC {method} failed: {message['error']}")
        result = message.get("result", {})
        if not isinstance(result, dict):
            raise AssertionError(f"RPC {method} returned a non-object result")
        return result


class _OfficialGatewayProcess:
    def __init__(self, home: Path, internal_token: str) -> None:
        self.home = home
        self.internal_token = internal_token
        self.process: subprocess.Popen[bytes] | None = None
        self.port: int | None = None
        self.token_file: Path | None = None
        self.output: list[str] = []

    def start(self) -> None:
        executable = _official_command()
        environment = _isolated_environment(self.home)
        _assert_official_version(executable, self.home, environment)
        runtime_dir = self.home / ".hermes" / "desktop-ssh" / ("a" * 32)
        runtime_dir.mkdir(parents=True, mode=0o700)
        token_file = runtime_dir / (("b" * 16) + ".token")
        token_file.write_text(self.internal_token, encoding="utf-8")
        token_file.chmod(0o600)
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
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

        assert self.process.stdout is not None
        descriptor = self.process.stdout.fileno()
        os.set_blocking(descriptor, False)
        selector = selectors.DefaultSelector()
        selector.register(descriptor, selectors.EVENT_READ)
        pending = bytearray()
        deadline = time.monotonic() + 60
        try:
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    break
                for key, _ in selector.select(timeout=0.25):
                    try:
                        chunk = os.read(key.fd, 65536)
                    except BlockingIOError:
                        continue
                    pending.extend(chunk)
                    while b"\n" in pending:
                        raw_line, _, remainder = pending.partition(b"\n")
                        pending = bytearray(remainder)
                        line = raw_line.decode("utf-8", "replace").rstrip("\r")
                        self.output.append(line)
                        if READY in line:
                            self.port = int(line.split("port=", 1)[1].strip())
                            assert self.token_file is not None and not self.token_file.exists()
                            return
        finally:
            selector.close()
        raise AssertionError(f"official gateway failed to start: {self.output[-20:]}")

    def stop(self) -> None:
        process = self.process
        if process is None or process.poll() is not None:
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=15)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)

    @property
    def base_url(self) -> str:
        assert self.port is not None
        return f"http://127.0.0.1:{self.port}"


class _SidecarProcess:
    def __init__(self, home: Path, official: _OfficialGatewayProcess, internal_token: str, external_token: str) -> None:
        self.home = home
        self.official = official
        self.internal_token = internal_token
        self.external_token = external_token
        self.port: int | None = None
        self.process: subprocess.Popen[bytes] | None = None
        self.log_path = home / "sidecar.log"

    def start(self) -> None:
        assert self.official.port is not None
        secret_dir = self.home / "sidecar-secrets"
        secret_dir.mkdir(mode=0o700)
        internal_path = secret_dir / "internal.token"
        external_path = secret_dir / "external.token"
        for path, token in ((internal_path, self.internal_token), (external_path, self.external_token)):
            path.write_text(token + "\n", encoding="ascii")
            path.chmod(0o600)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = int(listener.getsockname()[1])
        environment = _isolated_environment(self.home)
        with self.log_path.open("wb") as log:
            self.process = subprocess.Popen(
                [
                    sys.executable,
                    "-I",
                    str(SIDECAR),
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(self.port),
                    "--upstream-host",
                    "127.0.0.1",
                    "--upstream-port",
                    str(self.official.port),
                    "--external-token-file",
                    str(external_path),
                    "--internal-token-file",
                    str(internal_path),
                ],
                cwd=self.home,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError(self.log_path.read_text(encoding="utf-8", errors="replace"))
            try:
                status, _ = _json_request(f"{self.base_url}/api/status")
                if status == 200:
                    return
            except OSError:
                pass
            time.sleep(0.1)
        raise AssertionError("sidecar did not become ready")

    def stop(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        os.killpg(self.process.pid, signal.SIGTERM)
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)

    @property
    def base_url(self) -> str:
        assert self.port is not None
        return f"http://127.0.0.1:{self.port}"


def _mint_ticket(base_url: str, external_token: str) -> str:
    status, body = _json_request(
        f"{base_url}/api/auth/ws-ticket",
        external_token=external_token,
        method="POST",
    )
    assert status == 200
    assert body["ttl_seconds"] == 30
    return str(body["ticket"])


@pytest.mark.integration
def test_official_gateway_mobile_http_websocket_compatibility() -> None:
    with TemporaryDirectory(prefix="hermes-official-mobile-compat-") as directory:
        home = Path(directory)
        internal_token = secrets.token_hex(32)
        external_token = secrets.token_hex(32)
        official = _OfficialGatewayProcess(home, internal_token)
        sidecar = _SidecarProcess(home, official, internal_token, external_token)
        try:
            official.start()
            sidecar.start()
            status, status_body = _json_request(f"{sidecar.base_url}/api/status")
            assert status == 200
            assert isinstance(status_body, dict)

            unauthorized, _ = _json_request(f"{sidecar.base_url}/api/sessions")
            assert unauthorized == 401
            unauthorized_audio, _ = _json_request(
                f"{sidecar.base_url}/api/audio/speak",
                method="POST",
                body={"text": "mobile compatibility provider boundary"},
            )
            assert unauthorized_audio == 401

            listed_status, listed = _json_request(
                f"{sidecar.base_url}/api/sessions?limit=100&archived=include",
                external_token=external_token,
            )
            assert listed_status == 200
            assert isinstance(listed.get("sessions", []), list)
            asyncio.run(_exercise_websocket_session_contract(sidecar.base_url, external_token))

            valid_audio, audio_body = _json_request(
                f"{sidecar.base_url}/api/audio/speak",
                external_token=external_token,
                method="POST",
                body={"text": "mobile compatibility provider boundary"},
            )
            assert valid_audio in {400, 404, 422, 500, 503}
            assert "mobile compatibility provider boundary" not in json.dumps(audio_body)
        finally:
            sidecar.stop()
            official.stop()
        logs = "\n".join(official.output) + sidecar.log_path.read_text(encoding="utf-8", errors="replace")
        assert external_token not in logs
        assert internal_token not in logs


async def _exercise_websocket_session_contract(base_url: str, external_token: str) -> None:
    first_ticket = _mint_ticket(base_url, external_token)
    first_url = base_url.replace("http://", "ws://", 1) + f"/api/ws?ticket={first_ticket}"
    assert external_token not in first_url
    title = f"mobile-official-compat-{secrets.token_hex(8)}"

    async with websockets.connect(first_url, open_timeout=15, close_timeout=5) as first:
        created = await _rpc(first, "create", "session.create", {"source": "mobile", "profile": "default"})
        runtime_id = created.get("session_id")
        stored_id = created.get("stored_session_id")
        assert runtime_id and stored_id and runtime_id != stored_id
        titled = await _rpc(first, "title", "session.title", {"session_id": runtime_id, "title": title})
        assert titled.get("title") == title
        assert titled.get("session_key") == stored_id
        closed = await _rpc(first, "close", "session.close", {"session_id": runtime_id})
        assert closed.get("closed") is True

    listed_status, listed = _json_request(
        f"{base_url}/api/sessions?limit=100&archived=include",
        external_token=external_token,
    )
    assert listed_status == 200
    assert any(row.get("id") == stored_id for row in listed.get("sessions", []))

    second_ticket = _mint_ticket(base_url, external_token)
    assert second_ticket != first_ticket
    second_url = base_url.replace("http://", "ws://", 1) + f"/api/ws?ticket={second_ticket}"
    async with websockets.connect(second_url, open_timeout=15, close_timeout=5) as second:
        resumed = await _rpc(second, "resume", "session.resume", {"session_id": stored_id, "source": "mobile"})
        resumed_runtime_id = resumed.get("session_id")
        assert resumed_runtime_id and resumed_runtime_id != stored_id
        assert resumed.get("resumed") == stored_id or resumed.get("stored_session_id") == stored_id
        closed = await _rpc(second, "close-resumed", "session.close", {"session_id": resumed_runtime_id})
        assert closed.get("closed") is True
        deleted = await _rpc(second, "delete", "session.delete", {"session_id": stored_id})
        assert deleted.get("deleted") == stored_id

    listed_status, listed = _json_request(
        f"{base_url}/api/sessions?limit=100&archived=include",
        external_token=external_token,
    )
    assert listed_status == 200
    assert not any(row.get("id") == stored_id for row in listed.get("sessions", []))
