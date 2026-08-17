# Hermes Mobile sidecar

This package is an independent, additive compatibility layer for the official
Hermes Agent release `v2026.8.13` (peeled commit
`f80f453ae0679347e38abc917c7f94f717bf96c5`, runtime version `v0.20.1`). It
never installs, upgrades, imports, or edits the official checkout.

## 给安装 Agent 的强制指令

> 请阅读并严格执行此安装说明：
>
> https://github.com/snowzlmbot/hermes-agent/blob/hermes-agent-Mobile-app/apps/mobile/gateway/README.md
>
> 只安装 `~/.hermes/mobile-gateway` 下的附加资源，不修改或升级官方 Hermes。先执行 `probe` 和 `install --dry-run`；如果检测到已有服务、权限异常、未知 systemd 状态或端口冲突，立即停止并报告。

Agent 不得替换现有服务、猜测 Python 环境、把秘钥写入 URL，或在检查失败后继续。只有主人明确要求时才可追加 `--show-pairing` 输出配对秘钥。

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

## 选择安装场景

![远端 Linux 与同机 Termux 模式](assets/deployment-modes.svg)

| 场景 | sidecar 安装位置 | App 填写端点 | 激活方式 |
|---|---|---|---|
| Android 原生 App，Hermes 在 Linux/服务器 | Hermes 所在 Linux 用户账户 | `https://域名或IP:TLS端口` | `systemd --user` |
| iOS 或其他原生客户端 | Hermes 所在 Linux 用户账户 | `https://域名或IP:TLS端口` | `systemd --user` |
| Android 原生 App 与 Hermes 同在本机 Termux | 同一 Android 的 Termux | `http://127.0.0.1:9120`，并在 Beta App 中明确允许不安全连接 | `--no-activate` 后前台运行 `bin/launch` |

Termux 模式只开放同设备 loopback HTTP；任何 LAN/WAN HTTP、`0.0.0.0` 绑定或远程明文端点都会被拒绝。

## Install

### A. Android App / iOS / 其他客户端连接远端 Linux Hermes

使用官方 Hermes 命令和它所属 Python 环境；不要让 Agent 猜路径：

```bash
HERMES_BIN="$(command -v hermes)"
HERMES_PYTHON="/absolute/path/to/official/.venv/bin/python"
MOBILE_ENDPOINT="https://hermes.example.com:443"

python3 apps/mobile/gateway/install.py probe \
  --hermes "$HERMES_BIN" \
  --python "$HERMES_PYTHON"

python3 apps/mobile/gateway/install.py install --dry-run \
  --hermes "$HERMES_BIN" \
  --python "$HERMES_PYTHON" \
  --endpoint "$MOBILE_ENDPOINT"

python3 apps/mobile/gateway/install.py install \
  --hermes "$HERMES_BIN" \
  --python "$HERMES_PYTHON" \
  --endpoint "$MOBILE_ENDPOINT" \
  --show-pairing
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

### B. Android Termux 同机安装

Termux 不使用 `systemd --user`。只有 Android App 与 Hermes 在同一台手机时使用此模式：

```bash
pkg update
pkg install -y python git

HERMES_BIN="$(command -v hermes)"
HERMES_PYTHON="$(python3 -c 'import sys; print(sys.executable)')"
LOCAL_ENDPOINT="http://127.0.0.1:9120"

python3 apps/mobile/gateway/install.py probe \
  --hermes "$HERMES_BIN" --python "$HERMES_PYTHON"

python3 apps/mobile/gateway/install.py install --dry-run --no-activate \
  --hermes "$HERMES_BIN" --python "$HERMES_PYTHON" \
  --endpoint "$LOCAL_ENDPOINT" --allow-loopback-http

python3 apps/mobile/gateway/install.py install --no-activate \
  --hermes "$HERMES_BIN" --python "$HERMES_PYTHON" \
  --endpoint "$LOCAL_ENDPOINT" --allow-loopback-http --show-pairing

termux-wake-lock
~/.hermes/mobile-gateway/bin/launch
```

保持最后一个命令在前台运行；按 `Ctrl-C` 停止。Android Beta App 中填写 `http://127.0.0.1:9120`，粘贴命令末尾打印的配对秘钥，并明确勾选允许不安全连接。该例外绝不能用于远程IP。

## Pairing and ingress

Configure TLS ingress to the sidecar loopback port, then pair when the endpoint
was not supplied during installation:

```bash
~/.hermes/mobile-gateway/bin/manage pair --dry-run \
  --endpoint https://hermes.example.com:443

~/.hermes/mobile-gateway/bin/manage pair \
  --endpoint https://hermes.example.com:443 \
  --show-pairing
```

The ingress must preserve WebSocket upgrades and forward to
`http://127.0.0.1:9120`. Keep both loopback ports blocked from LAN/WAN access.

### Android App 配对步骤

![Android App 配对流程](assets/android-pairing-flow.svg)

1. 从 GitHub **Beta Release** 下载并安装 `hermes-mobile-android-beta.apk`；不要再从 Actions 手工找构建。
2. 让安装 Agent 完成 `probe`、`install --dry-run` 和带 `--show-pairing` 的真实安装。
3. 记录命令最后输出的 `Android App endpoint` 与 `Android App pairing secret`；秘钥等同密码。
4. 打开 App，在“Gateway address”填写完整端点（含 `https://` 与端口），在“Session token”粘贴配对秘钥。
5. 远端 Linux 模式不要勾选明文连接；仅同机 Termux 的 `http://127.0.0.1:9120` 需要明确勾选。
6. 点击“Connect”。首次连接会先用秘钥换取30秒单次ticket，再建立WebSocket。
7. 连接后打开会话列表，执行一次断开/重连；每次重连都应重新mint ticket。

App 中不要填写 `websocket_url`，不要把秘钥拼在地址、查询参数或截图里。若 Agent 未输出端点/秘钥，先运行 `manage status --json` 检查，再显式运行 `manage pair ... --show-pairing`，不要直接读取或放宽文件权限。

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
