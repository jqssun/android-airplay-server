# Screen extension (extended desktop)

This fork adds a **screen extension** mode to the AirPlay receiver: a MacBook
can use an Android TV as a *second, extended* display instead of a mirror of
the main screen. The TV shows a separate workspace, the Mac keeps its own
desktop, and windows can be dragged onto the TV.

The feature is the macOS **Screen Mirroring → *Use As Separate Display*** path
in Control Center (Ventura 13 and newer).

## How it works

The sender builds the extended desktop from the geometry the receiver
advertises. The receiver announces its display in the `displays` array of the
RTSP `/info` response:

```
displays[0] = {
    uuid           = <per device, derived from the MAC address>
    width          = <panel width in pixels>
    height         = <panel height in pixels>
    widthPixels    = <panel width in pixels>
    heightPixels   = <panel height in pixels>
    widthPhysical  = <panel width in mm, 0 = unknown>
    heightPhysical = <panel height in mm, 0 = unknown>
    refreshRate    = 1 / <max fps>
    maxFPS         = <max fps>
    features       = 14
}
```

Three things decide whether the extended desktop is sharp or blurry:

1. **The advertised geometry has to be the panel's native resolution.** If the
   receiver advertises 1920x1080 on a 4K panel, the sender creates a 1080p
   virtual display and upscales it onto the panel — the result is soft and
   blurry no matter how good the decoder is.
2. **The codec has to be able to handle that resolution.** The advertised size
   is bounded by the limits of the codec the sender is expected to use.
3. **The panel has to be in its native video mode.** A TV that runs its UI at
   1080p reports 1920x1080 as its display size, and the extended desktop
   follows that mode. Set the TV's output to 4K for a 4K extended desktop.

### Which codec is expected

The receiver advertises multi-codec support (dns-sd feature bit 42) whenever
HEVC is enabled, and macOS prefers HEVC once the resolution goes beyond the
H.264 limits. The advertised size is therefore bounded by the **HEVC**
decoder's limits, and only falls back to the H.264 limits when no HEVC
decoder is available. Bounding it by the smaller of the two would cap a
4K-capable TV at 1080p, because many TV sets report a 1080p limit for their
H.264 decoder while the HEVC decoder handles 4K fine.

If the sender still sends an H.264 stream that is too large for the hardware
decoder, the receiver falls back to a software decoder (slow but functional).

## Settings

| Setting | Key | Default | Effect |
| --- | --- | --- | --- |
| Use as separate display | `separate_display` | on | dns-sd feature bit 14; lets the sender offer the extended desktop |
| Fit picture to panel | `fit_aspect` | on | letterboxes the picture instead of stretching it to the panel |
| Physical width (mm) | `display_physical_width_mm` | 0 | physical panel size, the sender scales the extended desktop's UI from it |
| Physical height (mm) | `display_physical_height_mm` | 0 | see above |
| Verbose protocol log | `verbose_protocol_log` | off | logs the sender's SETUP requests and the connection setup |
| Resolution | `resolution` | auto | the advertised geometry, `auto` uses the panel's native size |
| Maximum FPS | `max_fps` | 60 | the refresh rate announced to the sender |

All settings can be changed over ADB (see the README) without a display:

```bash
export KEY="separate_display" VALUE="true"
am broadcast -n io.github.jqssun.airplay/.SettingsReceiver \
  -a io.github.jqssun.airplay.SET_SETTING --es key "$KEY" --es value "$VALUE"
```

## Usage

1. Connect the TV and the Mac (USB tethering preferred, see below).
2. On the Mac: Control Center → Screen Mirroring → *Android AirPlay*.
3. Choose **Use As Separate Display**.
4. The Mac adds a second display; move a window onto it with
   `fn` + arrow keys, or drag it to the edge of the screen.

The TV shows the extended desktop. The receiver's own UI (settings, logs) is
replaced by the video while a session is running.

### Wired connection (USB tethering)

The wired path is the most reliable and has the lowest latency. The TV
provides the Mac with an IP over USB; the receiver is advertised on that
interface by a small mDNS responder of its own.

```bash
# on the TV: make the USB connection show up as a network link (CDC-NCM)
adb shell settings put global tether_force_usb_functions 1
```

Some devices default to RNDIS, which Apple's devices do not support for
tethering; `tether_force_usb_functions 1` switches to CDC-NCM. The receiver
also supports advertising on USB only:

```bash
export KEY="usb_only" VALUE="true"
```

### Wi-Fi

Works as well, but the latency depends on the network. For a 4K extended
desktop a 5 GHz or 6 GHz link is recommended.

## Diagnostics

The status line on the main screen shows the advertised geometry
(`Display 3840x2160@60`) and the number of active display sessions. A second
session appears when the sender runs a mirror and an extended display at the
same time. Only one of them can be shown on the panel: the session that
started last gets it, and the frames of the other session are dropped instead
of corrupting the picture. When the shown session goes quiet, the other one
takes the panel over.

With **Verbose protocol log** enabled, the app logs the sender's SETUP
requests (as XML) and the connection setup. The interesting part is the
`/info` response, which contains the `displays` array the extended desktop is
built from.

## Known limitations

- **FairPlay DRM** is not supported, so protected content is not shown.
- The panel's **video mode** decides the resolution of the extended desktop.
  A TV that is set to 1080p gives a 1080p extended desktop.
- The **physical panel size** is unknown by default (0). Depending on the
  sender this can make the extended desktop's UI smaller or larger than
  expected; set the two millimetre values to the panel's real size if the UI
  looks wrong.
- Bluetooth LE discovery probes from the Mac can end a session; this is
  worked around in the upstream patches.
- Only one of the sender's displays can be shown on the panel at a time.
