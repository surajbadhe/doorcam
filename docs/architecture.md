# Architecture

DoorCam uses an Android phone as an authenticated camera publisher, a self-hosted PC hub, and browser clients on the local network.

- Caddy terminates HTTPS for the viewer and public API.
- FastAPI stores local accounts, sessions, pairing codes, paired cameras, and device-token hashes in a persistent SQLite volume.
- MediaMTX receives RTSP on TCP 8554, serves WebRTC to viewers, and delegates publish/read authorization to FastAPI.
- The Android app stores its pairing token encrypted with Android Keystore, publishes H.264/Opus from a foreground service, and receives viewer talk audio over an authenticated WHEP lane.
- The paired camera can submit authenticated doorbell requests. Signed-in viewers poll only requests for cameras they own; the first stage displays an in-page alert and can open that camera's live view.
- The browser viewer logs in and uses WHEP to display live video. Hold-to-talk publishes microphone audio over a separate authenticated WHIP lane; talk audio is excluded from recordings.
- MediaMTX writes finalized one-minute fMP4 segments to a persistent Docker volume. A worker indexes segments and enforces age and low-disk retention. A second worker reads active camera streams over the private Compose network, detects frame changes, and posts thumbnails/events to the API using a shared internal worker secret.
- Recording playback, motion events, and thumbnails are served only through owner-authenticated API endpoints; the raw recording volume is not published by Caddy.

The MediaMTX Control API is bound inside the Compose network and is not host-published. Device revocation uses it to disconnect active sessions. For M1/M2 local development, simulator streams can replace the Android camera.

The current camera publisher uses plain RTSP over the trusted LAN. See [security.md](security.md) and [milestone notes](milestones/) for current scope and future hardening.

