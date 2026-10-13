# API

All public endpoints are served over the hub's HTTPS origin. JSON request/response bodies are used unless noted.

## Accounts and sessions

- `POST /api/auth/login`: `{ "username", "password" }`; returns a 15-minute access token and 30-day refresh token.
- `POST /api/auth/refresh`: `{ "refresh_token" }`; rotates the refresh token and invalidates the old one.
- `POST /api/auth/logout`: requires `Authorization: Bearer <access_token>`.

Tokens are opaque random values. Only SHA-256 digests are stored in SQLite. Passwords use Argon2id.

## Cameras

- `POST /api/pairing/start`: administrator only; body `{ "name" }`; returns a one-use code valid for 10 minutes.
- `POST /api/pairing/qr`: administrator only; body `{ "hub_url", "code" }`; returns an uncached SVG QR containing that administrator's active one-use pairing code and HTTPS hub origin.
- `POST /api/pairing/exchange`: public; body `{ "code", "name" }`; returns the camera ID and the device token once.
- `GET /api/cameras`: authenticated; lists cameras owned by the account with `id`, `name`, `path`, and `status` (`online`, `offline`, or `unknown`). Status is based on active RTSP publisher sessions in MediaMTX; it is `unknown` if the internal MediaMTX API cannot be reached.
- `POST /api/cameras/{camera_id}/ring`: paired camera credentials (`Authorization: Basic <base64(camera_id:device_token)>`) send a visitor doorbell request. Duplicate rings from the same camera within 20 seconds return the existing request.
- `GET /api/rings?since={unix_timestamp}`: signed-in viewers receive recent ring requests for cameras they own. Requests remain visible for up to one minute; this endpoint does not answer or end a call.
- `POST /api/devices/{camera_id}/revoke`: administrator only; prevents further publishing with the device token.
- `GET /api/recordings?camera_id=<id>`: lists indexed segments belonging to the signed-in account.
- `PATCH /api/cameras/{camera_id}/retention`: set recording retention `{ "retention_days": 1..365 }` for an owned camera.
- `PATCH /api/cameras/{camera_id}/motion`: enable or disable motion detection for an owned camera with `{ "enabled": true|false }`. `GET /api/cameras` includes `motion_enabled`.
- `DELETE /api/recordings/{recording_id}`: permanently delete an owned recording; linked motion events remain without that playback clip.
- `DELETE /api/cameras/{camera_id}`: remove an owned camera, revoke its publisher, disconnect its sessions, and delete its recordings and motion snapshots.
- `GET /api/recordings/{recording_id}/stream`: authenticated MP4 segment playback with byte-range support.
- `GET /api/events?camera_id=<id>`: lists motion events and linked recording IDs for that account.
- `DELETE /api/events/{event_id}`: permanently delete an owned motion event and its snapshot.
- `DELETE /api/events`: permanently delete selected owned motion events and snapshots; body `{ "ids": ["event-id", ...] }`, up to 500 IDs.
- `GET /api/events/{event_id}/thumbnail`: authenticated JPEG snapshot for an owned event.
- `DELETE /api/recordings`: permanently delete selected owned recording files and their indexes; body `{ "ids": ["recording-id", ...] }`, up to 500 IDs.

Camera publishers use their device token as the MediaMTX password and camera ID as username on `cam/{camera_id}`. Viewers use HTTP Basic credentials `viewer:<access token>` when requesting WebRTC.

## Internal API

`POST /internal/mediamtx/auth` is for the private Compose network only. It checks publish tokens against active devices and viewer tokens against active sessions and camera ownership. Do not expose this route directly to the LAN.

`POST /internal/events` accepts motion events only from the motion worker and requires the `X-Worker-Token` header. It is not routed by Caddy and must remain private to the Compose network.

