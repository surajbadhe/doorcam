# DoorCam

DoorCam turns an Android phone into a local door camera. The phone streams to a hub on your PC; viewers on the same Wi-Fi use a web app to watch live video. This repository contains the hub, Android camera app, browser viewer, and simulator. It does not use cloud accounts or telemetry. Remote access is not configured; keep it on a trusted LAN unless you set up a VPN.

## Status

The core hub, Android streaming app, and viewer are implemented. Local phone streaming, camera switching, and a screen-locked stream have been confirmed by the project owner. The debug APK builds. The latest Android build uses Android's voice-communication microphone source and routes call audio through the speaker to improve echo cancellation; verify behavior on your device.

The viewer provides sign-in, camera listing with publisher status, live WHEP playback, audio controls, two-way audio calls, reconnect handling, fullscreen, pairing codes and QR display, recordings, motion events, and manual picture rotation. The Android app includes QR pairing, camera quality controls, and a setup preview with framing grid, focus, zoom, and torch controls. End-to-end checks are still needed on a fresh machine and with multiple devices.

Recording and motion features are implemented, including retention, event snapshots, viewer playback, per-camera motion controls, opt-in browser alerts, and individual or bulk deletion. Two-way audio is implemented. Motion notifications currently require the signed-in viewer to stay open. Release packaging, remote access, and full acceptance testing remain future work.

## Repository layout

- `hub/` — Docker Compose hub, API, MediaMTX, and proxy
- `camera-android/` — Android camera app
- `viewer-web/` — browser viewer and pairing UI
- `tools/` — development utilities, including a simulated camera stream
- `docs/` — setup, architecture, security, and milestone notes

## Run DoorCam end to end

Start with the complete [end-to-end setup guide](docs/setup-hub.md). It covers prerequisites, hub setup, certificate trust, running a simulator, building and pairing the Android app, opening the viewer from another device, and troubleshooting.

For focused references, see [Android camera setup](docs/setup-camera.md), [API](docs/api.md), [architecture](docs/architecture.md), and [security notes](docs/security.md).

