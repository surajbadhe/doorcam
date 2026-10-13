# Run DoorCam end to end

This guide starts a new hub, pairs either a simulator or Android phone, and opens the live feed from a browser. Keep the hub and test devices on the same trusted Wi-Fi/LAN. Do not expose the hub ports to the public internet.

## 1. Install prerequisites

- **All systems:** Docker Desktop (Windows/macOS) or Docker Engine with the Compose plugin (Linux). Start Docker before continuing. Git is needed to clone the repository.
- **Android camera:** Android Studio with Android SDK Platform 36, Build Tools 35, and Gradle JDK 17. A physical Android phone on the same LAN is required for camera streaming.
- **Simulator only:** Docker must be able to pull `bluenviron/mediamtx:1.21.1-ffmpeg` on the first run. The Windows PowerShell script runs FFmpeg in a temporary container; the macOS/Linux script requires FFmpeg installed on the host.

Clone the repository using the URL shown by your Git host, then open a terminal in the cloned `doorcam` directory (the directory containing this file and `.env.example`). All commands below run from that directory unless stated otherwise.

## 2. Set the PC's LAN address and secret

Find the PC's IPv4 address on the Wi-Fi/Ethernet network used by the phones:

- Windows: run `ipconfig` and find the active adapter's **IPv4 Address**.
- macOS: run `ipconfig getifaddr en0` (Wi-Fi; adapter names can differ).
- Linux: run `hostname -I` and choose the address on the active LAN.

Create your local environment file, then edit it:

```powershell
Copy-Item .env.example .env
```

On macOS/Linux, use `cp .env.example .env`.

1. Set `DOORCAM_HOST` to the PC's LAN IP, for example `192.168.1.225` (no `https://` and no port). Do not leave it as `localhost` when accessing the hub from a phone.
2. Replace `INTERNAL_WORKER_TOKEN` with a unique random secret. On Windows PowerShell, generate one with:

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
```

Paste the printed value after `INTERNAL_WORKER_TOKEN=`. On macOS/Linux, use `openssl rand -hex 32`. Keep `.env` private; it is ignored by Git.

## 3. Start the hub

Run:

```sh
docker compose --env-file .env -f hub/docker-compose.yml up --build -d
```

Check that all five services are running:

```sh
docker compose --env-file .env -f hub/docker-compose.yml ps
```

The first build can take several minutes while Docker downloads images and dependencies. Create the first administrator:

```sh
docker compose --env-file .env -f hub/docker-compose.yml exec api python -m app.cli create-admin
```

Follow the prompts and choose a strong password. This account is created in the persistent `api_data` volume; repeat this only if the database was deleted.

### Allow LAN connections through the PC firewall

If devices cannot connect, allow incoming **TCP 8443**, **TCP 8554**, and **UDP 8189** on the private/home network. Port 8443 serves HTTPS viewer/API traffic, 8554 receives the Android RTSP camera stream, and 8189 carries WebRTC media. Do not forward these ports on your router or expose them to the internet. The MediaMTX control/API ports are not published to the host.

## 4. Trust the hub's HTTPS certificate on each client

Caddy creates a local certificate authority. Export its public root certificate:

```sh
docker compose --env-file .env -f hub/docker-compose.yml cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

Install and trust `caddy-root.crt` on each phone and on any PC browser that will open the viewer. On Android, copy the certificate to the phone, open it, and follow Android's prompt to install it as a **CA certificate** (the menu is usually under Security > More security settings > Encryption & credentials). Android may require a screen lock. On Windows, open the certificate and install it for the current user in **Trusted Root Certification Authorities**. macOS/Linux browser trust steps depend on the browser and operating system.

Open `https://<DOORCAM_HOST>:8443` in the browser, replacing the placeholder with the PC IP, for example `https://192.168.1.225:8443`. Sign in with the administrator account. The certificate must be trusted for HTTPS and browser microphone access used by two-way talk.

## 5. Test a simulated camera (recommended first)

In the viewer, enter a camera name under **New camera name** and create a pairing code. It expires after 10 minutes and can be used once. Keep the viewer open.

Open a **second PowerShell window** on Windows in the `doorcam` directory. Exchange the code for a simulator device token:

```powershell
$hub = 'https://<DOORCAM_HOST>:8443'
$pair = Invoke-RestMethod -Uri "$hub/api/pairing/exchange" -Method Post -ContentType 'application/json' -Body (@{ code = '<PAIRING_CODE>'; name = 'Simulator' } | ConvertTo-Json)
$env:CAMERA_ID = $pair.camera_id
$env:DEVICE_TOKEN = $pair.device_token
$pair | Format-List camera_id
```

Replace both placeholders. If PowerShell reports an SSL trust error, install and trust `caddy-root.crt` on the PC, then open a new PowerShell window. Do not disable certificate validation.

Start the simulated video/audio stream and leave this terminal running:

```powershell
.\tools\simulate-camera.ps1
```

Return to the viewer, refresh the camera list if needed, select the simulator camera, and connect. Use **Unmute camera audio** to hear the generated tone. Stop the simulator with **Ctrl+C**. The simulator uses a synthetic test pattern and tone; it does not use your webcam or microphone.

On macOS/Linux, enter the following in a terminal from the repository root. Replace the hub IP and pairing code. The Python snippets read the one-time response and export the values for the simulator:

```sh
hub='https://<DOORCAM_HOST>:8443'
pairing_code='<PAIRING_CODE>'
pair=$(curl --cacert ./caddy-root.crt -sS -X POST "$hub/api/pairing/exchange" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"$pairing_code\",\"name\":\"Simulator\"}")
export CAMERA_ID=$(printf '%s' "$pair" | python3 -c 'import json,sys; print(json.load(sys.stdin)["camera_id"])')
export DEVICE_TOKEN=$(printf '%s' "$pair" | python3 -c 'import json,sys; print(json.load(sys.stdin)["device_token"])')
printf 'Paired camera ID: %s\n' "$CAMERA_ID"
./tools/simulate-camera.sh
```

Install FFmpeg with H.264 and Opus support if it is not already available. Keep the simulator terminal open while watching the feed.

## 6. Build and pair the Android camera

Follow [Android camera setup](setup-camera.md) to build the debug APK, install it on a phone, scan the hub's pairing QR code, adjust the framing, and start streaming. The phone and hub must be on the same LAN. Its camera ID appears in the app; select that camera in the web viewer.

## 7. Try recordings, motion, and two-way audio

- Keep a camera publishing for over a minute, then open the viewer's recordings/events section. Recording indexing may take an additional worker poll interval.
- Enable motion detection for the camera and move in view. Refresh the timeline to see whether an event is produced. Tune motion settings in the viewer if needed.
- For a call, allow microphone access in the browser and press **Start call**. The browser viewer must remain open for motion notifications; they are not delivered while it is closed.
- For a doorbell test, open **Open visitor doorbell screen** in the paired camera app. Tap **RING DOORBELL** on that phone; the signed-in browser should show a ring alert with **View camera** and **Dismiss** actions. The first stage requires the viewer page to stay open and does not yet answer or decline calls.
- For two devices, open the viewer on a second phone or computer at the same hub URL. Be aware that nearby loudspeakers and microphones can cause echo; use low volume or headphones during the first audio test.

## Stop, update, and data

Stop services while preserving the database and recordings:

```sh
docker compose --env-file .env -f hub/docker-compose.yml down
```

Start again with the `up` command above. To inspect logs, run `docker compose --env-file .env -f hub/docker-compose.yml logs -f` (optionally add a service name such as `api`). Rebuild after pulling code changes with `up --build -d`.

DoorCam stores account/pairing data in the Docker `api_data` volume and recordings/snapshots in the `recordings` volume. `docker compose down -v` permanently deletes these volumes; do not use `-v` unless you intentionally want to erase them.

## Troubleshooting

- **Browser says site is not secure / PowerShell SSL trust error:** install the exported Caddy root CA on that client. Use the exact IP configured in `DOORCAM_HOST` in the browser URL.
- **Viewer reports no stream / WHEP 404:** the paired camera has not started publishing yet, or the selected camera ID is wrong. Start the Android stream or simulator, refresh the camera list, and connect again.
- **RTSP 401 / authentication error:** exchange a new one-time pairing code and use the newly returned `camera_id` and `device_token` together. Keep both in the same terminal session; do not reuse stale or mismatched values.
- **WebRTC fails or video stays black:** confirm UDP 8189 is allowed through the PC firewall and that both devices are on the same LAN. Confirm Docker reports all services healthy/running.
- **Phone cannot open hub:** verify it is on the same Wi-Fi, use the PC's current LAN IP (not `localhost`), and allow TCP 8443 through the PC firewall.
- **Check service logs:** `docker compose --env-file .env -f hub/docker-compose.yml logs -f caddy api mediamtx motion-worker recording-worker`.

See [security notes](security.md) before using DoorCam beyond a trusted home LAN. The current Android RTSP ingest is not encrypted, so do not use it on an untrusted network.

