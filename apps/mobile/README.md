# Hermes Mobile test builds

The mobile clients connect through the independent Hermes Mobile sidecar in
[`gateway/README.md`](./gateway/README.md). The sidecar is the only TLS-ingress
target: it listens on `127.0.0.1:9120`, bridges to an unchanged official
Hermes `v0.20.1` gateway on `127.0.0.1:9119`, and keeps the two credentials
separate.

## Mobile authentication contract

Token-mode clients must keep the long-lived pairing credential in the HTTPS
`X-Hermes-Session-Token` header. It must never be put in a URL. Before every
WebSocket connection or reconnect, the client calls:

```text
POST /api/auth/ws-ticket
X-Hermes-Session-Token: <pairing-credential>
```

The sidecar returns a 30-second single-use ticket. The client then connects
using only:

```text
wss://<ingress>/api/ws?ticket=<short-lived-ticket>
```

`GET /api/status` is public. Other REST requests use the same HTTPS header;
the sidecar strips that header before forwarding and injects a distinct
loopback-only credential for official Hermes. OAuth flows retain their own
native ticket exchange and do not reuse the pairing credential in a URL.

## Download the Android test build

The stable build entry is the **Mobile Android** Actions page on the delivery
branch:

<https://github.com/snowzlmbot/hermes-agent/actions/workflows/mobile-android.yml?query=branch%3Ahermes-agent-Mobile-app>

1. Open the newest successful run whose branch is `hermes-agent-Mobile-app`.
2. Record the run commit SHA.
3. Download `hermes-mobile-android-<commit-sha>`.
4. Use the Debug APK for test installation. The Release output is a
   verification build and may be unsigned.

With GitHub CLI:

```bash
gh run list \
  --repo snowzlmbot/hermes-agent \
  --workflow mobile-android.yml \
  --branch hermes-agent-Mobile-app \
  --status success \
  --limit 5

gh run download RUN_ID \
  --repo snowzlmbot/hermes-agent \
  --name hermes-mobile-android-COMMIT_SHA \
  --dir hermes-mobile-android-COMMIT_SHA
```

Install only after verifying the run and artifact came from the expected
commit:

```bash
adb install -r path/to/app-debug.apk
```

## iOS test output

The stable **Mobile iOS** Actions page is:

<https://github.com/snowzlmbot/hermes-agent/actions/workflows/mobile.yml?query=branch%3Ahermes-agent-Mobile-app>

Current artifacts contain simulator/unsigned verification output, not a
signed App Store or physical-device distribution. Use the artifact whose name
embeds the exact run commit SHA.

## Official compatibility pin

The official compatibility workflow checks out and verifies tag
`v2026.8.13`, peeled commit
`f80f453ae0679347e38abc917c7f94f717bf96c5`, and runtime version `v0.20.1` in a
distinct checkout. It also asserts that imported `hermes_cli` resolves below
that checkout and that the integration lifecycle test is actually collected
with the `integration` marker.
