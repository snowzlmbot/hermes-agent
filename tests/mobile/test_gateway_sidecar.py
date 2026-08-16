from __future__ import annotations

import asyncio
from typing import Any

import pytest

httpx = pytest.importorskip("httpx")
pytest.importorskip("fastapi")
pytest.importorskip("starlette")
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from apps.mobile.gateway import supervisor
from apps.mobile.gateway.sidecar import SidecarConfig, TicketStore, create_app


EXTERNAL_TOKEN = "a" * 64
INTERNAL_TOKEN = "b" * 64


def test_ticket_store_is_single_use_and_expires() -> None:
    now = [100.0]
    store = TicketStore(clock=lambda: now[0])

    async def exercise() -> None:
        ticket = await store.mint()
        assert await store.consume(ticket) is True
        assert await store.consume(ticket) is False
        expired = await store.mint()
        now[0] += 31
        assert await store.consume(expired) is False

    asyncio.run(exercise())


def test_rest_uses_external_header_and_internal_upstream_header_only() -> None:
    seen: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"ok": True})

    app = create_app(
        SidecarConfig(EXTERNAL_TOKEN, INTERNAL_TOKEN),
        http_transport=httpx.MockTransport(handler),
    )
    transport = httpx.ASGITransport(app=app)

    async def exercise() -> None:
        async with httpx.AsyncClient(transport=transport, base_url="http://sidecar") as client:
            ticket = await client.post(
                "/api/auth/ws-ticket",
                headers={"X-Hermes-Session-Token": EXTERNAL_TOKEN},
            )
            assert ticket.status_code == 200
            assert ticket.json()["ttl_seconds"] == 30
            ticket_value = ticket.json()["ticket"]
            assert ticket_value not in str(ticket.request.url)

            public = await client.get("/api/status", headers={"X-Hermes-Session-Token": EXTERNAL_TOKEN})
            assert public.status_code == 200
            assert "x-hermes-session-token" not in {key.lower() for key in seen[-1].headers}

            private = await client.get(
                "/api/sessions?limit=2",
                headers={
                    "X-Hermes-Session-Token": EXTERNAL_TOKEN,
                    "Authorization": f"Bearer {EXTERNAL_TOKEN}",
                    "Cookie": "session=external",
                },
            )
            assert private.status_code == 200
            request = seen[-1]
            assert request.url.params["limit"] == "2"
            assert EXTERNAL_TOKEN not in str(request.url)
            assert request.headers["X-Hermes-Session-Token"] == INTERNAL_TOKEN
            assert request.headers["Authorization"] == f"Bearer {INTERNAL_TOKEN}"
            assert "cookie" not in {key.lower() for key in request.headers}

            leaked = await client.get(f"/api/sessions?token={EXTERNAL_TOKEN}")
            assert leaked.status_code == 400

            unsupported = await client.get(
                "/login",
                headers={"X-Hermes-Session-Token": EXTERNAL_TOKEN},
            )
            assert unsupported.status_code == 404
            assert len(seen) == 2

    asyncio.run(exercise())


class _FakeUpstream:
    def __init__(self) -> None:
        self.messages: asyncio.Queue[str] = asyncio.Queue()

    async def send(self, message: str | bytes) -> None:
        assert message == "ping"
        await self.messages.put("pong")

    async def recv(self) -> str:
        return await self.messages.get()


class _FakeConnect:
    def __init__(self, upstream: _FakeUpstream) -> None:
        self.upstream = upstream
        self.uri = ""
        self.kwargs: dict[str, Any] = {}

    async def __aenter__(self) -> _FakeUpstream:
        return self.upstream

    async def __aexit__(self, *_args: object) -> None:
        return None


def test_websocket_requires_ticket_and_bridges_with_internal_loopback_token() -> None:
    upstream = _FakeUpstream()
    connection = _FakeConnect(upstream)

    def connect(uri: str, **kwargs: Any) -> _FakeConnect:
        connection.uri = uri
        connection.kwargs = kwargs
        return connection

    app = create_app(
        SidecarConfig(EXTERNAL_TOKEN, INTERNAL_TOKEN),
        websocket_connect=connect,
    )
    with TestClient(app) as client:
        mint = client.post("/api/auth/ws-ticket", headers={"X-Hermes-Session-Token": EXTERNAL_TOKEN})
        assert mint.status_code == 200
        ticket = mint.json()["ticket"]
        assert EXTERNAL_TOKEN not in ticket

        with client.websocket_connect(f"/api/ws?ticket={ticket}") as websocket:
            websocket.send_text("ping")
            assert websocket.receive_text() == "pong"

        assert "?token=" in connection.uri
        assert INTERNAL_TOKEN in connection.uri
        assert EXTERNAL_TOKEN not in connection.uri
        assert connection.kwargs["proxy"] is None

        with pytest.raises(WebSocketDisconnect) as replay:
            with client.websocket_connect(f"/api/ws?ticket={ticket}"):
                pass
        assert replay.value.code == 4401


def test_websocket_ticket_replay_is_rejected() -> None:
    app = create_app(SidecarConfig(EXTERNAL_TOKEN, INTERNAL_TOKEN))
    with TestClient(app) as client:
        with pytest.raises(WebSocketDisconnect) as rejected:
            with client.websocket_connect("/api/ws?ticket=not-a-ticket"):
                pass
        assert rejected.value.code == 4401


def test_supervisor_keeps_internal_token_out_of_sidecar_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[list[str], dict[str, str]]] = []

    class FakeProcess:
        def __init__(self, command: list[str], *, env: dict[str, str], **_kwargs: Any) -> None:
            calls.append((command, dict(env)))

        def poll(self) -> int:
            return 0

        def terminate(self) -> None:
            pass

        def wait(self, timeout: float | None = None) -> int:
            return 0

        def kill(self) -> None:
            pass

    monkeypatch.setenv("HERMES_DASHBOARD_SESSION_TOKEN", "ambient-secret")
    monkeypatch.setenv("OPENAI_API_KEY", "provider-secret")
    monkeypatch.setattr(supervisor, "_read_secret", lambda path, label: INTERNAL_TOKEN if "internal" in label else EXTERNAL_TOKEN)
    monkeypatch.setattr(supervisor, "_wait_for_upstream", lambda process, port: None)
    monkeypatch.setattr(supervisor.subprocess, "Popen", FakeProcess)
    monkeypatch.setattr(supervisor.signal, "signal", lambda *_args: None)

    result = supervisor.main(
        [
            "--hermes", "/usr/bin/hermes",
            "--sidecar", "/managed/sidecar.py",
            "--internal-token-file", "/managed/internal.token",
            "--external-token-file", "/managed/external.token",
            "--upstream-port", "9119",
            "--sidecar-port", "9120",
        ]
    )

    assert result == 1
    assert len(calls) == 2
    official_command, official_env = calls[0]
    sidecar_command, sidecar_env = calls[1]
    assert official_command[:2] == ["/usr/bin/hermes", "serve"]
    assert official_env["HERMES_DASHBOARD_SESSION_TOKEN"] == INTERNAL_TOKEN
    assert "--internal-token-file" in sidecar_command
    assert INTERNAL_TOKEN not in sidecar_command
    assert "HERMES_DASHBOARD_SESSION_TOKEN" not in sidecar_env
    assert "OPENAI_API_KEY" not in sidecar_env
    assert "provider-secret" not in sidecar_env.values()
