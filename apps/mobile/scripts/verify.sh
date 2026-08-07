#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"

contract_only=false
if [[ "${1:-}" == "--contract-only" ]]; then
  contract_only=true
elif [[ -n "${1:-}" ]]; then
  printf 'Usage: %s [--contract-only]\n' "$0" >&2
  exit 64
fi

python3 -m unittest apps/mobile/test-server/test_fake_gateway.py -v
python3 apps/mobile/test-server/fake_gateway.py --self-test
printf 'CONTRACT_OK\n'

if [[ "$contract_only" == true ]]; then
  exit 0
fi

if [[ ! -x apps/mobile/android/gradlew ]]; then
  printf 'Android Gradle wrapper is missing or not executable.\n' >&2
  exit 1
fi

(
  cd apps/mobile/android
  ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
)
printf 'ANDROID_OK\n'

if [[ "$(uname -s)" == "Darwin" ]]; then
  if ! command -v xcodegen >/dev/null 2>&1; then
    printf 'xcodegen is required on macOS.\n' >&2
    exit 1
  fi
  (
    cd apps/mobile/ios
    xcodegen generate
    xcodebuild \
      -project HermesMobile.xcodeproj \
      -scheme HermesMobile \
      -sdk iphonesimulator \
      -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
      CODE_SIGNING_ALLOWED=NO \
      test
  )
  printf 'IOS_OK\n'
else
  printf 'IOS_SKIPPED (requires macOS; GitHub Actions provides the authoritative iOS build)\n'
fi

printf 'MOBILE_VERIFY_OK\n'
