# Mobile Delivery Milestones

These milestones define staged, independently usable mobile deliveries. Each stage must pass Android, iOS, protocol, and production-gateway checks on the same commit before it advances.

## 1. Mobile Preview — Core Usable

Goal: support normal daily gateway conversations on both platforms.

Acceptance criteria:

- Token and native OAuth/PKCE sign-in
- Secure credential storage, refresh coalescing, and one-time WebSocket tickets
- Create, stream, close, resume, archive, restore, and search sessions
- Streaming messages, reasoning, tool output, approvals, clarification, secret, and sudo prompts
- Model and reasoning controls
- Attachments and voice interactions
- Logout clears credentials, transient tickets, and active identities
- Android and iOS builds and tests pass with production-gateway E2E

Status: delivered; regressions remain release-blocking.

## 2. Mobile Beta — Stable and Secure

Goal: make the preview reliable and safe across network and lifecycle transitions.

Acceptance criteria:

- Release builds reject HTTP and WS
- Debug cleartext consent applies only to token and one-time-ticket connections
- Native OAuth rejects HTTP and WS in every build, including loopback
- Foreground/background reconnect and refresh behavior is deterministic
- Notification routes restore only the intended stored session
- Runtime and stored session identities remain isolated
- Event deduplication and replay guards survive reconnects
- Archived-session and profile mutations are generation scoped
- Accessibility smoke checks and localized error paths pass
- Android, iOS, protocol, and production E2E pass on one commit

Status: in progress.

## 3. Mobile RC — Private Device Candidate

Goal: produce a non-public release candidate for real-device acceptance.

Acceptance criteria:

- Signed Android and iOS release candidates install on supported devices
- Token and OAuth sign-in work against a production gateway
- Background recovery, notification taps, attachments, microphone, and voice playback pass on devices
- Network changes, process termination, expired tokens, and gateway restarts recover safely
- Upgrade and credential migration from the preview build preserve only approved non-secret state
- Battery, memory, launch, scrolling, and long-session performance meet the release budget
- No high-severity security, privacy, or data-loss findings remain

Status: pending Mobile Beta.

## 4. Mobile 1.0 — Release Ready

Goal: complete the production release gate after private device acceptance.

Acceptance criteria:

- RC device matrix is accepted
- Store metadata, privacy disclosures, permissions, and support paths are complete
- Crash diagnostics and privacy-safe operational telemetry are verified
- Upgrade, rollback, backup exclusion, and logout behavior are documented and tested
- Release artifacts are reproducible from the immutable delivery commit
- Final Android, iOS, protocol, production E2E, and artifact checks pass on that commit

Status: pending Mobile RC.
