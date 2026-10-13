# Build and use the Android camera app

The Android app pairs with the local hub, stores its device token using Android Keystore, and publishes camera video/audio to the hub. For a complete new-machine setup, first follow [Run DoorCam end to end](setup-hub.md) through hub startup and certificate trust.

## Build

Install Android Studio, Android SDK Platform 36, Android SDK Build Tools 35, and JDK 17. Android Studio can install the SDK components from **Tools > SDK Manager**. From the repository root, build with Windows PowerShell:

```powershell
cd camera-android
.\gradlew.bat assembleDebug
```

On macOS/Linux, run `cd camera-android && ./gradlew assembleDebug`. The debug APK is `camera-android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it to install, or use Android Studio's **Run** action with USB debugging enabled. If Android blocks installation, allow installs from the file manager/browser used to open the APK, then turn that permission off again afterward. The app supports Android 7 (API 24) and later and uses RootEncoder 2.8.1 from JitPack; the first build needs internet access to download Gradle dependencies.

## Pair and start

1. Connect the camera phone and hub PC to the same trusted Wi-Fi/LAN. Trust the hub CA on the phone as described in [hub setup](setup-hub.md#4-trust-the-hubs-https-certificate-on-each-client).
2. In a PC browser, open `https://<DOORCAM_HOST>:8443`, sign in, and create a named camera pairing code. It expires in 10 minutes and works once; keep this page open.
3. In DoorCam Camera, tap **Scan pairing QR** and scan the code shown by the hub. The app reads the HTTPS hub URL and one-time code, then pairs. Manual entry is available if QR scanning is not convenient. The app displays the new camera ID after pairing.
4. Tap **Preview and adjust framing**. Use the 16:9 preview and grid to aim the phone; tap the image to focus, use the zoom slider, switch front/back lenses, and use the torch if supported. Tap **Done** when framing is set.
5. Choose the stream quality and audio option. Grant camera, microphone (if enabled), and notification permissions when prompted. Press **Start camera** and leave the foreground notification active; it shows the connection state.
6. In the PC browser, refresh the camera list, select the camera ID/name shown in the app, and connect. Allow camera audio with **Unmute camera audio** if desired. For a remote viewer on a second phone, install/trust the same CA there and use the same HTTPS hub address.
7. To use the first doorbell prototype, return to the camera app and tap **Open visitor doorbell screen**. Leave that screen awake where a visitor can reach it. A visitor taps **RING DOORBELL**; signed-in viewers see a ring alert and can open that camera's live view. This stage does not yet answer/decline calls or notify viewers while the web app is closed.

The app sends the selected 480p, 720p, or 1080p H.264 stream at 15 fps, with optional Opus audio. The camera setup preview uses a 16:9 frame with a composition grid, tap-to-focus, digital zoom, front/back selection, and torch control where available. The selected lens and zoom are saved. During a two-way call, the app plays incoming viewer audio on the speaker and captures camera audio using Android's voice-communication source with echo cancellation requested; actual echo suppression depends on phone hardware/software. RTSP publishing uses TCP 8554 on the local network and is not encrypted in this milestone.

## Background behavior

Start streaming while the app is visible. Android 14 and later require camera and microphone permissions when creating the corresponding foreground service and restrict starting these services from the background. After reboot, DoorCam shows a notification; tap it to open the app and start streaming.

Disable battery restrictions for DoorCam using Android's app battery settings if the phone stops streaming while idle. Samsung, Xiaomi, Oppo, and other vendors add their own auto-start and background controls; labels vary by model. Wi-Fi recovery and long-duration screen-off behavior should be checked on each phone model.

