# Official Hermes mobile gateway compatibility package

This directory is the installation entry point to send to an existing **official Hermes Agent** installation. The package is additive: it uses the installed `hermes serve` command and never installs, upgrades, replaces, or edits Hermes itself.

## Security and compatibility contract

- Verified baseline: official release tag `v2026.8.13`, peeled commit `f80f453ae0679347e38abc917c7f94f717bf96c5`, installed version `v0.20.1`.
- The service binds **only** `127.0.0.1` (default port `9119`). Token mode must not bind `0.0.0.0`.
- Public and private mobile connections require an HTTPS/WSS ingress to that loopback listener.
- The manager generates a 256-bit session token. It never accepts a token value on the command line and never prints the token.
- `session.token` and `pairing.json` are regular, owner-only `0600` files. Managed directories are `0700`.
- All managed files are under `~/.hermes/mobile-gateway`. The only external registration is systemd's link to the unit stored there.
- The manager never runs `pip`, edits site-packages, or writes `~/.hermes/.env`, `config.yaml`, profiles, session databases, or other official state.
- The manager probes `hermes version` and `hermes serve --help` in a temporary isolated `HOME`. Its read-only `hermes serve --status` conflict check preserves the caller's current `HOME` and `HERMES_HOME`, so an existing machine-level gateway remains visible. Every probe removes `PYTHONPATH` and `PYTHONHOME`.
- If any Hermes dashboard/serve process, target-port listener, or foreign service with the same name exists, installation fails before creating or starting a service. It never stops, restarts, or replaces an existing process/service.
- Native OAuth is optional and is not required for the default session-token pairing path.

Plaintext private-LAN credentials are not supported by this installer. Debug builds can expose an explicit development-only cleartext capability, but do not send a pairing secret over HTTP/WS.

## Install from the repository

Use the stable document on the mobile delivery branch:

<https://github.com/snowzlmbot/hermes-agent/blob/hermes-agent-Mobile-app/apps/mobile/gateway/README.md>

Clone or update that branch, inspect this directory, and run the manager with the existing official command:

```bash
git clone --filter=blob:none --branch hermes-agent-Mobile-app \
  https://github.com/snowzlmbot/hermes-agent.git hermes-agent-mobile
cd hermes-agent-mobile

python3 apps/mobile/gateway/install.py probe \
  --hermes "$(command -v hermes)"
python3 apps/mobile/gateway/install.py install \
  --hermes "$(command -v hermes)" \
  --dry-run \
  --endpoint https://hermes.example.com
python3 apps/mobile/gateway/install.py install \
  --hermes "$(command -v hermes)" \
  --endpoint https://hermes.example.com
```

The final command links and starts a new systemd user service only after the conflict checks pass. On a host where activation will be performed later, add `--no-activate`; the unit and launcher are still installed.

Re-running the same command is idempotent. It preserves the token and does not rewrite files or restart an active managed service.

## Pair after configuring ingress

If the endpoint was not known at install time, configure TLS ingress first and then run:

```bash
~/.hermes/mobile-gateway/bin/manage pair \
  --endpoint https://hermes.example.com
```

The command writes `~/.hermes/mobile-gateway/pairing.json` with mode `0600`. It contains the HTTPS endpoint, WSS URL, and pairing secret, so transfer it only over a trusted encrypted channel and delete temporary copies after import. The secret is never emitted to stdout, logs, or process arguments.

The default mobile protocol uses:

- REST: `X-Hermes-Session-Token` request header;
- WebSocket: `wss://…/api/ws?token=…`;
- optional gated/native auth: discover `auth_flows` from `/api/status`, then use the official native OAuth and one-time `/api/auth/ws-ticket` flow only when the installed gateway advertises it.

## Private HTTPS/WSS topology

```text
Mobile app
    │ HTTPS / WSS (private tailnet identity + TLS)
    ▼
Private TLS ingress (for example Tailscale Serve)
    │ HTTP / WS on host loopback only
    ▼
127.0.0.1:9119 ── additive user service ── installed `hermes serve`
```

A typical Tailscale Serve configuration proxies the loopback origin:

```bash
tailscale serve --bg http://127.0.0.1:9119
```

Confirm the HTTPS hostname shown by Tailscale, keep it tailnet-only, and use that `https://…` origin with `manage pair`. Follow current Tailscale Serve documentation if the CLI syntax on your installed Tailscale version differs.

## Public HTTPS/WSS topology

```text
Mobile app
    │ HTTPS / WSS (public DNS + valid certificate)
    ▼
Caddy / equivalent TLS reverse proxy
    │ HTTP / WS on host loopback only
    ▼
127.0.0.1:9119 ── additive user service ── installed `hermes serve`
```

Minimal Caddy site block:

```caddyfile
hermes.example.com {
    reverse_proxy 127.0.0.1:9119
}
```

Keep port `9119` blocked from LAN/WAN access. The ingress must preserve WebSocket upgrades. Protect the host and pairing secret as account credentials; TLS is necessary but does not make a public endpoint low risk.

Do not bind token mode to a non-loopback address. Official Hermes uses its OAuth/password gate for non-loopback binds; that is a separate optional deployment mode, not this compatibility package.

## Status and conflict recovery

```bash
~/.hermes/mobile-gateway/bin/manage status
~/.hermes/mobile-gateway/bin/manage status --json
systemctl --user status hermes-mobile-gateway.service
```

Status output never includes the secret.

If installation reports an existing Hermes process, service, or occupied port, inspect it and choose which deployment should own the gateway. The manager intentionally provides no force/restart option.

## Rollback and uninstall

Both commands are scoped to the additive managed root. They do not invoke the official Hermes uninstall command or touch official config/package/state.

The manager will not stop an active service automatically. Stop this package's service explicitly, verify it is inactive, preview removal, then confirm:

```bash
systemctl --user stop hermes-mobile-gateway.service
systemctl --user is-active hermes-mobile-gateway.service  # should print inactive

~/.hermes/mobile-gateway/bin/manage rollback --dry-run
~/.hermes/mobile-gateway/bin/manage rollback --yes
```

For a direct uninstall, use:

```bash
systemctl --user stop hermes-mobile-gateway.service
~/.hermes/mobile-gateway/bin/manage uninstall --dry-run
~/.hermes/mobile-gateway/bin/manage uninstall --yes
```

`rollback` undoes this first additive installation; `uninstall` is the equivalent explicit removal path. Both remove the pairing secret, service file, launcher, and manifests under `~/.hermes/mobile-gateway`, and unlink only the unit that resolves to that managed service file.

## Behavioral and official compatibility verification

The fast behavioral suite uses a temporary caller `HOME` plus a distinct `HERMES_HOME`, fake Hermes/systemd executables, sentinel official files, and filesystem snapshots. It asserts that version/help probes receive a nested isolated home while the read-only status probe receives the caller's real test `HOME`/`HERMES_HOME`; both paths must remove `PYTHONPATH` and `PYTHONHOME`:

```bash
python -m pytest tests/mobile/test_gateway_install.py -q
```

The opt-in black-box path must run against a separately installed official checkout/package, not this worktree. It launches from a neutral directory with isolated `HOME`/`HERMES_HOME`, removes `PYTHONPATH`, binds a temporary loopback port, and verifies HTTP token auth plus a WebSocket upgrade:

```bash
HERMES_OFFICIAL_EXECUTABLE=/path/to/official/hermes \
HERMES_OFFICIAL_TAG=v2026.8.13 \
HERMES_OFFICIAL_COMMIT=f80f453ae0679347e38abc917c7f94f717bf96c5 \
HERMES_OFFICIAL_VERSION=0.20.1 \
python -m pytest tests/mobile/test_official_gateway_compat.py -q -s
```

CI may set `HERMES_OFFICIAL_PYTHON=/path/to/official/venv/bin/python` instead; the test invokes `python -I -m hermes_cli.main`. The evidence record includes the pinned tag, commit, observed version, executable hash, loopback bind, HTTP status checks, and WebSocket `101` result.
