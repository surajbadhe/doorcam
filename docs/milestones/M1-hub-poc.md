# M1 hub proof-of-concept summary

Built a Docker Compose hub with pinned MediaMTX 1.21.1, a minimal FastAPI `/healthz`, Caddy internal HTTPS, and a static WHEP viewer with connect, disconnect, and unmute controls. Added a Windows PowerShell simulator that runs FFmpeg from the pinned MediaMTX image.

Run from the `doorcam` directory: copy `.env.example` to `.env`, set `DOORCAM_HOST` to the PC's LAN IP for phone testing, then run `docker compose -f hub/docker-compose.yml up --build -d`. See `docs/setup-hub.md` for certificate trust and simulator instructions.

Verification: Compose configuration validated; API, Caddy, and MediaMTX report healthy; `/healthz` and the viewer page return HTTP 200; the simulator stream was probed as H.264 video plus Opus audio. WHEP playback in desktop/mobile browsers and glass-to-glass latency still need manual validation.

Deviation: simulator audio uses Opus for M1 WebRTC compatibility; confirm the Android encoder choice in M3.