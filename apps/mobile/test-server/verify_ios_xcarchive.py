from __future__ import annotations

import plistlib
import stat
import struct
import sys
from pathlib import Path, PurePosixPath
from typing import Any


EXPECTED_APPLICATION_PATH = PurePosixPath("Applications/HermesMobile.app")
EXPECTED_BUNDLE_ID = "com.snowzlmbot.hermes.mobile"
EXPECTED_EXECUTABLE = "HermesMobile"
PLATFORM_IOS = 2
LC_CODE_SIGNATURE = 0x1D
LC_VERSION_MIN_IPHONEOS = 0x25
LC_BUILD_VERSION = 0x32


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


def _parse_thin_macho(data: bytes, offset: int, limit: int) -> tuple[set[int], bool]:
    magic = data[offset : offset + 4]
    formats = {
        b"\xfe\xed\xfa\xce": (">", False),
        b"\xce\xfa\xed\xfe": ("<", False),
        b"\xfe\xed\xfa\xcf": (">", True),
        b"\xcf\xfa\xed\xfe": ("<", True),
    }
    if magic not in formats:
        raise ArchiveVerificationError("application executable is not a Mach-O binary")
    endian, is_64_bit = formats[magic]
    header_size = 32 if is_64_bit else 28
    if offset + header_size > limit:
        raise ArchiveVerificationError("Mach-O header is truncated")
    ncmds, sizeofcmds = struct.unpack_from(f"{endian}II", data, offset + 16)
    command_offset = offset + header_size
    command_end = command_offset + sizeofcmds
    if command_end > limit:
        raise ArchiveVerificationError("Mach-O load commands are truncated")

    platforms: set[int] = set()
    has_code_signature = False
    for _ in range(ncmds):
        if command_offset + 8 > command_end:
            raise ArchiveVerificationError("Mach-O load command header is truncated")
        command, command_size = struct.unpack_from(
            f"{endian}II", data, command_offset
        )
        if command_size < 8 or command_offset + command_size > command_end:
            raise ArchiveVerificationError("Mach-O load command size is invalid")
        if command == LC_CODE_SIGNATURE:
            has_code_signature = True
        elif command == LC_VERSION_MIN_IPHONEOS:
            platforms.add(PLATFORM_IOS)
        elif command == LC_BUILD_VERSION:
            if command_size < 24:
                raise ArchiveVerificationError("Mach-O build version command is truncated")
            platforms.add(struct.unpack_from(f"{endian}I", data, command_offset + 8)[0])
        command_offset += command_size

    if not platforms:
        raise ArchiveVerificationError("Mach-O executable has no Apple platform command")
    return platforms, has_code_signature


def inspect_macho(path: Path) -> set[int]:
    data = path.read_bytes()
    magic = data[:4]
    fat_formats = {
        b"\xca\xfe\xba\xbe": (">", False),
        b"\xbe\xba\xfe\xca": ("<", False),
        b"\xca\xfe\xba\xbf": (">", True),
        b"\xbf\xba\xfe\xca": ("<", True),
    }
    if magic not in fat_formats:
        platforms, has_code_signature = _parse_thin_macho(data, 0, len(data))
    else:
        endian, is_64_bit = fat_formats[magic]
        if len(data) < 8:
            raise ArchiveVerificationError("universal Mach-O header is truncated")
        architecture_count = struct.unpack_from(f"{endian}I", data, 4)[0]
        if architecture_count == 0:
            raise ArchiveVerificationError("universal Mach-O has no architectures")
        entry_size = 32 if is_64_bit else 20
        table_end = 8 + architecture_count * entry_size
        if table_end > len(data):
            raise ArchiveVerificationError("universal Mach-O table is truncated")
        platforms = set()
        has_code_signature = False
        for index in range(architecture_count):
            entry = 8 + index * entry_size
            if is_64_bit:
                slice_offset, slice_size = struct.unpack_from(
                    f"{endian}QQ", data, entry + 8
                )
            else:
                slice_offset, slice_size = struct.unpack_from(
                    f"{endian}II", data, entry + 8
                )
            slice_end = slice_offset + slice_size
            if slice_offset < table_end or slice_end > len(data):
                raise ArchiveVerificationError("universal Mach-O slice is invalid")
            slice_platforms, slice_signed = _parse_thin_macho(
                data, slice_offset, slice_end
            )
            platforms.update(slice_platforms)
            has_code_signature = has_code_signature or slice_signed

    if platforms != {PLATFORM_IOS}:
        raise ArchiveVerificationError(
            f"Mach-O executable is not exclusively device iOS: platforms={sorted(platforms)}"
        )
    if has_code_signature:
        raise ArchiveVerificationError("Mach-O executable contains a code signature command")
    return platforms


def verify_archive(raw_path: str | Path) -> tuple[Path, Path]:
    archive = Path(raw_path)
    if archive.suffix != ".xcarchive" or not archive.is_dir():
        raise ArchiveVerificationError(f"expected an xcarchive directory: {archive}")

    archive_info = load_plist(archive / "Info.plist")
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
    for key in ("SigningIdentity", "Team"):
        value = properties.get(key)
        if value not in (None, "", "-"):
            raise ArchiveVerificationError(
                f"xcarchive must be unsigned: {key}={value!r}"
            )
    if properties.get("ProvisionedDevices"):
        raise ArchiveVerificationError("xcarchive must not contain ProvisionedDevices")

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
        raise ArchiveVerificationError("archived product is not an application")
    executable_name = app_info.get("CFBundleExecutable")
    if executable_name != EXPECTED_EXECUTABLE:
        raise ArchiveVerificationError(
            f"archived application executable is incorrect: {executable_name!r}"
        )
    executable = app / EXPECTED_EXECUTABLE
    if executable.is_symlink() or not executable.is_file():
        raise ArchiveVerificationError(f"archived executable is missing: {executable}")
    if not executable.stat().st_mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH):
        raise ArchiveVerificationError("archived Mach-O does not have executable permissions")
    inspect_macho(executable)

    ats = app_info.get("NSAppTransportSecurity")
    if (
        ats != {"NSAllowsArbitraryLoads": False}
        or app_info.get("HermesAllowsInsecureTransport") is not False
    ):
        raise ArchiveVerificationError(
            "Release ATS must have no arbitrary loads opt-in"
        )

    embedded_profiles = list(app.rglob("embedded.mobileprovision"))
    if embedded_profiles:
        raise ArchiveVerificationError(
            f"unsigned archive contains embedded.mobileprovision: {embedded_profiles[0]}"
        )
    code_signatures = list(app.rglob("_CodeSignature"))
    if code_signatures:
        raise ArchiveVerificationError(
            f"unsigned archive contains _CodeSignature: {code_signatures[0]}"
        )
    return app, executable


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {Path(argv[0]).name} <archive.xcarchive>", file=sys.stderr)
        return 64
    try:
        app, executable = verify_archive(argv[1])
    except (ArchiveVerificationError, OSError, struct.error) as error:
        print(f"iOS xcarchive verification failed: {error}", file=sys.stderr)
        return 1
    print("unsigned iPhoneOS xcarchive verified")
    print(f"archive={Path(argv[1])}")
    print(f"application={app}")
    print(f"bundle_id={EXPECTED_BUNDLE_ID}")
    print(f"executable={executable.name}")
    print("macho_platform=IOS")
    print("signing_material=absent")
    print("release_ats=arbitrary-loads-disabled")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
