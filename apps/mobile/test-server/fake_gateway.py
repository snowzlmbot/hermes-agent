from __future__ import annotations

import base64
import hashlib
import http.server
import json
import os
import socket
import struct
import threading
import time
import urllib.parse
import urllib.request
import uuid
from collections import deque
from dataclasses import dataclass
from typing import Any

_WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _read_exact(stream: Any, count: int) -> bytes:
    chunks: list[bytes] = []
    remaining = count
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            raise ConnectionError("WebSocket closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _encode_frame(payload: bytes, *, opcode: int = 0x1, masked: bool = False) -> bytes:
    first = 0x80 | opcode
    length = len(payload)
    if length < 126:
        header = bytes((first, (0x80 if masked else 0) | length))
    elif length <= 0xFFFF:
        header = bytes((first, (0x80 if masked else 0) | 126)) + struct.pack("!H", length)
    else:
        header = bytes((first, (0x80 if masked else 0) | 127)) + struct.pack("!Q", length)

    if not masked:
        return header + payload

    mask = os.urandom(4)
    encoded = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return header + mask + encoded


def _decode_frame(stream: Any) -> tuple[int, bytes]:
    first, second = _read_exact(stream, 2)
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", _read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", _read_exact(stream, 8))[0]
    mask = _read_exact(stream, 4) if masked else b""
    payload = _read_exact(stream, length)
    if masked:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return opcode, payload


@dataclass
class _StoredSession:
    id: str
    title: str
    preview: str
    started_at: float
    messages: list[dict[str, Any]]
    archived: bool = False
    pinned: bool = False


class FakeHermesGateway:
    """Deterministic local Hermes protocol peer used by native-client tests."""

    def __init__(self, *, token: str = "mobile-test-token") -> None:
        self.token = token
        self.rpc_requests = 0
        self._tickets: set[str] = set()
        self._native_codes: dict[str, str] = {}
        self._access_tokens: set[str] = set()
        self._refresh_tokens: set[str] = set()
        self._lock = threading.Lock()
        self._server: http.server.ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._runtime_to_stored: dict[str, str] = {}
        self._sessions: dict[str, _StoredSession] = {
            "mobile-demo-session": _StoredSession(
                id="mobile-demo-session",
                title="Welcome",
                preview="Hermes mobile contract fixture",
                started_at=1_700_000_000,
                messages=[
                    {
                        "id": 1,
                        "role": "assistant",
                        "content": "Connected to Hermes.",
                        "timestamp": 1_700_000_000,
                    }
                ],
            )
        }

    @property
    def http_url(self) -> str:
        if self._server is None:
            raise RuntimeError("gateway is not running")
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"

    @property
    def ws_url(self) -> str:
        return self.http_url.replace("http://", "ws://", 1) + "/api/ws"

    def start(self) -> None:
        if self._server is not None:
            return
        gateway = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                del format, args
                return

            def _body(self) -> dict[str, Any]:
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = 0
                if length <= 0:
                    return {}
                try:
                    payload = json.loads(self.rfile.read(length))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    return {}
                return payload if isinstance(payload, dict) else {}

            def _authenticated(self) -> bool:
                session_token = self.headers.get("X-Hermes-Session-Token", "")
                bearer = self.headers.get("Authorization", "")
                bearer_token = bearer.removeprefix("Bearer ") if bearer.startswith("Bearer ") else ""
                with gateway._lock:
                    oauth_valid = bearer_token in gateway._access_tokens
                return session_token == gateway.token or bearer_token == gateway.token or oauth_valid

            def _json(self, status: int, payload: dict[str, Any]) -> None:
                encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(encoded)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(encoded)

            def _require_auth(self) -> bool:
                if self._authenticated():
                    return True
                self._json(401, {"detail": "authentication required"})
                return False

            def do_GET(self) -> None:  # noqa: N802
                parsed = urllib.parse.urlsplit(self.path)
                if parsed.path == "/api/status":
                    self._json(
                        200,
                        {
                            "version": "test",
                            "gateway_running": True,
                            "gateway_state": "running",
                            "auth_required": True,
                            "auth_providers": ["fixture"],
                            "auth_flows": ["cookie", "native_pkce", "native_pkce_mobile"],
                        },
                    )
                    return
                if parsed.path == "/auth/native/authorize":
                    query = urllib.parse.parse_qs(parsed.query)
                    redirect_uri = (query.get("redirect_uri") or [""])[0]
                    challenge = (query.get("code_challenge") or [""])[0]
                    method = (query.get("code_challenge_method") or [""])[0]
                    state = (query.get("state") or [""])[0]
                    if redirect_uri != gateway.mobile_redirect_uri:
                        self._json(400, {"detail": "unregistered native redirect_uri"})
                        return
                    if method.upper() != "S256" or not challenge or not state:
                        self._json(400, {"detail": "valid S256 PKCE and state are required"})
                        return
                    code = uuid.uuid4().hex
                    with gateway._lock:
                        gateway._native_codes[code] = challenge
                    location = f"{redirect_uri}?{urllib.parse.urlencode({'code': code, 'state': state})}"
                    self.send_response(302)
                    self.send_header("Location", location)
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if parsed.path == "/api/sessions":
                    if not self._require_auth():
                        return
                    query = urllib.parse.parse_qs(parsed.query)
                    archived = (query.get("archived") or ["exclude"])[0]
                    try:
                        limit = max(0, min(100, int((query.get("limit") or [20])[0])))
                        offset = max(0, int((query.get("offset") or [0])[0]))
                    except (TypeError, ValueError):
                        self._json(400, {"detail": "invalid pagination"})
                        return
                    with gateway._lock:
                        rows = sorted(
                            gateway._sessions.values(), key=lambda row: row.started_at, reverse=True
                        )
                        if archived == "exclude":
                            rows = [row for row in rows if not row.archived]
                        elif archived == "only":
                            rows = [row for row in rows if row.archived]
                        elif archived != "include":
                            self._json(400, {"detail": "invalid archived filter"})
                            return
                        total = len(rows)
                        page = rows[offset : offset + limit]
                        payload = [gateway._session_payload(row) for row in page]
                    self._json(
                        200,
                        {"sessions": payload, "total": total, "limit": limit, "offset": offset},
                    )
                    return
                if parsed.path == "/api/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
                    gateway._handle_websocket(self, parsed)
                    return
                self._json(404, {"detail": "not found"})

            def do_POST(self) -> None:  # noqa: N802
                parsed = urllib.parse.urlsplit(self.path)
                if parsed.path == "/api/auth/ws-ticket":
                    if not self._require_auth():
                        return
                    ticket = uuid.uuid4().hex
                    with gateway._lock:
                        gateway._tickets.add(ticket)
                    self._json(200, {"ticket": ticket, "ttl_seconds": 30})
                    return
                if parsed.path == "/auth/native/token":
                    payload = self._body()
                    code = str(payload.get("code") or "")
                    verifier = str(payload.get("code_verifier") or "")
                    with gateway._lock:
                        challenge = gateway._native_codes.pop(code, None)
                    if not challenge or gateway.pkce_challenge(verifier) != challenge:
                        self._json(400, {"detail": "Invalid or expired authorization code."})
                        return
                    self._json(200, gateway._issue_oauth_tokens())
                    return
                if parsed.path == "/auth/native/refresh":
                    payload = self._body()
                    refresh_token = str(payload.get("refresh_token") or "")
                    with gateway._lock:
                        valid = refresh_token in gateway._refresh_tokens
                        gateway._refresh_tokens.discard(refresh_token)
                    if not valid:
                        self._json(
                            401,
                            {
                                "error": "session_expired",
                                "detail": "Refresh token expired or invalid; start a new sign-in.",
                            },
                        )
                        return
                    self._json(200, gateway._issue_oauth_tokens())
                    return
                if parsed.path == "/api/audio/transcribe":
                    if not self._require_auth():
                        return
                    payload = self._body()
                    if not str(payload.get("data_url") or "").startswith("data:audio/"):
                        self._json(400, {"detail": "audio data_url required"})
                        return
                    self._json(
                        200,
                        {"ok": True, "provider": "fixture", "transcript": "Test voice note"},
                    )
                    return
                if parsed.path == "/api/audio/speak":
                    if not self._require_auth():
                        return
                    payload = self._body()
                    if not str(payload.get("text") or "").strip():
                        self._json(400, {"detail": "text required"})
                        return
                    audio = base64.b64encode(b"RIFF-fixture").decode("ascii")
                    self._json(
                        200,
                        {
                            "ok": True,
                            "mime_type": "audio/wav",
                            "data_url": f"data:audio/wav;base64,{audio}",
                        },
                    )
                    return
                self._json(404, {"detail": "not found"})

            def do_PATCH(self) -> None:  # noqa: N802
                parsed = urllib.parse.urlsplit(self.path)
                prefix = "/api/sessions/"
                if not parsed.path.startswith(prefix):
                    self._json(404, {"detail": "not found"})
                    return
                if not self._require_auth():
                    return
                stored_id = urllib.parse.unquote(parsed.path[len(prefix) :])
                payload = self._body()
                with gateway._lock:
                    session = gateway._sessions.get(stored_id)
                    if session is None:
                        self._json(404, {"detail": "Session not found"})
                        return
                    if not any(key in payload for key in ("title", "archived", "pinned")):
                        self._json(400, {"detail": "Nothing to update"})
                        return
                    if "title" in payload:
                        session.title = str(payload.get("title") or "")
                    if "archived" in payload:
                        session.archived = bool(payload["archived"])
                    if "pinned" in payload:
                        session.pinned = bool(payload["pinned"])
                    result = {"ok": True, "title": session.title}
                    if "archived" in payload:
                        result["archived"] = session.archived
                    if "pinned" in payload:
                        result["pinned"] = session.pinned
                self._json(200, result)

            def do_DELETE(self) -> None:  # noqa: N802
                parsed = urllib.parse.urlsplit(self.path)
                prefix = "/api/sessions/"
                if not parsed.path.startswith(prefix):
                    self._json(404, {"detail": "not found"})
                    return
                if not self._require_auth():
                    return
                stored_id = urllib.parse.unquote(parsed.path[len(prefix) :])
                with gateway._lock:
                    existed = gateway._sessions.pop(stored_id, None) is not None
                    gateway._runtime_to_stored = {
                        runtime: stored
                        for runtime, stored in gateway._runtime_to_stored.items()
                        if stored != stored_id
                    }
                self._json(200, {"ok": True, **({"already_absent": True} if not existed else {})})

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        server, thread = self._server, self._thread
        self._server = None
        self._thread = None
        if server is not None:
            server.shutdown()
            server.server_close()
        if thread is not None:
            thread.join(timeout=3)

    @property
    def mobile_redirect_uri(self) -> str:
        return "com.snowzlmbot.hermes.mobile:/oauth/callback"

    @staticmethod
    def pkce_challenge(verifier: str) -> str:
        digest = hashlib.sha256(verifier.encode("ascii")).digest()
        return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")

    def _issue_oauth_tokens(self) -> dict[str, Any]:
        access_token = f"fixture-at-{uuid.uuid4().hex}"
        refresh_token = f"fixture-rt-{uuid.uuid4().hex}"
        with self._lock:
            self._access_tokens.add(access_token)
            self._refresh_tokens.add(refresh_token)
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "Bearer",
            "expires_at": int(time.time()) + 3600,
            "provider": "fixture",
            "user_id": "fixture-user",
        }

    def _authorize_websocket(self, query: dict[str, list[str]]) -> bool:
        token = (query.get("token") or [""])[0]
        if token and token == self.token:
            return True
        ticket = (query.get("ticket") or [""])[0]
        if not ticket:
            return False
        with self._lock:
            if ticket not in self._tickets:
                return False
            self._tickets.remove(ticket)
        return True

    def _handle_websocket(
        self, handler: http.server.BaseHTTPRequestHandler, parsed: urllib.parse.SplitResult
    ) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        if not self._authorize_websocket(query):
            encoded = b'{"detail":"authentication required"}'
            handler.send_response(401)
            handler.send_header("Content-Type", "application/json")
            handler.send_header("Content-Length", str(len(encoded)))
            handler.send_header("Connection", "close")
            handler.end_headers()
            handler.wfile.write(encoded)
            handler.close_connection = True
            return

        key = handler.headers.get("Sec-WebSocket-Key", "")
        if not key:
            handler.send_error(400, "Sec-WebSocket-Key required")
            return
        accept = base64.b64encode(
            hashlib.sha1((key + _WEBSOCKET_GUID).encode("ascii")).digest()
        ).decode("ascii")
        handler.send_response(101, "Switching Protocols")
        handler.send_header("Upgrade", "websocket")
        handler.send_header("Connection", "Upgrade")
        handler.send_header("Sec-WebSocket-Accept", accept)
        handler.end_headers()
        handler.wfile.flush()
        handler.close_connection = True

        self._send_json(
            handler.wfile,
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "gateway.ready",
                    "payload": {"change_events": True, "skin": {"name": "default"}},
                },
            },
        )
        while True:
            try:
                opcode, payload = _decode_frame(handler.rfile)
            except (ConnectionError, OSError, ValueError):
                break
            if opcode == 0x8:
                break
            if opcode == 0x9:
                handler.wfile.write(_encode_frame(payload, opcode=0xA))
                handler.wfile.flush()
                continue
            if opcode != 0x1:
                continue
            try:
                request = json.loads(payload.decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                self._send_json(
                    handler.wfile,
                    {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}},
                )
                continue
            response, events = self._dispatch(request)
            self._send_json(handler.wfile, response)
            for event in events:
                self._send_json(handler.wfile, event)

    @staticmethod
    def _send_json(stream: Any, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        stream.write(_encode_frame(encoded))
        stream.flush()

    @staticmethod
    def _ok(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    @staticmethod
    def _error(request_id: Any, code: int, message: str) -> dict[str, Any]:
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": code, "message": message},
        }

    @staticmethod
    def _event(event_type: str, session_id: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        params: dict[str, Any] = {"type": event_type, "session_id": session_id}
        if payload is not None:
            params["payload"] = payload
        return {"jsonrpc": "2.0", "method": "event", "params": params}

    def _session_payload(self, session: _StoredSession) -> dict[str, Any]:
        return {
            "id": session.id,
            "title": session.title,
            "preview": session.preview,
            "started_at": session.started_at,
            "last_active": session.started_at,
            "message_count": len(session.messages),
            "source": "mobile",
            "archived": session.archived,
            "pinned": session.pinned,
        }

    def _new_runtime(self, stored_id: str) -> str:
        runtime_id = f"runtime-{uuid.uuid4().hex[:8]}"
        self._runtime_to_stored[runtime_id] = stored_id
        return runtime_id

    def _require_runtime(self, request_id: Any, params: dict[str, Any]) -> tuple[str | None, dict[str, Any] | None]:
        runtime_id = str(params.get("session_id") or "")
        if runtime_id not in self._runtime_to_stored:
            return None, self._error(request_id, 4005, "unknown session")
        return runtime_id, None

    def _dispatch(self, request: Any) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        if not isinstance(request, dict):
            return self._error(None, -32600, "invalid request"), []
        request_id = request.get("id")
        method = str(request.get("method") or "")
        raw_params = request.get("params")
        params: dict[str, Any] = raw_params if isinstance(raw_params, dict) else {}
        with self._lock:
            self.rpc_requests += 1

            if method == "session.list":
                rows = sorted(self._sessions.values(), key=lambda row: row.started_at, reverse=True)
                return self._ok(request_id, {"sessions": [self._session_payload(row) for row in rows]}), []

            if method == "session.create":
                stored_id = f"mobile-{uuid.uuid4().hex[:12]}"
                now = time.time()
                session = _StoredSession(stored_id, str(params.get("title") or ""), "", now, [])
                self._sessions[stored_id] = session
                runtime_id = self._new_runtime(stored_id)
                return self._ok(
                    request_id,
                    {
                        "session_id": runtime_id,
                        "stored_session_id": stored_id,
                        "message_count": 0,
                        "messages": [],
                        "info": {
                            "model": str(params.get("model") or "fixture-model"),
                            "provider": str(params.get("provider") or "fixture"),
                            "reasoning_effort": str(params.get("reasoning_effort") or "medium"),
                            "running": False,
                            "stored_session_id": stored_id,
                        },
                    },
                ), []

            if method == "session.resume":
                stored_id = str(params.get("session_id") or "")
                session = self._sessions.get(stored_id)
                if session is None:
                    return self._error(request_id, 4007, "session not found"), []
                runtime_id = self._new_runtime(stored_id)
                return self._ok(
                    request_id,
                    {
                        "session_id": runtime_id,
                        "resumed": stored_id,
                        "session_key": stored_id,
                        "message_count": len(session.messages),
                        "messages": session.messages,
                        "running": False,
                        "status": "idle",
                        "info": {
                            "model": "fixture-model",
                            "provider": "fixture",
                            "reasoning_effort": "medium",
                            "stored_session_id": stored_id,
                            "running": False,
                        },
                    },
                ), []

            if method == "session.title":
                runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                stored_id = self._runtime_to_stored[runtime_id or ""]
                title = str(params.get("title") or "").strip()
                if not title:
                    return self._error(request_id, 4004, "title required"), []
                self._sessions[stored_id].title = title
                return self._ok(request_id, {"title": title, "session_id": stored_id}), []

            if method == "session.delete":
                target = str(params.get("session_id") or "")
                stored_id = self._runtime_to_stored.get(target, target)
                if self._sessions.pop(stored_id, None) is None:
                    return self._error(request_id, 4007, "session not found"), []
                self._runtime_to_stored = {
                    runtime: stored
                    for runtime, stored in self._runtime_to_stored.items()
                    if stored != stored_id
                }
                return self._ok(request_id, {"deleted": stored_id}), []

            if method == "model.options":
                return self._ok(
                    request_id,
                    {
                        "model": "fixture-model",
                        "provider": "fixture",
                        "providers": [
                            {
                                "slug": "fixture",
                                "name": "Fixture Provider",
                                "is_current": True,
                                "authenticated": True,
                                "models": ["fixture-model", "fixture-fast"],
                                "capabilities": {
                                    "fixture-model": {"fast": True, "reasoning": True}
                                },
                            }
                        ],
                    },
                ), []

            if method == "approval.respond":
                _runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                return self._ok(request_id, {"resolved": True}), []

            response_fields = {
                "clarify.respond": "answer",
                "secret.respond": "value",
                "sudo.respond": "password",
            }
            if method in response_fields:
                _runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                field = response_fields[method]
                if not str(params.get("request_id") or "") or field not in params:
                    return self._error(request_id, 4015, f"request_id and {field} required"), []
                return self._ok(request_id, {"status": "ok"}), []

            if method == "session.interrupt":
                runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                return self._ok(request_id, {"interrupted": True, "session_id": runtime_id}), [
                    self._event("session.info", runtime_id or "", {"running": False})
                ]

            if method in {"image.attach_bytes", "pdf.attach", "file.attach"}:
                _runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                bytes_field = "data_url" if method == "file.attach" else "content_base64"
                raw = str(params.get(bytes_field) or "")
                if not raw:
                    return self._error(request_id, 4015, f"{bytes_field} required"), []
                encoded = raw.split(",", 1)[-1]
                try:
                    size = len(base64.b64decode(encoded, validate=True))
                except ValueError:
                    return self._error(request_id, 4017, "data is not valid base64"), []
                name = str(params.get("filename") or params.get("name") or "attachment")
                result: dict[str, Any] = {
                    "attached": True,
                    "name": name,
                    "bytes": size,
                }
                if method == "file.attach":
                    result.update(
                        {
                            "ref_path": f".hermes/desktop-attachments/{name}",
                            "ref_text": f"@file:.hermes/desktop-attachments/{name}",
                            "uploaded": True,
                        }
                    )
                else:
                    result["text"] = "[Attachment accepted]"
                return self._ok(request_id, result), []

            if method == "prompt.submit":
                runtime_id, error = self._require_runtime(request_id, params)
                if error:
                    return error, []
                text = str(params.get("text") or "").strip()
                if not text:
                    return self._error(request_id, 4004, "text required"), []
                stored_id = self._runtime_to_stored[runtime_id or ""]
                session = self._sessions[stored_id]
                session.messages.append(
                    {"id": len(session.messages) + 1, "role": "user", "content": text, "timestamp": time.time()}
                )
                answer = f"Hermes received: {text}"
                session.messages.append(
                    {
                        "id": len(session.messages) + 1,
                        "role": "assistant",
                        "content": answer,
                        "timestamp": time.time(),
                    }
                )
                events = [
                    self._event("message.start", runtime_id or "", {}),
                    self._event("reasoning.delta", runtime_id or "", {"text": "Preparing response."}),
                    self._event(
                        "tool.start",
                        runtime_id or "",
                        {"tool_call_id": "fixture-tool-1", "name": "test_tool", "preview": "Checking fixture"},
                    ),
                    self._event(
                        "tool.complete",
                        runtime_id or "",
                        {"tool_call_id": "fixture-tool-1", "name": "test_tool", "result": "ok"},
                    ),
                    self._event("message.delta", runtime_id or "", {"text": answer}),
                    self._event("message.complete", runtime_id or "", {"text": answer, "status": "complete"}),
                    self._event(
                        "session.info",
                        runtime_id or "",
                        {"running": False, "stored_session_id": stored_id},
                    ),
                ]
                return self._ok(request_id, {"status": "streaming"}), events

        return self._error(request_id, -32601, f"method not found: {method}"), []


class JsonRpcWebSocketClient:
    """Tiny dependency-free WebSocket client for the fake gateway self-tests."""

    def __init__(self, sock: socket.socket, stream: Any) -> None:
        self._socket = sock
        self._stream = stream
        self._next_id = 1
        self._queued: deque[dict[str, Any]] = deque()
        self._closed = False

    @classmethod
    def connect(cls, url: str) -> "JsonRpcWebSocketClient":
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "ws":
            raise ValueError("self-test client supports ws:// URLs only")
        host = parsed.hostname or "127.0.0.1"
        port = parsed.port or 80
        sock = socket.create_connection((host, port), timeout=3)
        stream = sock.makefile("rwb", buffering=0)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode("ascii")
        stream.write(request)
        status_line = stream.readline().decode("latin-1").strip()
        if not status_line:
            stream.close()
            sock.close()
            raise ConnectionError("empty WebSocket handshake")
        parts = status_line.split(" ", 2)
        status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
        headers: dict[str, str] = {}
        while True:
            line = stream.readline()
            if line in {b"\r\n", b"\n", b""}:
                break
            name, _, value = line.decode("latin-1").partition(":")
            headers[name.strip().lower()] = value.strip()
        if status != 101:
            stream.close()
            sock.close()
            raise ConnectionError(f"WebSocket handshake failed: {status}")
        expected = base64.b64encode(
            hashlib.sha1((key + _WEBSOCKET_GUID).encode("ascii")).digest()
        ).decode("ascii")
        if headers.get("sec-websocket-accept") != expected:
            stream.close()
            sock.close()
            raise ConnectionError("invalid WebSocket accept key")
        return cls(sock, stream)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._stream.write(_encode_frame(b"", opcode=0x8, masked=True))
        except OSError:
            pass
        try:
            self._stream.close()
        finally:
            self._socket.close()

    def send_json(self, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._stream.write(_encode_frame(encoded, masked=True))

    def receive_json(self, *, timeout: float = 3) -> dict[str, Any]:
        if self._queued:
            return self._queued.popleft()
        self._socket.settimeout(timeout)
        while True:
            opcode, payload = _decode_frame(self._stream)
            if opcode == 0x8:
                raise ConnectionError("WebSocket closed")
            if opcode == 0x9:
                self._stream.write(_encode_frame(payload, opcode=0xA, masked=True))
                continue
            if opcode != 0x1:
                continue
            value = json.loads(payload.decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("JSON-RPC frame must be an object")
            return value

    def request(self, method: str, params: dict[str, Any], *, timeout: float = 3) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        self.send_json(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        )
        deadline = time.monotonic() + timeout
        deferred: list[dict[str, Any]] = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                self._queued.extendleft(reversed(deferred))
                raise TimeoutError(f"request timed out: {method}")
            frame = self.receive_json(timeout=remaining)
            if frame.get("id") != request_id:
                deferred.append(frame)
                continue
            self._queued.extend(deferred)
            if isinstance(frame.get("error"), dict):
                raise RuntimeError(str(frame["error"].get("message") or "JSON-RPC error"))
            result = frame.get("result")
            if not isinstance(result, dict):
                raise ValueError("JSON-RPC result must be an object")
            return result

    def receive_events_until(self, event_type: str, *, timeout: float = 5) -> list[dict[str, Any]]:
        deadline = time.monotonic() + timeout
        events: list[dict[str, Any]] = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"event not received: {event_type}")
            frame = self.receive_json(timeout=remaining)
            params = frame.get("params")
            if frame.get("method") != "event" or not isinstance(params, dict):
                continue
            events.append(frame)
            if params.get("type") == event_type:
                return events


def run_self_test() -> dict[str, Any]:
    gateway = FakeHermesGateway()
    gateway.start()
    try:
        with urllib.request.urlopen(f"{gateway.http_url}/api/status", timeout=3) as response:
            status = json.loads(response.read())
        if not status.get("gateway_running"):
            raise RuntimeError("status endpoint failed")
        client = JsonRpcWebSocketClient.connect(f"{gateway.ws_url}?token={gateway.token}")
        try:
            ready = client.receive_json(timeout=3)
            if ready.get("params", {}).get("type") != "gateway.ready":
                raise RuntimeError("gateway.ready not received")
            created = client.request("session.create", {"source": "mobile"})
            client.request(
                "prompt.submit",
                {"session_id": created["session_id"], "text": "self test"},
            )
            events = client.receive_events_until("message.complete", timeout=5)
            return {
                "status": "ok",
                "events": [event["params"]["type"] for event in events],
                "rpc_requests": gateway.rpc_requests,
            }
        finally:
            client.close()
    finally:
        gateway.stop()


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Hermes mobile protocol fixture")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if not args.self_test:
        parser.error("use --self-test")
    print(json.dumps(run_self_test(), ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
