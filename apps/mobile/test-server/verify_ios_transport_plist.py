import plistlib
import sys
from pathlib import Path

mode, raw_path = sys.argv[1:]
normalized_mode = mode.lower()
if normalized_mode not in {"debug", "release"}:
    raise SystemExit(f"unsupported transport policy mode: {mode}")
expected = normalized_mode == "debug"
with Path(raw_path).open("rb") as handle:
    info = plistlib.load(handle)
ats = info.get("NSAppTransportSecurity", {})
allowed_ats_keys = {"NSAllowsArbitraryLoads"}
if set(ats) != allowed_ats_keys:
    raise SystemExit(f"{mode} ATS policy is not closed: {sorted(ats)}")
if "HermesAllowsInsecureTransport" not in info:
    raise SystemExit(f"{mode} transport capability is missing")
checks = {
    "NSAllowsArbitraryLoads": ats.get("NSAllowsArbitraryLoads", False),
    "HermesAllowsInsecureTransport": info.get("HermesAllowsInsecureTransport", False),
}
wrong = {key: value for key, value in checks.items() if value is not expected}
if wrong:
    raise SystemExit(f"{mode} transport policy mismatch: {wrong}")
print(f"{mode} transport policy verified: {raw_path}")
