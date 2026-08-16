#!/usr/bin/env python3
"""Supervise the package-owned official gateway and mobile sidecar children."""

from __future__ import annotations

import argparse
import os
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


BIND_HOST = "127.0.0.1"
SECRET_MODE = 0o600


def _read_secret(path: Path, *, label: str) -> str:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f"{label} must be a regular file")
    if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != SECRET_MODE:
        raise SystemExit(f"{label} must be owned by the current user with mode 0600")
    token = path.read_text(encoding="ascii").strip()
    if len(token) != 64 or any(character not in "0123456789abcdef" for character in token):
        raise SystemExit(f"{label} is invalid")
    return token


def _wait_for_upstream(process: subprocess.Popen[bytes], port: int) -> None:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 60
    request = urllib.request.Request(f"http://{BIND_HOST}:{port}/api/status")
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("official Hermes gateway exited before readiness")
        try:
            with opener.open(request, timeout=1) as response:
                if response.status == 200:
                    response.read(1)
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.1)
    raise RuntimeError("official Hermes gateway did not become ready")


def _terminate(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Supervise the Hermes Mobile sidecar")
    parser.add_argument("--hermes", type=Path, required=True)
    parser.add_argument("--sidecar", type=Path, required=True)
    parser.add_argument("--internal-token-file", type=Path, required=True)
    parser.add_argument("--external-token-file", type=Path, required=True)
    parser.add_argument("--upstream-port", type=int, required=True, choices=range(1024, 65536))
    parser.add_argument("--sidecar-port", type=int, required=True, choices=range(1024, 65536))
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.upstream_port == args.sidecar_port:
        raise SystemExit("sidecar and upstream ports must differ")

    internal_token = _read_secret(args.internal_token_file, label="internal token file")
    # Validate the external credential here without retaining a second copy.
    _read_secret(args.external_token_file, label="external token file")

    official_environment = os.environ.copy()
    official_environment.pop("PYTHONPATH", None)
    official_environment.pop("PYTHONHOME", None)
    official_environment.update(
        {
            "PYTHONDONTWRITEBYTECODE": "1",
            "HERMES_DASHBOARD_SESSION_TOKEN": internal_token,
        }
    )
    sidecar_environment = {
        key: value
        for key, value in os.environ.items()
        if key in {"HOME", "LANG", "LC_ALL", "LC_CTYPE", "PATH", "TMPDIR", "TZ"}
    }
    sidecar_environment["PYTHONDONTWRITEBYTECODE"] = "1"

    official: subprocess.Popen[bytes] | None = None
    sidecar: subprocess.Popen[bytes] | None = None
    stopping = False

    def request_stop(_signum: int, _frame: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)

    try:
        official = subprocess.Popen(
            [
                str(args.hermes),
                "serve",
                "--host",
                BIND_HOST,
                "--port",
                str(args.upstream_port),
            ],
            env=official_environment,
            stdin=subprocess.DEVNULL,
        )
        _wait_for_upstream(official, args.upstream_port)
        sidecar = subprocess.Popen(
            [
                sys.executable,
                "-I",
                str(args.sidecar),
                "--host",
                BIND_HOST,
                "--port",
                str(args.sidecar_port),
                "--upstream-host",
                BIND_HOST,
                "--upstream-port",
                str(args.upstream_port),
                "--external-token-file",
                str(args.external_token_file),
                "--internal-token-file",
                str(args.internal_token_file),
            ],
            env=sidecar_environment,
            stdin=subprocess.DEVNULL,
        )

        while not stopping:
            if official.poll() is not None or sidecar.poll() is not None:
                return 1
            time.sleep(0.2)
        return 0
    except (OSError, RuntimeError) as error:
        print(f"mobile sidecar startup failed: {error}", file=sys.stderr)
        return 1
    finally:
        _terminate(sidecar)
        _terminate(official)


if __name__ == "__main__":
    raise SystemExit(main())
