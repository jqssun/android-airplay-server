# Direct SurfaceView output: experimental device trial

The `direct_surface` preference defaults to `false`. When enabled, mirroring
decodes directly into the display SurfaceView instead of blitting through the
view-sized GL window. This can preserve video dimensions on TVs with a 4K panel
and 1080p Android UI, provided the hardware composer accepts the video layer.
It does not force the sender to transmit higher resolution.

Set via the existing DUMP-protected SettingsReceiver, then restart the server:

```sh
adb shell am broadcast -n io.github.jqssun.airplay/.SettingsReceiver \
  -a io.github.jqssun.airplay.SET_SETTING --es key direct_surface --es value true
```

For a separate applicationId, use the actual package before `/` and the full
`io.github.jqssun.airplay.SettingsReceiver` class name after it. This repository's
normal package and build settings are unchanged by the trial feature.

## Validation

1. Connect a sender and leave its Home screen visible, without opening Photos
   or rotating first. Verify hardware composition on this initial connection
   as well as after subsequent display-mode changes.
2. Generate sender activity, then pause on small text or a detail pattern.
3. Record decoder dimensions and capture `adb shell dumpsys SurfaceFlinger`.
4. Run `python3 tools/check_render_path.py dump.txt --decoded-size WIDTHxHEIGHT`.
   For a separate package, also pass `--package PACKAGE`.
5. Compare visual detail, sound and frame pacing. Exercise fullscreen changes,
   rotation, background/resume, disconnect and reconnect. Verify default GL mode
   still behaves as before.

The checker requires one visible matching buffer and its uniquely associated
HWC entry, full source dimensions, and Device/Sideband composition. A pass proves
those properties in the dump; physical panel detail still requires visual or
hardware-specific validation. A full-sized Client buffer is insufficient when
the client target is only 1080p.

## Initial connection on MStar

Configuring the decoder against the GL SurfaceTexture, then moving it with
`setOutputSurface()`, retained buffers that required Client composition on the
MiTV-MSSP1. Despite a full-sized 994x2160 crop, those buffers were composited into
the 1080p client target. A new decoder configured directly against SurfaceView
used Device composition. This explains why a Photos/rotation round trip could
improve the Home screen.

Direct mode now retains the first keyframe while waiting up to five seconds for
SurfaceView creation. The native frame thread releases the renderer monitor
while waiting, so the UI can attach its surface. Stop, resolution changes and
server shutdown cancel obsolete starts; shutdown disables further frame starts
before joining native threads. If no view appears in time (for example while
remaining in the background), the existing GL path is used and a warning is
logged. The timeout path does not guarantee hardware composition.

## Known limits before an upstream release

- Some vendor decoders cannot switch between SurfaceTexture and SurfaceView
  usage. Initial direct configuration retries the original GL hardware path;
  failed switching from an existing direct surface may require reconnecting the
  sender for a fresh keyframe.
- Preview/fullscreen/tab/PiP UI branches now use a single attached SurfaceView.
  Its viewport follows the video slot; changing a tab retains the previous
  viewport under the opaque UI instead of destroying the native surface.
- Android can still destroy the native surface when the whole activity/window
  becomes hidden or is recreated. A fully static sender may then require a new
  frame on return. Retaining a UI view does not promise retention across OS
  window destruction; background/resume and activity recreation remain explicit
  validation cases before claiming equivalence with the GL texture path.
- Intermittent green/block-corrupted HEVC frames were observed in earlier builds
  before the final input-retention fix, with both direct and GL output. Full
  buffer dimensions and Device composition alone did not detect the corruption.
  The final sender trial is recorded below; it does not establish the cause of
  every earlier failure or prove long-term absence of vendor decoder errors.
- The mode is experimental, opt-in, and has not been validated across devices.

Baseline on Xiaomi MiTV-MSSP1 / Android 9 / PI.5925: HEVC stream 994x2160
(portrait) reached a 497x1080 RGBx GL buffer with Client composition. Panel
properties report 3840x2160 while Android's display and client target report
1920x1080. Landscape stream was 3840x2160. These are baseline observations, not
results of the direct-output trial.

## Related implementation

[PR #60](https://github.com/jqssun/android-airplay-server/pull/60), revision
`d6d75e7`, already proposes direct output and a retained full-window view for
HiMedia H10 / Android 7. The retained-view approach here is informed by that
work. This trial targets current upstream v0.0.36 and exposes an opt-in runtime
preference instead of H10-only build types. Its retained view follows the
actual aspect-correct preview/fullscreen slot, while the other UI stays opaque.
The startup wait and EGL-detach handshake address the measured Xiaomi first-
connection path; the comparison is not a claim that it outperforms the H10
implementation on other hardware or fixes its audio synchronization issues.

[PR #74](https://github.com/jqssun/android-airplay-server/pull/74), revision
`c2a3a98`, changes the same frame and geometry code for macOS screen extension,
including a direct ByteBuffer JNI path, advertised dimensions and GL aspect
fitting. It retains the GL output path and bounded compressed-input retries.
This trial instead targets display output and cancellation-aware input
retention; it does not replace those screen-extension changes. The overlapping
renderer and JNI changes will need reconciliation if both contributions land.

## Native view continuity regression (Android TV)

Build `assembleDebug assembleDebugAndroidTest` and install the debug receiver
and its test APK on an idle test TV. Stop competing receivers. Enable
`direct_surface` and `idle_preview` with the existing SettingsReceiver. Wake
and unlock the display first: an active screensaver owns focus and can hide
SurfaceView even though the activity's decor reports that it is shown.

```sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell am instrument -w io.github.jqssun.airplay.test/io.github.jqssun.airplay.ui.SurfaceContinuityInstrumentation
```

Use the actual test package for separately packaged builds. The test starts
Overview, binds the real service, and uses the TV remote navigation from the
initial Stop focus: Down to Settings, Left to Logs, then Left back to Overview.
It verifies each selected tab through the real Compose semantics tree, then
checks the renderer's actual Surface object, validity and SurfaceHolder lifecycle
callbacks, without adding production test hooks or mocking a codec. It requires no sender. With direct
mode enabled, the old UI fails because Logs destroys the surface; the retained
view must remain the same valid object, without any SurfaceHolder destruction
or creation after the initial attachment. This catches native replacement even
when Android reuses its Java Surface wrapper. Passing `-e skip_navigation true`
intentionally leaves Overview selected and must fail the Logs assertion.
With direct mode disabled, the test only
checks that the normal preview is restored. This test does not prove retained
pixels, hardware composition, codec switching or physical-panel resolution;
those need a real stream and the separate dump/visual checks above.

## Compressed-input backpressure

Severe block corruption was also reproduced with `direct_surface=false` after
browsing and rotating the sender. The log recorded a compressed-frame discard
278 ms after starting the MStar HEVC codec. A later real iPhone connection also
discarded input after the first decoded output, despite a larger startup budget.
The source may depend on every compressed reference frame until a later recovery
point. A temporary full input queue must therefore preserve the pending frame.
These observations do not prove that every earlier green frame had the same cause.

`awaitCodecInput` continues draining decoded output and polling input until a slot
becomes available. Between polls it releases the renderer ownership monitor, so
Stop, resolution/session changes and codec replacement can cancel obsolete input.
The MediaCodec calls themselves retain their existing 20 ms input wait. A hardware
stall can increase latency; there is no arbitrary input-discard deadline. Users
can stop the receiver. Both local server shutdown and sender teardown cancel
pending input before native thread joins. The UxPlay patch adds a synchronous
`mirror_video_stopping` callback before the join; the existing final
`mirror_video_running(false)` callback releases the codec after the frame thread
exits. Java cancellation rejects remaining old-session frames until a new session
starts. This does not add network-loss recovery or prevent vendor codec errors.

The portable `testDebugUnitTest` suite covers extended input unavailability,
slot zero, draining output to make input available, and cancellation by a thread
that acquires the actual monitor. The old bounded policy fails the retained-input
case; the cancellation-aware policy passes all four tests.

Run `python3 tools/test_mirror_stop.py` for the native teardown regression. It
applies the distributed patch to the pinned UxPlay files, compiles the actual
stop function with a minimal pthread harness, and requires a blocked frame
callback to exit before join returns. Without the pre-join signal it times out.
This tests ordering in the native function; it does not simulate JNI or a codec.

The physical-device startup check uses a synthetic 30-frame 3840x1768 HEVC
fixture generated by FFmpeg/libx265. Build and install the debug application and
its instrumentation APK, with competing receivers stopped:

```sh
python3 tools/generate_warmup_fixture.py
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest
adb shell am instrument -w -e test_case codec_warmup io.github.jqssun.airplay.test/io.github.jqssun.airplay.ui.SurfaceContinuityInstrumentation
```

This invokes the real VideoRenderer and MediaCodec in GL mode, submits a burst
of access units, requires actual decoded output, and rejects discarded inputs.
Each renderer is released before the next cold start. The generated binary is
ignored by Git and contains no phone footage. Use the actual test package when
building with a separate applicationId. Startup coverage alone does not establish
visual absence of all HEVC corruption; exercise a real sender and rotation too.

PR #60 revision `d6d75e7` still uses an input-enqueued flag to select a bounded
retry budget. This addresses a shared baseline input-loss case, separately from
its H10 view-retention and audio synchronization work.

## Device trial results

On Xiaomi MiTV-MSSP1 / Android 9, the final input-retention build produced real
output on three synthetic HEVC cold starts. The surface continuity check also
passed through Overview -> Logs -> Overview. A real iPhone stream reached a
1008x2160 hardware-composed buffer for 994x2160 portrait input and a 3840x1776
buffer for 3840x1768 landscape input; captured images looked normal in both
orientations.

The user then reported three connections from the iPhone and three from a
MacBook, with good image quality throughout the requested browsing, rotation
and reconnection trial. The receiver process remained alive, and the retained
tail of the device log records completed sender teardown without a codec error.
That log does not cover all six connections; the six-connection visual result
is the user's observation. No long-term or cross-device stability claim is made.
End-to-end latency was not measured or optimized in this trial.
