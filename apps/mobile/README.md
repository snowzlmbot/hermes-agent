# Hermes Mobile test builds

The mobile clients connect to an existing official Hermes Agent gateway. The first-test compatibility path uses the official session-token REST/WebSocket protocol; native OAuth remains optional.

## Download the Android test build

The stable build entry is the **Mobile Android** Actions page on the delivery branch:

<https://github.com/snowzlmbot/hermes-agent/actions/workflows/mobile-android.yml?query=branch%3Ahermes-agent-Mobile-app>

1. Open the newest successful run whose branch is `hermes-agent-Mobile-app`.
2. Record the run's commit SHA.
3. Download the artifact named `hermes-mobile-android-<commit-sha>`.
4. Use the Debug APK for test installation. The Release output is a verification build and may be unsigned.
5. Keep the Actions run URL and commit SHA with test results. Artifacts currently expire after 14 days.

With GitHub CLI, list the stable-branch runs and then download the selected exact-SHA artifact:

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

Install the downloaded Debug APK on a test device only after verifying the run and artifact came from the expected commit:

```bash
adb install -r path/to/app-debug.apk
```

## iOS test output

The stable **Mobile iOS** Actions page is:

<https://github.com/snowzlmbot/hermes-agent/actions/workflows/mobile.yml?query=branch%3Ahermes-agent-Mobile-app>

Its current artifacts contain simulator/unsigned verification output, not a signed App Store or physical-device distribution. Use the artifact whose name embeds the exact run commit SHA.

## Prepare an official Hermes gateway

Use the additive compatibility package at [`gateway/README.md`](./gateway/README.md). It:

- probes official Hermes `v0.20.1` capabilities without importing this repository's runtime;
- stores every managed resource under `~/.hermes/mobile-gateway`;
- generates owner-only `0600` token and pairing manifests;
- launches the installed `hermes serve` on `127.0.0.1` only;
- requires HTTPS/WSS ingress for both public and private mobile connections;
- never upgrades or edits the official package, `.env`, config, profiles, or state database;
- checks the current `HOME`/`HERMES_HOME` for an existing Hermes gateway before installing;
- refuses to stop, restart, or replace an existing Hermes process/service.

After TLS ingress is ready, import the endpoint and secret from the `0600` pairing manifest into the app over a trusted encrypted channel. Never paste the secret into shell arguments or send it over plaintext HTTP/WS.
