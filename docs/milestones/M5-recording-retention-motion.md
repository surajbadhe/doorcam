# M5: Recording, retention, and motion events

## Implemented

- MediaMTX records fMP4 segments of about one minute into the persistent `recordings` Docker volume.
- `recording-worker` indexes settled segments into SQLite and enforces the seven-day default retention and free-disk threshold. Recently modified files are skipped so a segment being written is not deleted.
- `motion-worker` reads each paired camera stream at a configurable 1–5 FPS, downsizes and blurs frames, applies frame differencing, and sends a JPEG snapshot when the changed area exceeds the threshold and cooldown.
- The API stores recording metadata and motion events. Recording bytes and event thumbnails require a valid viewer session and camera ownership. The internal event endpoint requires a separate worker token and is not routed through Caddy.
- The viewer has a camera timeline with motion snapshots and buttons to play a recording or start playback at the matching event offset.
- The viewer can set per-camera recording retention from 1 to 365 days.
- The viewer can enable or disable motion detection per camera. Disabled cameras are skipped by the motion worker, and the API rejects any in-flight event after the setting is turned off.
- While the signed-in viewer is open, it polls enabled cameras for new motion events and can show a text browser notification with the camera name and timestamp. Sound is an optional, device-local setting. Notification text contains no screenshot.
- Device alerts have a separate opt-in switch, off by default. Turning them off silences this device without stopping camera recording or motion event generation.
- The viewer can delete an individual recording or remove a camera. Camera removal revokes its device token and removes its recordings and motion snapshots.
- The viewer can delete an individual motion event, including its screenshot and event details. Browser notification settings include a test notification button to diagnose device/browser delivery.
- The viewer can select and bulk delete up to 500 motion events or recordings at a time.

## Before starting the hub

Create a unique random token in PowerShell:

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$token
```

Copy the printed value into `.env` as `INTERNAL_WORKER_TOKEN=<printed-value>`. Do not share or commit it. Then rebuild and start the services from the DoorCam directory:

```powershell
docker compose --env-file .env -f hub/docker-compose.yml up --build -d
```

The recording volume is separate from the SQLite volume and persists across container rebuilds. Deleting the `recordings` Docker volume permanently deletes recordings and motion snapshots.

## Configuration

- `RETENTION_DEFAULT_DAYS`: default camera retention, 7 days.
- `RETENTION_FREE_PERCENT`: begin deleting the oldest settled segments when free space falls below this percentage, default 10.
- `MOTION_FPS`: worker sampling rate, clamped to 1–5 FPS, default 3.
- `MOTION_THRESHOLD`: grayscale frame-difference threshold, default 22.
- `MOTION_MIN_AREA`: minimum changed area in the 320×180 analysis image, default 900 pixels.
- `MOTION_COOLDOWN_SECONDS`: minimum time between events for one camera, default 15 seconds.

The threshold is basic frame differencing; lighting changes or moving shadows can trigger events. Tune the values in `.env` after observing the installed camera location.

## Manual M5 acceptance check

1. Start a paired simulator or camera and confirm files appear under the `recordings` volume after a segment closes. It can take roughly one minute plus the indexer's polling delay.
2. Move in front of a real camera or use a motion clip; refresh the viewer timeline and confirm an event with a snapshot appears.
3. Select the event's playback button and confirm playback begins near the event time. Try a recording's normal playback button too.
4. Use a temporary test volume to check age and low-space cleanup. Confirm a currently writing segment is never selected for deletion.
5. Delete one recording from the viewer and confirm it disappears. Remove a test camera and confirm it is revoked and its recordings and snapshots are deleted.
6. Select a camera, turn motion detection off, save, and confirm new motion events stop. Turn it on again and confirm events resume. Allow browser notifications, use the test notification button, then leave the signed-in viewer open and trigger motion. Confirm the camera name and timestamp appear; enable the sound checkbox for an optional alert. Confirm no screenshot is included in the notification. Delete a motion event and confirm its row and snapshot are gone.
7. Disconnect live viewing while the camera remains publishing. Confirm recording and motion detection continue, then turn off device alerts and confirm no notification or sound is delivered. Select multiple event and recording rows and bulk delete each type.

Motion notifications currently depend on the signed-in DoorCam viewer remaining open so it can poll for events. Delivery while the browser is fully closed requires a push-notification service and is a later feature. M5 is **implemented but not yet verified** against these acceptance checks. The recording and event endpoints require sign-in; `/internal/events` is private to the Compose network.
