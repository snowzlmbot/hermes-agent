# Hermes Mobile Beta 0.2.0

这是专用移动分支的阶段性Beta预发布，不是RC、App Store或Google Play正式版本。

## 本版重点

- Android可安装Beta APK，新增中英文关键界面、无障碍标签和删除确认。
- 静态配对token只走HTTPS header；每次WebSocket连接先换取30秒单次ticket。
- 官方Hermes保持不变；附加sidecar只写入 `~/.hermes/mobile-gateway`。
- 安装器支持probe、dry-run、严格inventory/权限/systemd检查。
- 显式 `--show-pairing` 可在真实安装后输出Android端点与配对密钥；默认不泄漏。
- 同机Android Termux可显式使用 `http://127.0.0.1:9120`，不允许远程明文。
- iOS OAuth全链路HTTPS、端点/profile绑定迁移、录音上限与错误可见性已加固。
- CI生成并验证unsigned iPhoneOS xcarchive，但本Beta不提供可安装iOS包。

## Android资产

- `hermes-mobile-android-beta.apk`
- `SHA256SUMS.txt`
- `android-pairing-flow.svg`
- `deployment-modes.svg`

安装和配对说明：[`android/README.md`](./android/README.md)

## 已知限制

- Android APK使用测试签名，仅用于Beta验证。
- iOS尚未完成受保护签名导出、TestFlight和真机验收。
- 公网部署仍需用户自己的TLS ingress；sidecar只监听loopback。
- Beta验证不等于正式商店审核、隐私声明或长期支持承诺。
