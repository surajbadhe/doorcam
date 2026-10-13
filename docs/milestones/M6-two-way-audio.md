# M6: Two-way audio

## Implemented

- Added a separate `talk/{camera_id}` WebRTC path so push-to-talk does not renegotiate or interrupt the camera's video publisher.
- The signed-in browser uses **Start call** / **End call** to keep microphone audio flowing for a full-duplex conversation. Losing focus, hiding the page, or disconnecting ends the call and removes the WHIP session.
- The paired Android app receives the talk lane with WHEP and plays it through the phone speaker. It retries while no viewer is talking and stops when camera streaming stops.
- Hub authorization limits talk publishing to signed-in owners of that camera and talk reading to the paired device token for that camera.
- The talk lane is excluded from MediaMTX recordings.
- The live player has an **Unmute camera audio** / **Mute camera audio** control. Starting a call unmutes the camera audio so both ends can speak and hear at once.
- Browser microphone capture requests echo cancellation, noise suppression, and automatic gain control. Android camera audio is prepared with echo cancellation and noise suppression.

## Build and acceptance check

The initial push-to-talk path worked in a physical-device check. Full-duplex call mode and the relocated live audio control still need a fresh viewer reload and hands-on check. Install the latest camera APK if the Android app changed, rebuild/restart the hub after backend changes, then:

1. Start the camera stream and open the same camera in the viewer.
2. Allow microphone access when prompted by the browser.
3. Click **Unmute camera audio** and confirm the camera phone's microphone can be heard in the viewer.
4. Click **Start call** and speak from the viewer device. Confirm the camera phone plays the voice while its camera microphone remains audible in the viewer. Click **End call** and confirm viewer audio sending stops.
5. Check for echo. Use a headset on the viewer device if room speakers create feedback.
6. Try with an unrelated signed-in account and confirm it cannot publish talk audio to this camera.

The camera Android app adds the WebRTC Android runtime. The first rebuild requires Gradle dependency resolution from Maven Central.
