# M4: Viewer web app (in progress)

Implemented so far:

- Authenticated camera list with online/offline/unknown status from MediaMTX RTSP publisher sessions.
- Camera list refresh on demand and every 15 seconds while signed in.
- WHEP connection recovery with capped exponential retry delay and a 5-second grace period for transient `disconnected` state.
- Fullscreen playback control.
- PWA manifest, install prompt where supported, and an app-shell service worker. The worker does not cache API responses, WHEP requests, or video.

Still to complete or verify:

- Run the planned browser checks on Android Chrome and iPhone Safari, including home-screen installation.
- Verify that camera status changes correctly as publishers connect and disconnect.
- Verify viewer reconnect behavior after Wi-Fi/network interruption and access-token refresh.
- Review mobile layout and two-viewer playback behavior.

M4 is not complete until these manual acceptance checks pass. The owner has deferred end-of-milestone testing until the implementation work is complete.
