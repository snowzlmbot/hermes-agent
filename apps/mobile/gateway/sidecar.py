#!/usr/bin/env python3
"""Credential-isolating mobile proxy for an official loopback Hermes gateway."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import hmac
import logging
import os
import secrets
import stat
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import httpx
import uvicorn
import websockets
from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse, Response


BIND_HOST = "127.0.0.1"
TOKEN_HEADER = "X-Hermes-Session-Token"
TICKET_TTL_SECONDS = 30
SECRET_MODE = 0o600
_TOKEN_BYTES = 32
_HOP_BY_HOP_HEADERS = frozenset(
    {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
)
_EXTERNAL_CREDENTIAL_HEADERS = frozenset(
    {TOKEN_HEADER.lower(), "authorization", "cookie", "proxy-authorization"}
)


@dataclass(frozen=True)
class SidecarConfig:
    external_token: str
    internal_token: str
    upstream_host: str = BIND_HOST
    upstream_port: int = 9119

    @property
    def upstream_http_origin(self) -> str:
        return f"http://{self.upstream_host}:{self.upstream_port}"

    @property
    def upstream_websocket_url(self) -> str:
        query = urlencode({"token": self.internal_token})
        return f"ws://{self.upstream_host}:{self.upstream_port}/api/ws?{query}"


class TicketStore:
    """In-memory hashed tickets with atomic single-use consumption."""

    def __init__(
        self,
        *,
        ttl_seconds: int = TICKET_TTL_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._tickets: dict[bytes, float] = {}
        self._lock = asyncio.Lock()

    @staticmethod
    def _digest(ticket: str) -> bytes:
        return hashlib.sha256(ticket.encode("ascii", "strict")).digest()

    async def mint(self) -> str:
        ticket = secrets.token_urlsafe(_TOKEN_BYTES)
        now = self._clock()
        async with self._lock:
            self._prune(now)
            self._tickets[self._digest(ticket)] = now + self._ttl_seconds
        return ticket

    async def consume(self, ticket: str) -> bool:
        try:
            digest = self._digest(ticket)
        except (UnicodeEncodeError, UnicodeError):
            return False
        now = self._clock()
        async with self._lock:
            self._prune(now)
            expiry = self._tickets.pop(digest, None)
        return expiry is not None and expiry > now

    def _prune(self, now: float) -> None:
        expired = [digest for digest, expiry in self._tickets.items() if expiry <= now]
        for digest in expired:
            self._tickets.pop(digest, None)


def _authenticated(request: Request, expected: str) -> bool:
    supplied = request.headers.get(TOKEN_HEADER, "")
    return bool(supplied) and hmac.compare_digest(supplied.encode(), expected.encode())


def _query_contains_credential(request: Request) -> bool:
    lowered = {key.lower() for key in request.query_params.keys()}
    return bool(lowered & {"token", "access_token", "session_token"})


def _allowed_http_route(method: str, path: str) -> bool:
    method = method.upper()
    if path == "/api/status":
        return method == "GET"
    if path == "/api/auth/ws-ticket":
        return method == "POST"
    if path == "/api/sessions":
        return method == "GET"
    if path.startswith("/api/sessions/") and path.count("/") == 3:
        return method in {"PATCH", "DELETE"}
    if path in {"/api/audio/transcribe", "/api/audio/speak"}:
        return method == "POST"
    return False


def _upstream_headers(request: Request, *, internal_token: str | None) -> dict[str, str]:
    headers: dict[str, str] = {}
    for name, value in request.headers.items():
        lowered = name.lower()
        if lowered in _HOP_BY_HOP_HEADERS or lowered in _EXTERNAL_CREDENTIAL_HEADERS:
            continue
        if lowered in {"host", "content-length"}:
            continue
        headers[name] = value
    if internal_token is not None:
        headers[TOKEN_HEADER] = internal_token
        headers["Authorization"] = f"Bearer {internal_token}"
    return headers


def _downstream_headers(response: httpx.Response) -> dict[str, str]:
    return {
        name: value
        for name, value in response.headers.items()
        if name.lower() not in _HOP_BY_HOP_HEADERS and name.lower() != "content-length"
    }


def create_app(
    config: SidecarConfig,
    *,
    http_transport: httpx.AsyncBaseTransport | None = None,
    websocket_connect: Callable[..., Any] = websockets.connect,
    clock: Callable[[], float] = time.monotonic,
) -> FastAPI:
    app = FastAPI(title="Hermes Mobile Sidecar", docs_url=None, redoc_url=None, openapi_url=None)
    tickets = TicketStore(clock=clock)

    @app.post("/api/auth/ws-ticket")
    async def mint_ws_ticket(request: Request) -> Response:
        if _query_contains_credential(request):
            return JSONResponse({"detail": "credentials are not accepted in URLs"}, status_code=400)
        if not _authenticated(request, config.external_token):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)
        ticket = await tickets.mint()
        return JSONResponse({"ticket": ticket, "ttl_seconds": TICKET_TTL_SECONDS})

    @app.websocket("/api/ws")
    async def websocket_proxy(websocket: WebSocket) -> None:
        ticket_values = websocket.query_params.getlist("ticket")
        if len(websocket.query_params) != 1 or len(ticket_values) != 1:
            await websocket.close(code=4401)
            return
        if not await tickets.consume(ticket_values[0]):
            await websocket.close(code=4401)
            return

        try:
            async with websocket_connect(
                config.upstream_websocket_url,
                open_timeout=10,
                close_timeout=5,
                max_size=None,
                proxy=None,
            ) as upstream:
                await websocket.accept()

                async def mobile_to_upstream() -> None:
                    while True:
                        message = await websocket.receive()
                        kind = message.get("type")
                        if kind == "websocket.disconnect":
                            return
                        if message.get("text") is not None:
                            await upstream.send(message["text"])
                        elif message.get("bytes") is not None:
                            await upstream.send(message["bytes"])

                async def upstream_to_mobile() -> None:
                    while True:
                        message = await upstream.recv()
                        if isinstance(message, str):
                            await websocket.send_text(message)
                        else:
                            await websocket.send_bytes(bytes(message))

                tasks = {
                    asyncio.create_task(mobile_to_upstream()),
                    asyncio.create_task(upstream_to_mobile()),
                }
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    task.result()
        except Exception:
            # Never include the upstream URL in an error: it contains the
            # loopback-only internal token required by official v0.20.1.
            try:
                await websocket.close(code=1011)
            except Exception:
                pass

    @app.api_route(
        "/{path:path}",
        methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    )
    async def rest_proxy(request: Request, path: str) -> Response:
        normalized_path = "/" + path
        if normalized_path == "/api/auth/ws-ticket":
            return JSONResponse({"detail": "Method Not Allowed"}, status_code=405)
        if normalized_path == "/api/ws":
            return JSONResponse({"detail": "WebSocket upgrade required"}, status_code=426)
        if not _allowed_http_route(request.method, normalized_path):
            return JSONResponse({"detail": "Not found"}, status_code=404)
        if _query_contains_credential(request):
            return JSONResponse({"detail": "credentials are not accepted in URLs"}, status_code=400)

        public_status = request.method == "GET" and normalized_path == "/api/status"
        if not public_status and not _authenticated(request, config.external_token):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)

        query_items = list(request.query_params.multi_items())
        upstream_url = httpx.URL(config.upstream_http_origin + normalized_path, params=query_items)
        try:
            async with httpx.AsyncClient(
                transport=http_transport,
                timeout=60.0,
                follow_redirects=False,
            ) as client:
                upstream_response = await client.request(
                    request.method,
                    upstream_url,
                    headers=_upstream_headers(
                        request,
                        internal_token=None if public_status else config.internal_token,
                    ),
                    content=await request.body(),
                )
        except httpx.HTTPError:
            return JSONResponse({"detail": "Upstream unavailable"}, status_code=502)
        return Response(
            content=upstream_response.content,
            status_code=upstream_response.status_code,
            headers=_downstream_headers(upstream_response),
        )

    return app


def _read_secret(path: Path, *, label: str) -> str:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise SystemExit(f"{label} is unavailable: {error}") from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f"{label} must be a regular file")
    if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != SECRET_MODE:
        raise SystemExit(f"{label} must be owned by the current user with mode 0600")
    try:
        token = path.read_text(encoding="ascii").strip()
    except OSError as error:
        raise SystemExit(f"{label} could not be read: {error}") from error
    if len(token) != 64 or any(character not in "0123456789abcdef" for character in token):
        raise SystemExit(f"{label} is invalid")
    return token


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the Hermes Mobile sidecar")
    parser.add_argument("--host", default=BIND_HOST, choices=[BIND_HOST])
    parser.add_argument("--port", type=int, required=True, choices=range(1024, 65536))
    parser.add_argument("--upstream-host", default=BIND_HOST, choices=[BIND_HOST])
    parser.add_argument("--upstream-port", type=int, required=True, choices=range(1024, 65536))
    parser.add_argument("--external-token-file", type=Path, required=True)
    parser.add_argument("--internal-token-file", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.port == args.upstream_port:
        raise SystemExit("sidecar and upstream ports must differ")
    config = SidecarConfig(
        external_token=_read_secret(args.external_token_file, label="external token file"),
        internal_token=_read_secret(args.internal_token_file, label="internal token file"),
        upstream_host=args.upstream_host,
        upstream_port=args.upstream_port,
    )
    logging.getLogger("uvicorn.access").disabled = True
    logging.getLogger("websockets").setLevel(logging.CRITICAL)
    uvicorn.run(
        create_app(config),
        host=args.host,
        port=args.port,
        access_log=False,
        log_level="warning",
        proxy_headers=False,
        ws_ping_interval=None,
        ws_max_size=384 * 1024 * 1024,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
