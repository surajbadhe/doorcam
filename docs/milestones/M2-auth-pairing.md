# M2: Authentication and pairing

## Built

- Local administrator creation CLI, Argon2id password hashing, rate-limited login, short-lived access tokens, and rotating refresh tokens.
- One-use 10-minute camera pairing codes; device tokens are returned once and only token hashes are stored.
- SQLite users, sessions, pairing-code, device, camera, and attempt tables. Startup upgrades older databases with the session-token type field.
- MediaMTX HTTP authorization for publisher path ownership and viewer session ownership.
- Device revoke endpoint asks MediaMTX to disconnect active RTSP and WebRTC sessions.
- Mobile browser sign-in and admin pairing-code form.
- Pair-aware simulator scripts and setup instructions.

## Verification

- Five automated API tests cover access/refresh type separation and rotation, one-use/expired pairing, pairing rate limiting, publisher path scoping, viewer ownership, invalid tokens, and active-session kick requests.
- A live API integration run also exercised login, refresh, pairing, callback authorization, and revocation.
- Docker Compose API, Caddy, and MediaMTX reported healthy after rebuild. MediaMTX's private Control API responded and the host has no published port 9997.

## Deviation

The API currently uses Python's SQLite module and explicit SQL rather than SQLAlchemy/Alembic. It has a basic startup upgrade for the session schema; a formal migration framework is still needed as schema changes grow.

