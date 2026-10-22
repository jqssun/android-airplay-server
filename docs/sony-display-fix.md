# Sony direct-video output and mirroring latency experiment

Based on upstream v0.0.36 (`a46cca0`). Changes are on `fix/sony-native-resolution`.

## Current status (sony.3, 2026-10-22)

The tested Sony configuration uses opt-in `direct_video_output=true` and
`mirror_fill_landscape=true`, with 30 fps and an 80 ms low-latency audio target.
MediaCodec sends the decoded high-resolution video directly to a HWC video layer;
the UI framebuffer remains 1920x1080. The user confirmed both removal of the large
Xiaohongshu landscape bars and normal full-screen TV-native Douyin playback after
leaving mirroring. See the final validation section for measured layer geometry.

The earlier sony.1/sony.2 forced-4K RGB/GL fullscreen path caused a vendor display
state regression and is withdrawn on this Sony. That experimental GL sizing code
remains in this development branch for comparison; direct output is opt-in and
not yet exposed in the normal settings UI. Do not treat the default configuration
or this whole experimental branch as ready for a general upstream release.

The notes below retain the development history and distinguish withdrawn paths
from the final direct-output test. Native audio/protocol code is unchanged;
30 fps and 80 ms were device preferences, not new global defaults. Remaining work
for general release includes safe gating of experimental GL sizing, a normal
settings entry for direct output, build-variant cleanup, and validation on other vendors.

## Reproduced on 2026-10-21

Sony BRAVIA 4K VH2, Android 12. Physical mode 3840x2160, application UI 1920x1080.
During iPhone Control Centre screen mirroring, Douyin switches between 994x2160
and 3840x2160. These are the receiver's reported session/decoder dimensions;
the brief resolution label does not describe the final display buffer.

SurfaceFlinger captured a 497x1080 video buffer displayed over a 994x2160 panel
rectangle, and later a 1920x1080 buffer displayed over the entire 3840x2160 panel.
The EGL pipeline was therefore downsampling and the compositor upsampling again.
One baseline debug sample was HEVC, 60 incoming fps, 8.6 Mbps, zero input drops;
the audio queue was 182 ms. This sample does not establish end-to-end video latency.

## Initial sony.1 changes (historical; GL sizing withdrawn on this Sony)

- Allocate SurfaceView buffers in the physical pixels occupied by the view, rather
  than the UI pixels. Fullscreen is native 4K, the observed portrait rectangle is
  994x2160, and a smaller preview consumes proportionally less memory. Normal
  1080p devices remain 1080p. Refresh the EGL viewport when its window changes size.
- Drain decoded pictures independently of the next network callback, including the
  final picture before a sender becomes idle. When multiple decoded pictures are
  already available and old ones lag the latest queued timestamp by over 150 ms,
  skip the old outputs. Encoded reference pictures are never discarded by this
  catch-up policy. Always render the newest decoded picture, including on a slow
  decoder, to avoid indefinite starvation.
- Deliver SurfaceTexture frame-available notifications on a dedicated video
  HandlerThread. The GL thread has no Looper; using the listener overload without
  a Handler previously allowed callbacks to fall back to the main/UI Looper.
  Blocking UI work must not stall video while audio continues independently.
- Add decoded-queue age and stale-output counters to the optional debug overlay.
  These measure decoder backlog, not absolute audio/video or network latency.

The audio engine, protocol implementation, codec choices and timing preference
defaults are unchanged. A/V synchronisation must still be judged with an actual
phone and a known synchronisation clip; the changes do not promise 4K source detail
or zero latency for every sender/app.

## Build

The default native source build remains available on a Unix build host. On Windows,
the untouched protocol/audio native libraries can be taken from the official same-
version release, which the build verifies against its published SHA-256. The full
native source and submodules are present in this checkout.

With a suitable JDK and Android SDK configured in `local.properties`:

```powershell
.\tools\build-sony.ps1
```

The script downloads the verified upstream APK into ignored `.local/` if needed.
Or supply `-NativeApk` with an existing official APK. It rebuilds the Kotlin code,
runs unit tests and lint, and builds the instrumentation APK.

`app/build/outputs/apk/sonyRelease/app-sonyRelease.apk` is a minified, locally signed
build with package `io.github.jqssun.airplay.sonytest` and launcher label
`AirPlay Sony`. It coexists with the official installation and does not replace
the official signing identity. Do not run both receiver services on port 7000.
`sonyTest` is the unminified instrumentation variant of the same main sources;
it also includes the isolated test activity. It uses the same local signing key
and package, so a release build can replace it without removing settings.

The committed lint baseline was generated separately from untouched v0.0.36
application sources, using the same build plugin. It records 29 pre-existing
errors and the upstream warnings; new issues remain checked rather than globally
disabling lint. This is not a claim that the upstream code has no lint issues.

## Validation

Unit tests cover native panel sizing, the observed portrait shape, previews,
1080p displays, invalid layouts, rotations, stale output and timestamp resets.
Instrumentation uses an isolated test-only activity that does not start AirPlay.
It verifies real Surface dimensions across fullscreen/portrait/fullscreen and
uses PixelCopy to check that the EGL image stays complete after a resize.
It also verifies final-frame delivery without another network packet, and video
delivery while the UI thread is deliberately blocked.

Use the instrumentation APK on a dedicated device/emulator, explicitly choosing
its serial rather than invoking a test task against all connected devices:

```powershell
adb -s <serial> install -r app/build/outputs/apk/sonyTest/app-sonyTest.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/sonyTest/app-sonyTest-androidTest.apk
adb -s <serial> shell am instrument -w io.github.jqssun.airplay.sonytest.test/androidx.test.runner.AndroidJUnitRunner
```

For the Sony phone test, record both session dimensions and the SurfaceFlinger
buffer/source-crop/display-frame dimensions. A label saying 4K is not acceptance.
Compare normal playback, repeated portrait/video transitions, A/V drift and
decoder recovery. Emulator checks cannot establish Sony performance or iPhone
compatibility. Record results separately from the intended behaviour above.

### Results (2026-10-21, Asia/Shanghai)

- Build and all 10 unit tests passed.
- API 31 emulator, physical 3840x2160 with `wm size 1920x1080`: all five
  instrumentation tests passed, including native-library loading, blocked-UI
  delivery and the final-frame case.
- Actual Sony BRAVIA: all three display/pipeline tests passed (native fullscreen,
  portrait/fullscreen transitions, resized-image PixelCopy, and blocked-UI delivery).
  The single-frame software-encoder test was intentionally limited to the emulator.
- Release and test lint completed with no new errors, using the untouched upstream
  baseline. Twelve remaining warnings were reported; this is not an all-clean
  upstream lint result.
- The minified Sony build was installed separately on the TV. Receiver name is
  `Sony-AirPlay-Test`, control port 7200, requested size 3840x2160; boot autostart
  is disabled for this comparison installation. The official receiver remains on
  its original port and the TV was returned to its original activity.
- Full receiver startup on the Sony was verified: foreground service running and
  both `_airplay._tcp` and `_raop._tcp` registered under `Sony-AirPlay-Test`.
  Native audio dependency `liboboe.so` is included in the Windows packaging path.
- Actual iPhone playback clarity, sustained 4K/60 performance and A/V drift on
  the modified receiver still require reconnecting the phone and comparing clips.
  Do not report those measurements as passed based on synthetic display tests.

### Live follow-up and sony.2 (2026-10-21)

The phone test confirmed that the sony.1 fullscreen 16:9 mirror used a 3840x2160
video buffer, source crop and panel display frame. At a requested maximum 60 fps,
sampled decoder queue ages reached 566-1483 ms. Setting the maximum to 30 fps kept
the requested 4K dimensions and substantially reduced queue age; the user reported
an obvious improvement in video delay.

Audio was separately changed from adaptive buffering / media output to an 80 ms
fixed cushion and low-latency output. Native logs confirmed AAudio MMAP, a 384-frame
buffer instead of 4430 frames at 44100 Hz. Sampled application audio queue ages were
19-102 ms. The user reported markedly improved old-audio carryover when switching.
These are component queue measurements, not end-to-end A/V latency measurements.
The native RAOP audio_flush callback is currently log-only. No audio_flush event
was observed in the inspected switching interval, so its missing implementation
has not been established as the cause of that user's symptom.

For a user-reported 1080p Xiaohongshu video, sony.1 received HEVC 3840x1768 at
about 31 fps and 18.9 Mbps. The video buffer/source crop was 3840x1768, but the
compositor display frame was [0,98,1920,982]. The framebuffer remained allocated
3840x2160, while its content rectangle and display projection were 1920x1080
with an identity transform. This differs from the 2x projection/full 4K content
rectangle seen with the 16:9 video. Vendor scaling after composition is not fully
described by this dump; the dump alone does not prove final panel pixel detail.

sony.2 keeps the fullscreen mirroring SurfaceView at the full physical panel size
and fits portrait/wide pictures inside it in GL, with black bars and no cropping
or stretching. Preview sizing and the separate URL-video player are unchanged.
This tests whether a panel-sized layer avoids the observed composition path.
Validate the actual Sony display frame/projection after installation, in addition
to image-aspect tests. A 4K mirror of 1080p source material is still an upscale;
improved resolution handling cannot recover source detail or undo sender encoding.

sony.2 validation: all 14 unit tests passed; all six instrumentation tests passed
on both the API 31 emulator and the actual Sony, including the new wide/portrait
image-and-black-bars PixelCopy check. Release lint reported zero new errors and
12 warnings with the upstream baseline. The minified sony.2 build was installed
on the Sony and receiver startup/mDNS registration was verified on port 7200.
The 30 fps cap, 80 ms fixed audio cushion and low-latency output preferences were
preserved.

After the user reconnected and resumed the same Xiaohongshu video, the input was
still 3840x1768. Logs confirmed a 3840x2160 fullscreen SurfaceView buffer. The
compositor framebuffer/display content rectangles were now 3840x2160 and the
SurfaceView source crop/display frame were both [0,0,3840,2160], instead of the
previous 1920x1080 composition content and [0,98,1920,982] video display frame.
The source image is fitted inside that canvas with black bars. Video queue age
was 33 ms at 30-31 incoming fps, with zero input drops. This verifies the intended
composition-path change on the actual phone stream, not a measured increase in
source-video detail. User subjective clarity comparison is still pending.

An audio debug sample briefly showed 2298 ms queued, then the immediately following
screenshot showed 69 ms and an increased trim count. The fixed 80 ms cushion is a
target, not a hard upper bound; this native timeline currently waits for sustained
excess backlog before trimming. Residual switching spikes remain possible and must
not be reported as eliminated by the previously improved audio settings.
Three subsequent samples were 84 ms, 2298 ms and 73 ms, while the video queue
remained 33 ms. The second full-ring spike confirms this was not limited to the
first connection sample. Diagnostics were disabled again after sampling.

### Withdrawn sony.2 display path and sony.3 candidate (2026-10-22)

The user reported four-sided letterboxing and a shrunken TV-native Douyin picture
after leaving mirroring. "Douyin" in this report meant the separate TV application,
not merely switching applications on the iPhone. The receiver was also observed
automatically entering PiP when the user intended to leave casting. Its small
469x216 window retained a 3840x2160 RGB video buffer. Display.getSize() can reflect
the Activity's small window, making it unsuitable as the fullscreen scale reference.

After the receiver was stopped, a later dump still reported a 3840x2160 composition
render surface while the actual framebuffer queue buffers were 1920x1080. Screenshots
could look normal while the user reported abnormal physical output, so screenshot
checks alone were insufficient. A brief WM size refresh (restored to the original
1920x1080 override) and restarting TV Douyin did not reliably restore the desktop.
The TV was rebooted; afterward composition render size, framebuffer-space bounds,
buffer default size and actual buffers were consistently 1920x1080, and the user
confirmed normal desktop geometry. sony.1/sony.2's native-4K RGB/GL path is withdrawn
on this Sony pending a reliable exit/transition fix. Do not call it successful based
on the earlier screenshot/PixelCopy tests.

The sony.3 candidate adds:

- A separately enabled `direct_video_output` path: MediaCodec displays decoded YUV
  pictures directly on the SurfaceView, avoiding a full-size 4K RGB GL canvas. The
  persistent SurfaceTexture is used to park the decoder while a display is absent;
  setOutputSurface is tested for this exact device. If direct output is rejected,
  the compatible GL path uses the ordinary UI-size surface rather than forcing a
  4K RGB framebuffer. Check the actual HWC layer type and buffer/crop/display sizes.
- `mirror_fill_landscape`: optional aspect-preserving landscape fill/cropping,
  with a visible framing button. Portrait remains fitted rather than cropped.
  Fill may remove real content at the edges; it is not automatic bar detection.
- TV BACK from mirror fullscreen stops the receiver, and leaving the TV activity
  releases playback unless the user explicitly selected PiP. TV auto-PiP is disabled.
- Native display sizing uses maximum logical window metrics, not current PiP size.
  Preview/PiP use UI-size buffers. GL fallback receives the SurfaceHolder's real
  buffer dimensions explicitly instead of relying solely on potentially stale EGL
  size queries; framing changes repaint even a paused GL picture.

New tests cover repeated wide/portrait/fullscreen transitions, restoring all four
corners after resizing, phone-side pillarboxing removal, paused-picture framing
changes, preview buffer sizing and direct decoder parking/reattachment. Full 4K GL
image tests run on the emulator only after the physical display regression was
identified. Sony tests are restricted to the new direct-output path until physical
output/exit behaviour is established. Phone reconnection and a real return to the
TV launcher/TV Douyin remain required before marking this candidate validated.

### sony.3 validation on 2026-10-22

The final minified release was installed on the Sony, with direct output and
landscape fill enabled, 3840x2160 advertised resolution, 30 fps maximum and the
previous 80 ms fixed/low-latency audio settings. All 17 unit tests and all 10
emulator instrumentation tests passed. The Sony-specific direct decoder
parking/reattachment test passed on the physical TV. Release lint had zero new
errors and the 12 existing warnings under the upstream baseline.

During the real iPhone reconnection, the HEVC mirror changed from 994x2160 to
3840x1768. The user confirmed that the large top/bottom bars disappeared.
SurfaceFlinger reported a DEVICE-composed video layer, physical display frame
[0,0,3840,2160] and centered source crop [348,0,3491,1768], while the UI framebuffer,
render surface and queue default size consistently stayed at 1920x1080. This
preserves the decoded high-resolution hardware video layer without reintroducing
the problematic 4K RGB UI composition path. It does not prove the original app
video contains native 4K detail.

A sampled video queue age was 0 ms and audio queue age about 58 ms (80 ms target),
with zero input drops. These are component samples, not measured end-to-end latency.
The prior native audio backlog caveat still applies. Diagnostics were disabled
after sampling. MENU and the framing button successfully toggled fitted/fill
layout on the live phone stream, then fill was restored.

BACK stopped the receiver and disconnected the sender. Returning to Home kept all
framebuffer/render/default sizes at 1920x1080. TV-native Douyin initially buffered;
after switching to another video, actual playback was verified in a full-screen
DEVICE layer: UI bounds [0,0,1920,1080], physical display frame [0,0,3840,2160], source
crop [0,0,1920,1080]. The screenshot sony3-after-cast-douyintv-next.png records this
playback, rather than merely the loading interface. Physical user confirmation of
this final TV-native playback cycle is requested separately because screenshots
alone previously failed to reveal the vendor display-state regression.

This validates one real mirroring/exit/native-player cycle. It is not a guarantee
for every app, source aspect ratio or prolonged switching session. sony.1/sony.2
remain withdrawn on this Sony. The new direct path and shutdown behavior must be
retained for subsequent device testing.

The user subsequently confirmed that TV-native Douyin physically fills the screen
normally and is not reduced to the top-left. The real mirroring/exit/native-player
cycle therefore has both compositor evidence and physical user confirmation.

### Simplified Chinese resources (sony.4, 2026-10-22)

Added a complete values-zh-rCN translation of all 166 app-owned string/array
resources, with matching resource names, format placeholders and array lengths.
The two framing labels now have English defaults and Chinese translations. The
Sony variant keeps the AirPlay Sony launcher label in both languages. This follows
the existing system language and does not force a global locale change.

The minified sony.4 release built and passed release lint with zero new errors
and the same 12 warnings. It was installed over sony.3. Physical-TV screenshots
and UI hierarchy confirmed Chinese overview and settings text without clipping;
D-pad DOWN/CENTER entered Settings and LEFT/LEFT/CENTER returned to Overview.
Saved server name Sony-AirPlay, 30 fps limit, direct video output, landscape fill,
80 ms audio cushion and low-latency output remained unchanged. There were no
video/audio implementation changes in this localization update.
