# M3: Android camera app (in progress)

The Kotlin Android project uses RootEncoder 2.8.1, manual one-use pairing, AES-GCM token encryption with Android Keystore, camera/microphone permission handling, a camera/microphone foreground service, partial wake and Wi-Fi locks, and capped exponential RTSP reconnect. The debug APK builds successfully. A real phone has streamed to the hub and a second phone has viewed the stream. The camera continued streaming with the screen locked for the period tested.

The project uses Gradle 8.11.1, Android Gradle Plugin 8.10.1, and Kotlin 2.3.21 to match RootEncoder's Kotlin metadata. The Wi-Fi interruption test did not pass cleanly: after reconnecting, an authentication error appeared and the feed stopped. Viewer access tokens expire after 15 minutes; automatic refresh has been added to the web viewer, but the updated page still needs a manual recheck. If the Android app itself reports that the device token was rejected, investigate the publisher separately.

QR pairing is implemented with an on-hub SVG code and an in-app CameraX/ML Kit scanner. Camera controls include 480p, 720p, and 1080p stream quality, front/back switching, and a setup preview with a 16:9 framing grid, tap-to-focus, zoom, and torch where supported. The chosen lens and zoom are applied to the next stream. The APK builds successfully; the setup preview and QR flow still need a phone check. A two-hour screen-off test and a successful Wi-Fi recovery test remain.

Android 14+ requires camera and microphone permissions when creating the matching foreground service and restricts background starts; DoorCam prompts the user after reboot instead of starting capture automatically. See [setup-camera.md](../setup-camera.md).

