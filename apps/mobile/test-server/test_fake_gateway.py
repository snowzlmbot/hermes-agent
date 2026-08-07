from __future__ import annotations

import json
import sys
import unittest
import urllib.error
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
        self.assertEqual(payload["status"], "ok")
        self.assertTrue(payload["embedded_chat"])

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

        client = JsonRpcWebSocketClient.connect(f"{self.gateway.ws_url}?ticket={ticket}")
        self.addCleanup(client.close)
        ready = client.receive_json(timeout=3)
        self.assertEqual(ready["params"]["type"], "gateway.ready")

        with self.assertRaisesRegex(ConnectionError, "401"):
            JsonRpcWebSocketClient.connect(f"{self.gateway.ws_url}?ticket={ticket}")

    def test_session_lifecycle_and_stream_events_match_contract(self) -> None:
        client = JsonRpcWebSocketClient.connect(
            f"{self.gateway.ws_url}?token=mobile-test-token"
        )
        self.addCleanup(client.close)
        client.receive_json(timeout=3)  # gateway.ready

        listed = client.request("session.list", {"limit": 20})
        self.assertGreaterEqual(len(listed["sessions"]), 1)
        stored_id = listed["sessions"][0]["id"]

        resumed = client.request("session.resume", {"session_id": stored_id, "source": "mobile"})
        runtime_id = resumed["session_id"]
        self.assertEqual(resumed["resumed"], stored_id)
        self.assertNotEqual(runtime_id, stored_id)

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

    def test_contract_fixture_has_request_and_event_examples(self) -> None:
        contract = json.loads((MOBILE_ROOT / "protocol" / "contract.json").read_text())
        self.assertGreaterEqual(contract["schema_version"], 1)
        self.assertIn("session.create", contract["rpc_methods"])
        self.assertIn("message.delta", contract["event_types"])
        self.assertEqual(contract["frames"]["request"]["jsonrpc"], "2.0")
        self.assertEqual(contract["frames"]["event"]["method"], "event")


class FakeGatewaySelfTestTests(unittest.TestCase):
    def test_self_test_exercises_http_websocket_and_streaming(self) -> None:
        result = run_self_test()
        self.assertEqual(result["status"], "ok")
        self.assertIn("message.complete", result["events"])
        self.assertGreater(result["rpc_requests"], 0)


if __name__ == "__main__":
    unittest.main()
