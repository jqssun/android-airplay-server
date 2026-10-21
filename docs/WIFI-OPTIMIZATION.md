# Wi-Fi and link optimisation

The goal of the tuning in this fork is **quality first**: as few dropped frames as
possible due to a poor bitrate, the best picture the link can carry, and as little
delay as the transport allows. Where those conflict, quality wins over latency.

## First: "frame loss" on this transport is not packet loss

The AirPlay mirroring transport splits like this:

| stream   | transport | what a problem looks like |
|----------|-----------|---------------------------|
| video    | **TCP**   | a retransmission stalls the whole picture (a freeze), not a dropped frame |
| audio    | UDP       | a lost packet is a click or a gap |
| control  | TCP       | delayed commands |
| NTP      | UDP       | clock drift, audio/video offset |

Because video is TCP, the receiver cannot "drop a bad frame" the way an RTP
receiver would. A congested link shows up as head-of-line blocking and
retransmission timeouts: the picture freezes for a moment and then catches up.
Everything below therefore aims at keeping the receive path drained and the
sender's window open, not at error concealment.

## What the app does

### Keeps the receive thread cheap (the single biggest win)

A 4K frame takes 3–8 ms to decode, and `MediaCodec.dequeueInputBuffer` can block
for 20 ms. When that ran on the thread that reads the TCP socket, the socket went
undrained meanwhile, the receive window closed, and the *sender* stalled — a
self-inflicted freeze.

The receive thread now only copies the frame into an app-owned direct buffer and
hands the index to a queue; a dedicated `VideoDecode` thread at
`THREAD_PRIORITY_URGENT_DISPLAY` feeds the decoder and returns the buffer
(`nativeReleaseFrameBuffer`). The ring is 6 × 1 MB, so 4K keyframes fit and the
queue has room to absorb jitter. If the decoder cannot keep up, the **oldest**
frame goes: latency stays bounded instead of growing without end. The counter is
visible in the debug overlay as `Queuedrops`.

### Sizes the sockets for a 4K stream

`SO_RCVBUF`/`SO_SNDBUF` at 4 MB, `IP_PMTUDISC_DO`, `SO_BUSY_POLL` on UDP,
`TCP_NODELAY` on the control connections (patch `0011`). The effective receive
buffer is logged at startup (`raop_rtp socket rcvbuf N bytes`) — compare it with
`net.core.rmem_max`, the kernel clamps to that.

### Raises the media threads above the launcher

The mirror thread, the audio RTP thread and the httpd thread ask to run ahead of
the UI (patch `0012`, best effort), the decode thread and the GL pipeline thread
run at `THREAD_PRIORITY_URGENT_DISPLAY`.

### Drops frame rate before resolution

On a constrained link 3840×2160@30 looks far better than 1920×1080@60. The
**Stream profile** setting (`auto`, `4k30`, `4k60`, `1080p60`, `custom`) picks
resolution, frame rate and codec together, and the built-in profiles force HEVC —
roughly half the bitrate of H.264 at the same quality. The receiver advertises
what it can decode, and the sender honours it.

The **size** is clamped to the decoder's caps, and the **frame rate** is clamped
to what the decoder can sustain *at that size*: a decoder that tops out at 4K30
cannot keep up with a 4K60 stream and drops frames instead of decoding them,
which in an extended desktop is a visible stutter. The receiver logs the clamp:

```
Advertising display 3840x2160@30 (decoder limit 3840x2160, profile auto)
Frame rate clamped to 30fps: the decoder cannot sustain 60fps at 3840x2160
```

The app only *advises* on a link problem, it never renegotiates mid-session: a
resolution change makes the sender restart the stream, which is a worse surprise
than a steady picture. The hint in the overview names the profile to switch to.

### Keeps the radio awake

`WifiTuner` holds a Wi-Fi lock for the lifetime of the server
(`WIFI_MODE_FULL_LOW_LATENCY` on API 29+, the high-perf lock below that), so the
radio does not sleep between beacons. Every beacon interval that the radio sleeps
is a frame that has to wait for the next one — jitter the stream cannot get back —
and the retries that follow eat throughput. It costs battery, so it is a switch:
*Developer → Keep the wifi radio awake* (default on).

### Shows the link, and says when it is the problem

The debug overlay reports band, RSSI, link speed and a quality verdict, plus the
kernel's UDP buffer-overflow counters. It also warns when the system throttles
scans: a scan hops to another channel and every hop costs the stream airtime.

## What the app cannot do, and what you can do instead

`WifiManager.setWifiPowerSaveMode`, `WifiManager.setScanThrottlingEnabled` and
`WifiManager.setTxPowerOverride` are **system APIs**. They are not in the public
`android.jar`, so an app cannot call them — including this one. What is left is
to report the state (the overlay does) and to let the device owner change it:

* **Scan throttling** — *Developer options → Wi-Fi scan throttling* on most
  devices. The overlay line `Scans throttled: …` tells you the current state.
* **Radio power save** — device-specific. Check what your device exposes:
  `adb shell cmd wifi help` lists the `cmd wifi` subcommands on that build.
* **Never let the device sleep** — *Developer options → Stay awake*, or the app's
  own *Keep screen on*.

Rooted devices can read and write these directly, for example:

```bash
adb shell settings list global | grep -i wifi     # see what your build has
adb shell getprop | grep -i wifi
```

A rooted device can also raise the kernel's limit on the socket buffers, which
is the last lever on the receive side:

```bash
adb shell su -c 'sysctl net.core.rmem_max'          # the limit the kernel applies
adb shell su -c 'sysctl -w net.core.rmem_max=4194304'
```

The receiver asks for a 4 MB receive buffer, but the kernel hands out what
`net.core.rmem_max` allows, and the effective size is logged at startup
(`raop_rtp socket rcvbuf N bytes`). When the logged number is far below 4 MB,
this is the reason.

## What the first test session on the tv showed

The first real run of the receiver on a tv (a 1080p Android TV on 5 GHz, the
receiver connected over the same access point) says more than any theory:

| what | measured |
|------|----------|
| the panel | 1920 x 1080 @ 60 Hz, the receiver advertises `1920x1080@60` |
| the decoders | `OMX.MS.AVC.Decoder` and `OMX.MS.HEVC.Decoder`, the codec reports up to 4096 x 2176 |
| the link | RSSI -45 dBm, 5 GHz, 866 Mbps, 0 retransmissions, 0 UDP buffer errors |
| the decode | 3.2 ms mean for a 4K frame (min 1.9, max 16) |
| the audio | oboe's fast path granted: AAudio, mmap, 384 frames (8.7 ms) |
| the extension | `Display session 1 takes over from 0`, `Video size: 1920x1080 -> 1920x1080` — the extended desktop is the panel's own size |
| the mirror | the sender sent **3840x2160 H.264** onto the 1080p panel |

Two things follow from it.

**The extended desktop is exactly the panel's size.** The sender builds it from
the geometry in the setup response, and the receiver hands out the panel's
native resolution, clamped to what the decoder can decode. Nothing is scaled.

**A plain mirror is bigger than the panel.** macOS picks the mirror's size
itself: the receiver's geometry is what the *extended display* is built from,
but a mirror comes in at the size macOS wants. The receiver cannot talk the
sender out of it directly — what it can do is stop *asking* for it: the hevc
capability is the dns-sd feature bit 42, and that bit is what tells the sender
that it may send 4k at all. On a 1080p panel the bit is now left off (see
`h265` above), the receiver advertises its size in the `/info` response as
well, and the picture stays 1080p instead of being a 4k stream squeezed onto a
panel that cannot show it.

## Prefer a wire

USB-C tethering (CDC-NCM) removes the whole radio question: no contention, no
beacon intervals, no neighbours. See the README for forcing CDC-NCM over RNDIS.
Use Wi-Fi when you cannot cable, and then 5 GHz (or 6 GHz) at close range.

## The configuration: best picture, least delay

`tools/optimal-config.sh` writes the whole choice down and puts it on a connected
device:

```bash
tools/optimal-config.sh apply          # the best picture with the least delay
tools/optimal-config.sh apply 4k30     # the same, for a weak link
tools/optimal-config.sh measure        # the same plus the counters that measure it
tools/optimal-config.sh show           # what the device is set to now
```

Every setting is checked against the receiver, which answers with the value it
stored — a setting the receiver refuses (a renamed key, a value out of range) is
reported and the script exits with an error instead of leaving a gap behind
silently.

| setting | value | why |
|---------|-------|-----|
| `stream_profile` | `auto` | the panel's native resolution, clamped to the decoder, frame rate clamped to what it sustains there. `4k30` for a weak link |
| `max_fps` | `60` | the panel's top rate; the clamp above decides what is actually advertised |
| `h265` | `auto` | hevc where it pays off, and only there: above 1080p it halves the bitrate, below that the hevc feature bit would make the sender push a 4k mirror onto a 1080p panel. `on` forces it, `off` forbids it |
| `fit_aspect` | `true` | the picture keeps its shape instead of being stretched to the panel |
| `overscanned` | `false` | use the whole panel |
| `enforce_sdr` | `true` | the sender sends SDR, so the decoder is told to output BT.709 |
| `allow-frame-drop` | `true` | a frame that arrives late is dropped rather than stalling the picture |
| `priority` | `true` | realtime priority for the codec |
| `wifi_low_latency_lock` | `true` | the radio does not sleep between beacons |
| `low_latency` | `true` | oboe's low-latency output and the decoder's low-latency flag; oboe falls back to a normal stream where the tv has no fast path |
| `audio_auto_buffer` | `true` | the cushion follows the arrival delay instead of a fixed value |
| `audio_adaptive_step` | `2` | the 90th percentile: less delay than the 95th percentile default, still stable. `1` or `0` is faster and clicks more |
| `oboe_buffer_frames` | `256` | about 5 ms: enough that a busy tv does not run the output dry, small enough to stay below the eye's notice. `0` lets oboe pick, which in low-latency mode is two bursts — that clicks on a weak tv |
| `separate_display` | `true` | the tv is a separate display, not a mirror |

The settings that only the diagnostics need (`verbose_protocol_log`,
`benchmark_log`, `debug_enabled`, `developer_options`) are **off** in the normal
config and on in the `measure` config: the protocol log is a debug-level log and
costs throughput, which is exactly what the measurements must not have.

If the audio clicks on the tv, `low_latency false` is the first thing to try —
the picture does not depend on it.

## Measuring before and after

`tools/verify-screen-extension.sh` collects what matters:

```bash
tools/verify-screen-extension.sh install   # install and set stream_profile auto
tools/verify-screen-extension.sh link      # band, rssi, link speed, udp errors, stream stats
tools/verify-screen-extension.sh watch     # follow the receiver log
```

The numbers to compare, per profile (`4k60`, `4k30`, `1080p60`) and per link
(USB, 5 GHz, 2.4 GHz):

| metric | where it comes from | before | after |
|--------|---------------------|--------|-------|
| `Queuedrops` | debug overlay / benchmark log | 144 of 4807 frames (4k mirror) | |
| `Drops` | debug overlay / benchmark log | 0 | |
| frame pacing jitter | debug overlay | 32 ms (4k mirror at 30 fps) | |
| `Udp: RcvbufErrors` | `tools/verify-screen-extension.sh link` | 0 | |
| effective `SO_RCVBUF` | receiver log at startup | not in the log yet | |
| freezes seen by eye | watching the picture | none reported | |
| end-to-end delay | clapper board or a stopwatch on camera | not measured yet | |

The numbers above are from one session on a 1080p tv over 5 GHz with the
`auto` profile, where the sender sent a 4k mirror. They are the baseline, not
a result: the same session has to be run again with the mirror at the panel's
size (and over USB) before the two columns can be compared.

Fill the "before" column from a build of the commit before
`a3674e3 perf: decode on its own thread …`, and the "after" column from a current
build, on the same link and the same content.

## Known upstream limits

* FairPlay DRM is not supported: HLS video with FairPlay will not play.
* macOS BLE discovery probes can drop a session; UxPlay patch `0007` keeps the
  session on the BLE discovery query.
