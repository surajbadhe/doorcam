# Security

## M2 controls

- Hub passwords are hashed with Argon2id.
- Session and device tokens are random opaque values; only SHA-256 digests are stored in the hub database.
- Access sessions expire after 15 minutes. Refresh sessions expire after 30 days and rotate on use.
- Pairing codes are single-use and expire after 10 minutes. Login and pairing exchange have per-IP rate limits.
- MediaMTX delegates publish/read checks to the API. Publishers are limited to their own `cam/{camera_id}` path. Viewer sessions must own the camera.
- Device revocation blocks future publishing and asks MediaMTX to kick current RTSP and WebRTC sessions. The Control API is not published to the host and is excluded from the auth callback only for its `api` action; it remains on the private Compose network.
- Android camera configuration, including the device token, is encrypted with an Android Keystore AES-GCM key.
- Caddy serves the browser/API with its internal HTTPS certificate. Install and trust its root certificate on each client.

## Known limitation

Android publishing uses RTSP over plain TCP on the local network in this milestone. The device token is therefore visible to an observer on that network. Do not expose TCP 8554 or use this setup on an untrusted network. RTSP-over-TLS or another protected ingest transport is a hardening task before public release.

The hub currently uses SQLite directly with a small startup schema upgrade for session-token typing; it does not yet use SQLAlchemy/Alembic. Additional audit logging and multi-user camera sharing are not implemented.

