# WebRTC audio codec for the M1 simulator

MediaMTX's WebRTC reader supports Opus, G.722, and G.711 audio, while the initial simulator recipe used AAC. AAC over the planned RTSP ingest path cannot be assumed to pass through to WHEP. The M1 simulator therefore publishes Opus so the browser proof of concept can exercise audio without adding a transcoder.

This is a development-only adjustment. Before M3, verify the selected Android encoder's RTSP audio codec against MediaMTX's WebRTC reader. If it publishes AAC, either select a compatible encoder or add and measure an explicit audio-transcoding stage; do not assume AAC will be audible in the WHEP viewer.

Reference: [MediaMTX WebRTC reader codec support](https://mediamtx.org/docs/read/webrtc).