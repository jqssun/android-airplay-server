# AirPlay Receiver for Android

[![Stars](https://img.shields.io/github/stars/jqssun/android-airplay-server)](https://github.com/jqssun/android-airplay-server)
[![GitHub](https://img.shields.io/github/downloads/jqssun/android-airplay-server/total?label=GitHub&logo=GitHub)](https://github.com/jqssun/android-airplay-server/releases)
[![license](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/jqssun/android-airplay-server/blob/main/LICENSE)
[![build](https://img.shields.io/github/actions/workflow/status/jqssun/android-airplay-server/apk.yml?label=build)](https://github.com/jqssun/android-airplay-server/actions/workflows/apk.yml)
[![release](https://img.shields.io/github/v/release/jqssun/android-airplay-server)](https://github.com/jqssun/android-airplay-server/releases)

A fully featured free and open-source implementation of AirPlay for Android that turns your device into an AirPlay-compatible display and speaker, based on [UxPlay](https://github.com/FDH2/UxPlay). It is the first open-source AirPlay 2 receiver for Android and Android TV, and works with iOS/iPadOS, macOS devices as well as other sender implementations.

[<img height="48" alt="Get it on Google Play" src="https://jqssun.github.io/images/badges/google-play-store.svg">](https://play.google.com/store/apps/details?id=io.github.jqssun.airplay)
[<img height="48" alt="Get it on F-Droid" src="https://jqssun.github.io/images/badges/fdroid.svg">](https://f-droid.org/packages/io.github.jqssun.airplay)
[<img height="48" alt="Get it on GitHub" src="https://jqssun.github.io/images/badges/github.svg">](https://github.com/jqssun/android-airplay-server/releases/latest)

<video loop src='https://github.com/user-attachments/assets/79ed7c0c-0102-43cc-8816-4f00ce6a4199' alt="demo" width="200" style="display: block; margin: auto;"></video>

## Compatibility

- Android 7.0+, including Android TV
- AirPlay devices on the same subnet, including iOS/iPadOS, macOS devices, or other sender implementations

## Features

- Screen mirroring with H.264 and H.265 (HEVC) video decoding
- Audio streaming with AAC-ELD, AAC-LC and ALAC audio decoding
- Music streaming with track information, cover art, and playback controls
- Support for Picture-in-Picture, automatic resolution and mode switching
- Optional PIN authentication
- Video resolution, overscan, and frame rate control
- Audio latency control and support for software decoder fallback
- Debug overlay with real-time statistics (FPS, bitrate, codec, resolution, frame count, audio volume, etc.)
- Android native media session integration with notification controls

> [!WARNING]
> DRM content (e.g. from the Apple TV application) is not supported.

## Implementation

This application uses the C-based [UxPlay](https://github.com/FDH2/UxPlay) library to implement the AirPlay/RAOP protocol, with a JNI bridge to the Android application layer. Audio can be decoded via MediaCodec (AAC) or the Apple ALAC decoder (software fallback), while video is always decoded via MediaCodec and rendered to a SurfaceView.

```mermaid
flowchart LR
    AppleDevice["Apple Device (Sender)"]
    UxPlay["UxPlay (C/JNI)<br/>RAOP + mDNS<br/>FairPlay"]
    AndroidApp["Android (Receiver)<br/>MediaCodec + AudioTrack"]

    AppleDevice -- "RAOP" --> UxPlay
    UxPlay --> AndroidApp
```

CMake is used for native C/C++ components under [`app/src/main/cpp`](app/src/main/cpp). Submodules must be initialized before building. 

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

Check out the [CI](https://github.com/jqssun/android-airplay-server/blob/main/.github/workflows/apk.yml) for more details on reproducible builds.

## Credits

- [UxPlay](https://github.com/FDH2/UxPlay) for the AirPlay/RAOP server implementation
- [ALAC](https://github.com/macosforge/alac) for the lossless audio decoder


---

Disclaimer: This project is not affiliated with Apple Inc.
