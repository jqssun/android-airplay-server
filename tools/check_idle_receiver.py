#!/usr/bin/env python3
"""Device regression: stale HLS properties must not abort an idle receiver.

Run only while no sender is playing: this opens an AirPlay control connection.
The original UxPlay handler aborts on the first request without an active playlist.
"""
import argparse
import http.client
import plistlib
import sys
import uuid


def check(host, port):
    session = str(uuid.uuid4())
    for name, value in [("actionAtItemEnd", 2), ("forwardEndTime", 0),
                        ("reverseEndTime", 0), ("selectedMediaArray", [])]:
        connection = http.client.HTTPConnection(host, port, timeout=10)
        try:
            connection.request("PUT", f"/setProperty?{name}",
                               plistlib.dumps({"value": value}, fmt=plistlib.FMT_BINARY),
                               {"X-Apple-Session-ID": session,
                                "Content-Type": "application/x-apple-binary-plist"})
            response = connection.getresponse()
            response.read()
            if response.status not in {200, 400, 409, 412}:
                raise ValueError(f"unexpected property status: {response.status}")
            print(f"{name}: HTTP {response.status}")
        finally:
            connection.close()
    connection = http.client.HTTPConnection(host, port, timeout=10)
    try:
        connection.request("GET", "/server-info", headers={"X-Apple-Session-ID": session})
        response = connection.getresponse()
        info = plistlib.loads(response.read())
        if response.status != 200 or not isinstance(info, dict) or "features" not in info:
            raise ValueError("receiver did not answer server-info after stale properties")
    finally:
        connection.close()
    print("PASS: idle receiver survived stale HLS properties and still answers AirPlay")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=7000)
    args = parser.parse_args()
    try:
        check(args.host, args.port)
    except (OSError, http.client.HTTPException, ValueError, plistlib.InvalidFileException) as error:
        sys.exit(f"FAIL: {error}")


if __name__ == "__main__":
    main()
