# Background receiver recovery

Build the current source with its pinned recursive submodules:

```sh
git submodule update --init --recursive
./gradlew assembleDebug
```

Use a debug installation on an idle test device. Stop other AirPlay receivers
first: they share port 7000. Do not run the process-death test during playback.
Enable **Run in background**, start the server, and leave the app with HOME.
With ADB connected to the same device, run:

```sh
python3 tools/check_service_recovery.py TV_HOST
```

For a separately packaged debug build, pass `--package PACKAGE`. For a selected
ADB transport, pass `--serial SERIAL`. The script uses only Python's standard
library and ADB, rejects packages that cannot use `run-as`, and kills the app
process under its own UID. It requires a new PID, a responsive AirPlay server,
and a foreground service. A force-stop is deliberately different: Android
treats it as a user stop, so it must not be used to test automatic recovery.

Also verify these lifecycle cases manually:

| Case | Expected |
| --- | --- |
| Background enabled, HOME, process killed | Server returns without opening the activity |
| Explicit Stop in the app, then HOME | Server remains stopped |
| Background disabled, HOME without a sender | Server stops and does not return |
| Normal start after an explicit stop | Server starts normally |
| Boot auto-start enabled, actual reboot | Existing boot receiver starts the server |

Restart recovery remains subject to Android's scheduling, foreground-service
rules and vendor power management. It is not a watchdog or a standby/network
wake feature. Changing the background preference does not retroactively change
Android's last `onStartCommand` return value; the normal activity stop/start
path applies the new preference.

## Related work

[PR #18](https://github.com/jqssun/android-airplay-server/pull/18) added the
background preference and quick-boot broadcast support. This change keeps those
settings and boot paths: it addresses process death *after* a started receiver
is already running. A recreated sticky service receives a null intent and must
restart the native server, not merely recreate an empty service instance.

## Device validation

The preparation baseline is v0.0.36 / `a46cca0`, on Xiaomi MiTV-MSSP1,
Android 9 / API 28, firmware PI.5925, ARMv7. Local test packages and ARMv7-only
build settings are outside the contribution. Results are recorded in the PR
description after testing; this document does not claim other devices passed.
