# Hermes Mobile sidecar

This package is an independent, additive compatibility layer for the official
Hermes Agent release `v2026.8.13` (peeled commit
`f80f453ae0679347e38abc917c7f94f717bf96c5`, runtime version `v0.20.1`). It
never installs, upgrades, imports, or edits the official checkout.

## Topology and credentials

The one package-owned user service starts two children and owns both of them:

```text
Mobile app
  | HTTPS/WSS through a TLS ingress
  v
127.0.0.1:9120  Hermes Mobile sidecar
  | HTTP/WS loopback with a separate internal credential
  v
127.0.0.1:9119  official `hermes serve` v0.20.1
```

- The external pairing credential is accepted only in the
  `X-Hermes-Session-Token` HTTPS header. It is never accepted in a URL.
- `POST /api/auth/ws-ticket` authenticates that header and returns an in-memory
  30-second, single-use ticket.
- Each external WebSocket uses `/api/ws?ticket=<short-lived-ticket>`. The
  sidecar consumes the ticket once, then opens the official loopback socket
  with its separate internal credential.
- REST requests are authenticated at the sidecar, external credential headers
  are stripped, and the internal credential is injected only on the loopback
  hop. `GET /api/status` remains public.
- Sidecar and official access logging are disabled or warning-only so request
  URLs and credentials are not retained by the managed processes.

The pairing manifest contains the external credential and must be transferred
only through a trusted encrypted channel. It is always a mode-`0600` file.

## Install

Use the official command and the Python interpreter from its environment:

```bash
python3 apps/mobile/gateway/install.py probe \
  --hermes "$(command -v hermes)" \
  --python /absolute/path/to/official/.venv/bin/python

python3 apps/mobile/gateway/install.py install \
  --hermes "$(command -v hermes)" \
  --python /absolute/path/to/official/.venv/bin/python \
  --endpoint https://hermes.example.com
```

The default loopback ports are `9119` for the official upstream and `9120` for
the sidecar. `--no-activate` writes and validates the package without asking
systemd to start it. The manager never stops, restarts, or replaces an
existing Hermes process or service.

The installer verifies the canonical HOME path, current-user ownership,
`0700` directories, `0600` secret/manifest files, a package installation ID,
the exact owned inventory, and SHA-256 digests before activation or removal.
Symlinks and unexpected files fail closed. A missing or uncertain
`systemctl --user` query is reported as `UNKNOWN` and blocks activation and
removal.

## Pairing and ingress

Configure TLS ingress to the sidecar loopback port, then pair when the endpoint
was not supplied during installation:

```bash
~/.hermes/mobile-gateway/bin/manage pair \
  --endpoint https://hermes.example.com
```

The ingress must preserve WebSocket upgrades and forward to
`http://127.0.0.1:9120`. Keep both loopback ports blocked from LAN/WAN access.
The mobile client flow is:

1. `POST https://.../api/auth/ws-ticket` with the pairing credential in
   `X-Hermes-Session-Token`.
2. Connect once to `wss://.../api/ws?ticket=<response-ticket>`.
3. Mint another ticket before every reconnect.

Never put the long-lived pairing credential in a WebSocket URL, query string,
path, shell argument, or log message.

## Status and removal

```bash
~/.hermes/mobile-gateway/bin/manage status --json
systemctl --user stop hermes-mobile-gateway.service
systemctl --user is-active hermes-mobile-gateway.service  # must be inactive
~/.hermes/mobile-gateway/bin/manage uninstall --dry-run
~/.hermes/mobile-gateway/bin/manage uninstall --yes
```

The manager refuses to stop an active service itself. Removal unlinks only a
unit whose `FragmentPath`, owner, mode, content digest, installation ID, and
inventory have all been verified. It removes only those verified package files
and never touches official Hermes configuration, package files, profiles, or
state.

## Compatibility evidence

The focused installer/sidecar suite uses fake official and systemd boundaries:

```bash
python -m pytest tests/mobile/test_gateway_install.py \
  tests/mobile/test_gateway_sidecar.py -q
```

The opt-in black-box checks require a separate official checkout at the pinned
tag and commit. They run the lifecycle through the sidecar, then verify HTTP,
ticketed WebSocket, session resume/delete, and absence of both credentials in
the process logs:

```bash
HERMES_OFFICIAL_HERMES=/path/to/official/.venv/bin/hermes \
HERMES_OFFICIAL_SOURCE=/path/to/official \
HERMES_OFFICIAL_TAG=v2026.8.13 \
HERMES_OFFICIAL_COMMIT=f80f453ae0679347e38abc917c7f94f717bf96c5 \
python -m pytest tests/mobile/test_official_gateway_mobile_e2e.py -m integration -q -s
```
