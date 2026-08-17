import argparse
import json
import os

import pytest

from apps.mobile.gateway import install


def test_explicit_pairing_output(tmp_path, monkeypatch, capsys) -> None:
    home = tmp_path / "home"
    root = home / ".hermes" / "mobile-gateway"
    root.mkdir(parents=True, mode=0o700)
    monkeypatch.setenv("HOME", str(home))
    token = "a" * 64
    pairing = root / "pairing.json"
    secret = root / "external.token"
    pairing.write_text(json.dumps({"endpoint": "https://100.64.0.10:443"}))
    secret.write_text(token)
    os.chmod(pairing, 0o600)
    os.chmod(secret, 0o600)
    hidden = install._pairing_output({"action": "paired"}, argparse.Namespace(show_pairing=False))
    assert "pairing_secret" not in hidden
    result = install._pairing_output({"action": "paired"}, argparse.Namespace(show_pairing=True))
    install._emit(result, as_json=False)
    output = capsys.readouterr().out
    assert token in output
    assert "100.64.0.10:443" in output


def test_show_pairing_rejects_json(capsys) -> None:
    code = install.main(["pair", "--endpoint", "https://agent.example", "--show-pairing", "--json"])
    assert code == 1
    assert "cannot be combined" in capsys.readouterr().err


def test_loopback_http_is_explicit() -> None:
    with pytest.raises(install.InstallError):
        install._normalize_endpoint("http://192.0.2.10:9120", allow_loopback_http=True)
    endpoint, websocket = install._normalize_endpoint("http://127.0.0.1:9120", allow_loopback_http=True)
    assert endpoint == "http://127.0.0.1:9120"
    assert websocket == "ws://127.0.0.1:9120/api/ws"
    payload = install._pairing_payload(
        installation_id="b" * 32, endpoint=endpoint, websocket=websocket,
        external_token="a" * 64, created_at="2026-08-17T00:00:00Z",
        sidecar_port=9120, upstream_port=9119,
    )
    assert payload["gateway"]["tls_terminated_by_ingress"] is False
