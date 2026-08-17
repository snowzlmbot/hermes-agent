# Hermes Mobile Beta 0.2.0

The mobile clients connect through the independent Hermes Mobile sidecar in
[`gateway/README.md`](./gateway/README.md). The sidecar is the only TLS-ingress
target: it listens on `127.0.0.1:9120`, bridges to an unchanged official
Hermes `v0.20.1` gateway on `127.0.0.1:9119`, and keeps the two credentials
separate.

Android安装与配对首先阅读：[`android/README.md`](./android/README.md)。
Termux区别、Agent指令和网关安全细节见：
[`gateway/README.md`](./gateway/README.md)。图示版见
[`gateway/assets/android-pairing-flow.svg`](./gateway/assets/android-pairing-flow.svg)。

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

## 下载 Android Beta

唯一面向用户的下载入口是 GitHub **Pre-release**：

<https://github.com/snowzlmbot/hermes-agent/releases>

选择最新的 `Hermes Mobile Beta`，下载：

- `hermes-mobile-android-beta.apk`
- `SHA256SUMS.txt`
- `android-pairing-flow.svg`（离线教程）

不要从 Actions 下载或安装中间产物。Release 页面会标明专用分支commit SHA；APK与校验文件必须来自同一Beta Release。

校验并安装：

```bash
sha256sum -c SHA256SUMS.txt
adb install -r hermes-mobile-android-beta.apk
```

也可在Android设备上直接打开APK安装；系统提示“允许此来源”时只授权你用于下载Release的浏览器或文件管理器。Beta不是正式商店版本。

## iOS Beta 状态

当前Beta Release不附带可安装的iOS包。CI中的`.xcarchive`仅证明`iphoneos`设备构建可归档且未签名，不是IPA、TestFlight或真机安装包。完成签名、受保护导出和真实设备验收前，不向用户提供iOS下载资产。

## Official compatibility pin

The official compatibility workflow checks out and verifies tag
`v2026.8.13`, peeled commit
`f80f453ae0679347e38abc917c7f94f717bf96c5`, and runtime version `v0.20.1` in a
distinct checkout. It also asserts that imported `hermes_cli` resolves below
that checkout and that the integration lifecycle test is actually collected
with the `integration` marker.
