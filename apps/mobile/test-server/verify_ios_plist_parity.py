import plistlib
import sys


def load(path):
    with open(path, "rb") as handle:
        value = plistlib.load(handle)
    value.pop("NSAppTransportSecurity")
    value.pop("HermesAllowsInsecureTransport")
    return value


assert load(sys.argv[1]) == load(sys.argv[2]), "iOS Info.plist files drifted"
print("iOS Info.plist parity verified")
