# Native audio regression tests

These tests exercise the actual UxPlay RTP receiver and packet buffer, together
with the application's PCM timeline buffer. They run on the host without an
Android SDK. Python 3, Git, Clang, and Clang++ must be available on `PATH`.

Initialize the UxPlay submodule before running them:

```sh
git submodule update --init app/src/main/cpp/third_party/UxPlay
python3 tests/native/run_tests.py --udp
```

The runner extracts the repository's pinned UxPlay revision into a temporary
directory and applies the complete, ordered patch series used by Gradle. It
does not reset or modify the checkout's submodule. UndefinedBehaviorSanitizer is
enabled by default; each subprocess has a 60-second timeout.

Coverage includes:

- Empty RTP payloads and AAC-ELD no-data markers, including duplicates and
  16-bit sequence-number wraparound.
- Recovery after a silent packet without waiting for the 256-packet buffer to
  fill.
- Out-of-order recovery within the resend window, expiration of missing
  packets, rejection of late packets, and immediate recovery without resends.
- FLUSH with and without a target sequence number, including an empty buffer.
- The real receive loop over local UDP sockets: silent restart, expiration
  without another incoming data packet, and recovery after FLUSH.
- PCM flushing that discards old samples while retaining samples decoded after
  the flush boundary, repeated timeline resets, and adaptive-delay reset.

For comparison, the unpatched pinned UxPlay source reproduces a 255-packet wait,
approximately 2.776 seconds at 480 samples per packet and 44.1 kHz:

```sh
python3 tests/native/run_tests.py --baseline
```

This baseline command is expected to exit unsuccessfully at the regression
assertion. It demonstrates the original failure rather than a passing test.

Use `--no-sanitize` to disable host sanitizers, or
`--sanitize address,undefined` to select additional supported sanitizers. Omit
`--udp` when local socket binding is unavailable; the deterministic buffer and
timeline tests still run.

Encryption and sender clock negotiation use test doubles. These tests do not
exercise real AAC/ALAC decoding, Android audio output, or end-to-end AirPlay
playback; those require device testing.
