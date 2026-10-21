# Stale HLS properties without a playlist

An idle v0.0.36 receiver can abort in `http_handler_set_property` when a sender
delivers a property after the current HLS playlist has been removed. On the
project's pinned UxPlay revision, `hls_get_current_video()` returns null but the
handler asserts before deciding whether the property is handled.

Build with the pinned recursive submodules and install on an idle test device:

```sh
git submodule update --init --recursive
./gradlew assembleDebug
python3 tools/check_idle_receiver.py TV_HOST
```

Use Python 3 and replace `TV_HOST` with the receiver's address. Run only with no
active sender: the test opens AirPlay control connections. Stop other receivers
that use the same port. Unpatched builds may crash and need a manual restart.

The regression sends four stale properties and then requires a valid
`/server-info` response. It accepts an empty success response or a client-error
response for an invalid property lifecycle; connection closure, server failure,
or an unavailable receiver fail the test. No ADB access or extra dependencies
are required by the script.

## Why a patch for the pinned dependency

Current upstream UxPlay already removed the playlist fetch/assert from this
handler in [120a166](https://github.com/FDH2/UxPlay/commit/120a166ae0ff34a6a8a6067be18dfef3477e96f6).
This is not a report of a new bug in current UxPlay. The Android project pins
`df67c212`, where `selectedMediaArray` still uses the playlist. The small patch
guards the missing-playlist case and keeps active-playlist handling unchanged.
It uses the existing `post_error` response rather than changing protocol behavior
throughout the library. A future submodule update should reconcile/remove this
patch with the other Android-specific UxPlay patches.

## Validation boundary

Baseline: Android receiver v0.0.36 / `a46cca0`, Xiaomi MiTV-MSSP1, Android 9 /
API 28, firmware PI.5925, ARMv7. The patch is independent of direct-surface
rendering and service restart changes. This idle regression does not verify
active HLS playback or solve unrelated sender/playlist compatibility issues.
