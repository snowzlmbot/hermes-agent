from __future__ import annotations

import json
import sys
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
MOBILE_ROOT = HERE.parent
sys.path.insert(0, str(HERE))

from fake_gateway import FakeHermesGateway, JsonRpcWebSocketClient, run_self_test  # noqa: E402


class FakeGatewayContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.gateway = FakeHermesGateway(token="mobile-test-token")
        self.gateway.start()
        self.addCleanup(self.gateway.stop)

    def request_json(
        self,
        path: str,
        *,
        method: str = "GET",
        payload: dict | None = None,
        authenticated: bool = False,
    ) -> tuple[int, dict]:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if authenticated:
            headers["X-Hermes-Session-Token"] = "mobile-test-token"
            headers["Authorization"] = "Bearer mobile-test-token"
        request = urllib.request.Request(
            f"{self.gateway.http_url}{path}", data=body, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as exc:
            with exc:
                return exc.code, json.loads(exc.read())

    def test_status_is_public_but_audio_requires_authentication(self) -> None:
        status, payload = self.request_json("/api/status")
        self.assertEqual(status, 200)
        self.assertTrue(payload["gateway_running"])
        self.assertTrue(payload["auth_required"])
        self.assertIn("native_pkce_mobile", payload["auth_flows"])

        status, payload = self.request_json(
            "/api/audio/transcribe",
            method="POST",
            payload={"data_url": "data:audio/wav;base64,UklGRg=="},
        )
        self.assertEqual(status, 401)
        self.assertEqual(payload["detail"], "authentication required")

        status, payload = self.request_json(
            "/api/audio/transcribe",
            method="POST",
            payload={"data_url": "data:audio/wav;base64,UklGRg=="},
            authenticated=True,
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["transcript"], "Test voice note")

    def test_ws_ticket_is_single_use(self) -> None:
        status, payload = self.request_json(
            "/api/auth/ws-ticket", method="POST", payload={}, authenticated=True
        )
        self.assertEqual(status, 200)
        ticket = payload["ticket"]
        self.assertEqual(payload["ttl_seconds"], 30)

        client = JsonRpcWebSocketClient.connect(f"{self.gateway.ws_url}?ticket={ticket}")
        self.addCleanup(client.close)
        ready = client.receive_json(timeout=3)
        self.assertEqual(ready["params"]["type"], "gateway.ready")

        with self.assertRaisesRegex(ConnectionError, "401"):
            JsonRpcWebSocketClient.connect(f"{self.gateway.ws_url}?ticket={ticket}")

    def test_native_pkce_mobile_flow_exchanges_and_refreshes_tokens(self) -> None:
        verifier = "mobile-verifier-with-at-least-forty-three-characters-0123456789"
        challenge = self.gateway.pkce_challenge(verifier)
        callback = "com.snowzlmbot.hermes.mobile:/oauth/callback"
        query = urllib.parse.urlencode(
            {
                "provider": "fixture",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                "redirect_uri": callback,
                "state": "mobile-state",
            }
        )
        opener = urllib.request.build_opener(_NoRedirectHandler())
        request = urllib.request.Request(
            f"{self.gateway.http_url}/auth/native/authorize?{query}", method="GET"
        )
        with self.assertRaises(urllib.error.HTTPError) as caught:
            opener.open(request, timeout=3)
        self.assertEqual(caught.exception.code, 302)
        redirect = urllib.parse.urlsplit(caught.exception.headers["Location"])
        self.assertEqual(
            f"{redirect.scheme}:{redirect.path}",
            "com.snowzlmbot.hermes.mobile:/oauth/callback",
        )
        values = urllib.parse.parse_qs(redirect.query)
        self.assertEqual(values["state"], ["mobile-state"])

        status, tokens = self.request_json(
            "/auth/native/token",
            method="POST",
            payload={"code": values["code"][0], "code_verifier": verifier},
        )
        self.assertEqual(status, 200)
        self.assertEqual(tokens["token_type"], "Bearer")
        self.assertIn("expires_at", tokens)

        status, refreshed = self.request_json(
            "/auth/native/refresh",
            method="POST",
            payload={
                "refresh_token": tokens["refresh_token"],
                "provider": tokens["provider"],
            },
        )
        self.assertEqual(status, 200)
        self.assertNotEqual(refreshed["refresh_token"], tokens["refresh_token"])

    def test_rest_session_mutations_use_durable_identity(self) -> None:
        status, listed = self.request_json(
            "/api/sessions?order=recent&archived=include",
            authenticated=True,
        )
        self.assertEqual(status, 200)
        stored_id = listed["sessions"][0]["id"]

        status, updated = self.request_json(
            f"/api/sessions/{stored_id}",
            method="PATCH",
            payload={"title": "Mobile session", "archived": True},
            authenticated=True,
        )
        self.assertEqual(status, 200)
        self.assertEqual(updated["title"], "Mobile session")
        self.assertTrue(updated["archived"])

        status, deleted = self.request_json(
            f"/api/sessions/{stored_id}", method="DELETE", authenticated=True
        )
        self.assertEqual(status, 200)
        self.assertTrue(deleted["ok"])

    def test_session_lifecycle_and_stream_events_match_contract(self) -> None:
        client = JsonRpcWebSocketClient.connect(
            f"{self.gateway.ws_url}?token=mobile-test-token"
        )
        self.addCleanup(client.close)
        client.receive_json(timeout=3)  # gateway.ready

        listed = client.request("session.list", {"limit": 20})
        self.assertGreaterEqual(len(listed["sessions"]), 1)
        self.assertIn("last_active", listed["sessions"][0])
        self.assertIn("archived", listed["sessions"][0])
        self.assertIn("pinned", listed["sessions"][0])
        stored_id = listed["sessions"][0]["id"]

        resumed = client.request("session.resume", {"session_id": stored_id, "source": "mobile"})
        runtime_id = resumed["session_id"]
        self.assertEqual(resumed["resumed"], stored_id)
        self.assertNotEqual(runtime_id, stored_id)

        resumed_again = client.request(
            "session.resume", {"session_id": stored_id, "source": "mobile"}
        )
        self.assertEqual(resumed_again["resumed"], stored_id)
        self.assertNotEqual(resumed_again["session_id"], runtime_id)
        self.assertNotEqual(resumed_again["session_id"], stored_id)
        client.send_json(
            {
                "jsonrpc": "2.0",
                "id": "missing-resume",
                "method": "session.resume",
                "params": {"session_id": "missing-stored-session", "source": "mobile"},
            }
        )
        missing = client.receive_json(timeout=3)
        self.assertEqual(missing["id"], "missing-resume")
        self.assertEqual(missing["error"]["code"], 4007)
        self.assertEqual(missing["error"]["message"], "session not found")

        ack = client.request("prompt.submit", {"session_id": runtime_id, "text": "hello"})
        self.assertEqual(ack["status"], "streaming")

        events = client.receive_events_until("message.complete", timeout=5)
        event_types = [frame["params"]["type"] for frame in events]
        self.assertIn("message.start", event_types)
        self.assertIn("reasoning.delta", event_types)
        self.assertIn("tool.start", event_types)
        self.assertIn("tool.complete", event_types)
        self.assertEqual(event_types[-1], "message.complete")
        self.assertTrue(
            all(frame["params"].get("session_id") == runtime_id for frame in events)
        )

    def test_model_controls_are_runtime_scoped_and_publish_session_info(self) -> None:
        client = JsonRpcWebSocketClient.connect(
            f"{self.gateway.ws_url}?token=mobile-test-token"
        )
        self.addCleanup(client.close)
        client.receive_json(timeout=3)
        created = client.request("session.create", {"source": "mobile"})
        runtime_id = created["session_id"]

        options = client.request("model.options", {"session_id": runtime_id})
        self.assertEqual(options["model"], "fixture-model")
        switched = client.request(
            "config.set",
            {
                "session_id": runtime_id,
                "key": "model",
                "value": "fixture-fast --provider fixture --session",
            },
        )
        self.assertEqual(switched["scope"], "session")
        model_event = client.receive_events_until("session.info", timeout=3)[-1]
        self.assertEqual(model_event["params"]["payload"]["model"], "fixture-fast")

        client.request(
            "config.set",
            {"session_id": runtime_id, "key": "reasoning", "value": "max"},
        )
        reasoning_event = client.receive_events_until("session.info", timeout=3)[-1]
        self.assertEqual(reasoning_event["params"]["payload"]["reasoning_effort"], "max")

    def test_action_and_attachment_methods_validate_payloads(self) -> None:
        client = JsonRpcWebSocketClient.connect(
            f"{self.gateway.ws_url}?token=mobile-test-token"
        )
        self.addCleanup(client.close)
        client.receive_json(timeout=3)

        created = client.request("session.create", {"source": "mobile"})
        runtime_id = created["session_id"]
        approved = client.request(
            "approval.respond", {"session_id": runtime_id, "choice": "deny"}
        )
        self.assertTrue(approved["resolved"])

        clarified = client.request(
            "clarify.respond",
            {
                "session_id": runtime_id,
                "request_id": "clarify-1",
                "answer": "production",
            },
        )
        self.assertEqual(clarified["status"], "ok")
        secret = client.request(
            "secret.respond",
            {"session_id": runtime_id, "request_id": "secret-1", "value": ""},
        )
        self.assertEqual(secret["status"], "ok")
        sudo = client.request(
            "sudo.respond",
            {"session_id": runtime_id, "request_id": "sudo-1", "password": ""},
        )
        self.assertEqual(sudo["status"], "ok")

        attached = client.request(
            "image.attach_bytes",
            {
                "session_id": runtime_id,
                "filename": "sample.png",
                "content_base64": "iVBORw0KGgo=",
            },
        )
        self.assertTrue(attached["attached"])
        self.assertEqual(attached["bytes"], 8)

        with self.assertRaisesRegex(RuntimeError, "content_base64 required"):
            client.request(
                "image.attach_bytes",
                {"session_id": runtime_id, "filename": "empty.png"},
            )

        pdf = client.request(
            "pdf.attach",
            {
                "session_id": runtime_id,
                "filename": "sample.pdf",
                "content_base64": "JVBERi0xLjQK",
            },
        )
        self.assertTrue(pdf["attached"])
        with self.assertRaisesRegex(RuntimeError, "content_base64 required"):
            client.request(
                "pdf.attach",
                {"session_id": runtime_id, "filename": "sample.pdf", "data_url": "data:application/pdf;base64,JVBERg=="},
            )

        ordinary = client.request(
            "file.attach",
            {
                "session_id": runtime_id,
                "name": "notes.txt",
                "data_url": "data:text/plain;base64,aGVsbG8=",
            },
        )
        self.assertTrue(ordinary["attached"])
        self.assertTrue(ordinary["ref_text"].startswith("@file:"))
        with self.assertRaisesRegex(RuntimeError, "data_url required"):
            client.request(
                "file.attach",
                {"session_id": runtime_id, "name": "notes.txt", "content_base64": "aGVsbG8="},
            )

    def test_contract_fixture_has_request_and_event_examples(self) -> None:
        contract = json.loads((MOBILE_ROOT / "protocol" / "contract.json").read_text())
        self.assertGreaterEqual(contract["schema_version"], 1)
        self.assertIn("session.create", contract["rpc_methods"])
        self.assertIn("message.delta", contract["event_types"])
        self.assertIn("clarify.expire", contract["event_types"])
        self.assertIn("secret.expire", contract["event_types"])
        self.assertIn("sudo.expire", contract["event_types"])
        self.assertEqual(contract["auth"]["native_mobile_flow"], "native_pkce_mobile")
        self.assertEqual(contract["rest"]["ws_ticket"]["ttl_field"], "ttl_seconds")
        self.assertEqual(contract["responses"]["clarify.respond"], "answer")
        self.assertEqual(contract["responses"]["secret.respond"], "value")
        session_identity = contract["session_identity"]
        self.assertEqual(session_identity["persisted_field"], "stored_session_id")
        self.assertEqual(session_identity["runtime_field"], "session_id")
        self.assertTrue(session_identity["new_runtime_per_resume"])
        self.assertEqual(session_identity["missing_stored_session_error_code"], 4007)
        self.assertEqual(
            session_identity["missing_stored_session_client_action"],
            "clear_selection_and_create",
        )
        notification_routing = contract["notification_routing"]
        self.assertEqual(notification_routing["identity_field"], "stored_session_id")
        self.assertEqual(
            notification_routing["allowed_fields"],
            ["stored_session_id", "profile_scope"],
        )
        self.assertEqual(notification_routing["profile_scope_field"], "profile_scope")
        self.assertEqual(notification_routing["profile_scope_encoding"], "sha256_hex_lowercase")
        self.assertEqual(notification_routing["profile_scope_input"], "session_selection_scope")
        self.assertEqual(notification_routing["scope_mismatch_action"], "discard")
        self.assertTrue(notification_routing["profile_scoped"])
        self.assertTrue(notification_routing["single_consume"])
        self.assertIn("session_id", notification_routing["forbidden_fields"])
        self.assertIn("token", notification_routing["forbidden_fields"])
        self.assertIn("ticket", notification_routing["forbidden_fields"])
        self.assertIn("content", notification_routing["forbidden_fields"])
        self.assertIn("prompt", notification_routing["forbidden_fields"])
        self.assertIn("approval", notification_routing["forbidden_fields"])
        self.assertEqual(contract["responses"]["sudo.respond"], "password")
        self.assertEqual(contract["attachments"]["file.attach"]["bytes_field"], "data_url")
        self.assertEqual(contract["frames"]["request"]["jsonrpc"], "2.0")
        self.assertEqual(contract["frames"]["event"]["method"], "event")


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        del req, fp, code, msg, headers, newurl
        return None


class FakeGatewaySelfTestTests(unittest.TestCase):
    def test_self_test_exercises_http_websocket_and_streaming(self) -> None:
        result = run_self_test()
        self.assertEqual(result["status"], "ok")
        self.assertIn("message.complete", result["events"])
        self.assertGreater(result["rpc_requests"], 0)


if __name__ == "__main__":
    unittest.main()
