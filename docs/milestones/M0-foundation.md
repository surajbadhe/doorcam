# M0 foundation summary

Built the standalone DoorCam scaffold, README, Apache-2.0 license placeholder, lint configuration, CI workflow, docs stubs, and FFmpeg simulator.

Run: `./tools/simulate-camera.sh` with Bash and FFmpeg, or use the M1 PowerShell/Docker simulator documented in `docs/setup-hub.md` once the hub is running.

Verification: Bash syntax check passed; a local MediaMTX 1.21.1 instance accepted an RTSP simulator stream and FFprobe found both video and audio. The Ruff lint command passed locally in a Python 3.12 container. A hosted GitHub Actions run was not available because this workspace has no Git repository or remote configured.

Deviation: the M1 simulator uses Opus instead of the original AAC track so the WebRTC reader can carry audio; see `docs/decisions/002-webrtc-audio.md`.