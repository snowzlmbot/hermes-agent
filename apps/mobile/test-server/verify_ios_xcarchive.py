from __future__ import annotations

import plistlib
import sys
from pathlib import Path, PurePosixPath
from typing import Any


EXPECTED_APPLICATION_PATH = PurePosixPath("Applications/HermesMobile.app")
EXPECTED_BUNDLE_ID = "com.snowzlmbot.hermes.mobile"


class ArchiveVerificationError(ValueError):
    pass


def load_plist(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ArchiveVerificationError(f"missing plist: {path}")
    try:
        with path.open("rb") as handle:
            value = plistlib.load(handle)
    except (OSError, plistlib.InvalidFileException) as error:
        raise ArchiveVerificationError(f"invalid plist {path}: {error}") from error
    if not isinstance(value, dict):
        raise ArchiveVerificationError(f"plist root is not a dictionary: {path}")
    return value


def verify_archive(raw_path: str | Path) -> Path:
    archive = Path(raw_path)
    if archive.suffix != ".xcarchive" or not archive.is_dir():
        raise ArchiveVerificationError(f"expected an xcarchive directory: {archive}")

    archive_info = load_plist(archive / "Info.plist")
    if archive_info.get("ArchiveVersion") != 2:
        raise ArchiveVerificationError("xcarchive ArchiveVersion must be 2")
    if (
        archive_info.get("Name") != "HermesMobile"
        or archive_info.get("SchemeName") != "HermesMobile"
    ):
        raise ArchiveVerificationError("xcarchive name and scheme must both be HermesMobile")

    properties = archive_info.get("ApplicationProperties")
    if not isinstance(properties, dict):
        raise ArchiveVerificationError("xcarchive ApplicationProperties is missing")
    application_path = properties.get("ApplicationPath")
    if (
        not isinstance(application_path, str)
        or PurePosixPath(application_path) != EXPECTED_APPLICATION_PATH
    ):
        raise ArchiveVerificationError(
            f"unexpected xcarchive ApplicationPath: {application_path!r}"
        )
    if properties.get("CFBundleIdentifier") != EXPECTED_BUNDLE_ID:
        raise ArchiveVerificationError("xcarchive bundle identifier is incorrect")
    for key in ("CFBundleShortVersionString", "CFBundleVersion"):
        if not isinstance(properties.get(key), str) or not properties[key]:
            raise ArchiveVerificationError(f"xcarchive {key} is missing")
    for key in ("SigningIdentity", "Team"):
        if properties.get(key):
            raise ArchiveVerificationError(f"xcarchive must be unsigned: {key} is present")

    products = archive / "Products"
    app = products / Path(*EXPECTED_APPLICATION_PATH.parts)
    if app.is_symlink() or not app.is_dir():
        raise ArchiveVerificationError(f"archived application is missing: {app}")
    try:
        app.resolve().relative_to(products.resolve())
    except ValueError as error:
        raise ArchiveVerificationError("xcarchive ApplicationPath escapes Products") from error

    app_info = load_plist(app / "Info.plist")
    if app_info.get("CFBundleIdentifier") != EXPECTED_BUNDLE_ID:
        raise ArchiveVerificationError("archived application bundle identifier is incorrect")
    if app_info.get("CFBundlePackageType") != "APPL":
        raise ArchiveVerificationError("archived product is not an iOS application")

    executable_name = app_info.get("CFBundleExecutable")
    if not isinstance(executable_name, str) or not executable_name:
        raise ArchiveVerificationError("archived application executable name is missing")
    executable = app / executable_name
    if (
        executable.is_symlink()
        or not executable.is_file()
        or executable.stat().st_size == 0
    ):
        raise ArchiveVerificationError(f"archived application executable is invalid: {executable}")

    ats = app_info.get("NSAppTransportSecurity")
    if (
        ats != {"NSAllowsArbitraryLoads": False}
        or app_info.get("HermesAllowsInsecureTransport") is not False
    ):
        raise ArchiveVerificationError(
            "archived application does not use the release transport policy"
        )

    embedded_profiles = list(app.rglob("embedded.mobileprovision"))
    code_signatures = list(app.rglob("_CodeSignature"))
    if embedded_profiles or code_signatures:
        raise ArchiveVerificationError("xcarchive must be unsigned and contain no signing material")

    return app


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {Path(argv[0]).name} <HermesMobile.xcarchive>", file=sys.stderr)
        return 64
    try:
        app = verify_archive(argv[1])
    except (ArchiveVerificationError, OSError) as error:
        print(f"iOS xcarchive verification failed: {error}", file=sys.stderr)
        return 1
    print(f"unsigned iOS xcarchive verified: {app}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
