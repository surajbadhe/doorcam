import base64
import json
import os
import sqlite3
from contextlib import closing
import threading
import time
import urllib.error
import urllib.request

import cv2
import numpy as np

DB_PATH = os.getenv("DATABASE_PATH", "/data/doorcam.sqlite3")
RTSP = os.getenv("MEDIAMTX_RTSP", "rtsp://mediamtx:8554")
API = os.getenv("DOORCAM_API", "http://api:8000")
MEDIA_API = os.getenv("MEDIAMTX_API", "http://mediamtx:9997")
TOKEN = os.getenv("INTERNAL_WORKER_TOKEN", "")
SAMPLE_PERIOD = 1 / max(1, min(5, int(os.getenv("MOTION_FPS", "3"))))
THRESHOLD = max(5, int(os.getenv("MOTION_THRESHOLD", "22")))
MIN_AREA = max(100, int(os.getenv("MOTION_MIN_AREA", "900")))
COOLDOWN = max(3, int(os.getenv("MOTION_COOLDOWN_SECONDS", "15")))
active = {}
lock = threading.Lock()


def cameras():
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn, conn:
        conn.execute("PRAGMA busy_timeout=10000")
        paired = [row[0] for row in conn.execute(
            "SELECT cam.id FROM cameras cam JOIN devices d ON d.id=cam.device_id "
            "WHERE d.revoked_at IS NULL AND cam.motion_enabled=1"
        )]
    try:
        with urllib.request.urlopen(f"{MEDIA_API}/v3/paths/list", timeout=3) as response:
            ready_paths = {item.get("name") for item in json.load(response).get("items", [])
                           if item.get("ready")}
        return [camera_id for camera_id in paired if f"cam/{camera_id}" in ready_paths]
    except (urllib.error.URLError, TimeoutError, ValueError):
        return []


def publish_event(camera_id, occurred_at, changed_area, jpeg):
    body = json.dumps({
        "camera_id": camera_id,
        "occurred_at": occurred_at,
        "changed_area": changed_area,
        "thumbnail_jpeg": base64.b64encode(jpeg).decode("ascii"),
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{API}/internal/events", data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Worker-Token": TOKEN},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()
    except (urllib.error.URLError, TimeoutError):
        pass


def watch_camera(camera_id, stop):
    url = f"rtsp://motion-worker:{TOKEN}@{RTSP.removeprefix('rtsp://').rstrip('/')}/cam/{camera_id}"
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    previous = None
    last_sample = 0.0
    last_event = 0.0
    try:
        while not stop.is_set():
            ok, frame = cap.read()
            if not ok:
                break
            current = time.monotonic()
            if current - last_sample < SAMPLE_PERIOD:
                continue
            last_sample = current
            small = cv2.resize(frame, (320, 180), interpolation=cv2.INTER_AREA)
            gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (21, 21), 0)
            if previous is None:
                previous = gray
                continue
            difference = cv2.absdiff(previous, gray)
            previous = gray
            _, mask = cv2.threshold(difference, THRESHOLD, 255, cv2.THRESH_BINARY)
            mask = cv2.dilate(mask, None, iterations=2)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            changed = sum(cv2.contourArea(contour) for contour in contours
                          if cv2.contourArea(contour) >= MIN_AREA)
            if changed < MIN_AREA or current - last_event < COOLDOWN:
                continue
            last_event = current
            thumbnail = cv2.resize(frame, (640, 360), interpolation=cv2.INTER_AREA)
            ok, encoded = cv2.imencode(".jpg", thumbnail, [cv2.IMWRITE_JPEG_QUALITY, 55])
            if ok:
                publish_event(camera_id, int(time.time()), changed, encoded.tobytes())
    finally:
        cap.release()


def main():
    if not TOKEN:
        raise RuntimeError("INTERNAL_WORKER_TOKEN is required")
    while True:
        camera_ids = set(cameras())
        with lock:
            for camera_id in camera_ids:
                entry = active.get(camera_id)
                if entry is None:
                    stop = threading.Event()
                    thread = threading.Thread(target=watch_camera, args=(camera_id, stop), daemon=True)
                    active[camera_id] = (thread, stop)
                    thread.start()
            for camera_id in list(active):
                entry = active[camera_id]
                if camera_id not in camera_ids:
                    active[camera_id][1].set()
                if not entry[0].is_alive():
                    del active[camera_id]
        time.sleep(2)


if __name__ == "__main__":
    main()
