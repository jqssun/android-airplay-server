#!/usr/bin/env python3
"""Device regression: an idle debug receiver must recover after process death.

Start the receiver with Run in background enabled, then leave it with HOME.
Run only on a test device with no active sender and no other receiver listening.
This kills the debug app's process under its own UID, not with force-stop.
It does not install apps, change preferences, grant permissions, or use root.
"""
import argparse
import http.client
import plistlib
import re
import subprocess
import sys
import time
import uuid


def check(host, package, adb="adb", port=7000, timeout=30, serial=None):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", package):
        raise ValueError("invalid Android package name")
    command = [adb] + (["-s", serial] if serial else [])
    session = str(uuid.uuid4())

    def shell(*args):
        return subprocess.check_output(command + ["shell", *args], text=True, timeout=15).strip()

    def pid():
        # pidof returns 1 while the process is absent.
        result = subprocess.run(command + ["shell", "pidof", package],
                                text=True, capture_output=True, timeout=15)
        if result.returncode not in (0, 1):
            raise RuntimeError(result.stderr.strip() or "ADB pidof failed")
        return result.stdout.strip()

    def available():
        connection = http.client.HTTPConnection(host, port, timeout=2)
        try:
            connection.request("GET", "/server-info", headers={"X-Apple-Session-ID": session})
            response = connection.getresponse()
            body = response.read()
            return response.status == 200 and "features" in plistlib.loads(body)
        except (OSError, http.client.HTTPException, plistlib.InvalidFileException, ValueError):
            return False
        finally:
            connection.close()

    # Reject release packages before doing anything disruptive.
    uid = shell("run-as", package, "id", "-u")
    if not uid.isdigit() or int(uid) < 10000:
        raise RuntimeError("run-as did not identify a debuggable application UID")
    before = pid()
    if not before.isdigit():
        raise RuntimeError("expected exactly one running receiver process")
    component = package + "/io.github.jqssun.airplay.service.AirPlayService"
    if "isForeground=true" not in shell("dumpsys", "activity", "services", component):
        raise RuntimeError("start the foreground receiver and press HOME first")
    if not available():
        raise RuntimeError("receiver did not answer server-info before the test")

    started = time.monotonic()
    shell("run-as", package, "kill", "-9", before)
    while time.monotonic() - started < timeout:
        after = pid()
        if after.isdigit() and after != before and available():
            if "isForeground=true" in shell("dumpsys", "activity", "services", component):
                print(f"PASS: new process restored foreground receiver in {time.monotonic() - started:.2f}s")
                return
        time.sleep(0.5)
    raise RuntimeError(f"receiver did not recover within {timeout:g}s after process death")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="same device as the selected ADB transport")
    parser.add_argument("--package", default="io.github.jqssun.airplay")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--port", type=int, default=7000)
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    try:
        check(args.host, args.package, args.adb, args.port, args.timeout, args.serial)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        sys.exit(f"FAIL: {error}")


if __name__ == "__main__":
    main()
