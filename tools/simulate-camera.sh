#!/usr/bin/env bash
set -euo pipefail
CAMERA_ID="${CAMERA_ID:-dev}"
if [[ -n "${RTSP_URL:-}" ]]; then
  stream_url="$RTSP_URL"
elif [[ -n "${DEVICE_TOKEN:-}" ]]; then
  stream_url="rtsp://${CAMERA_ID}:${DEVICE_TOKEN}@localhost:8554/cam/${CAMERA_ID}"
else
  stream_url="rtsp://localhost:8554/cam/${CAMERA_ID}"
fi
exec ffmpeg -re -f lavfi -i "testsrc2=size=1280x720:rate=15" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 30 -b:v 1500k \
  -c:a libopus -application lowdelay -b:a 64k -f rtsp -rtsp_transport tcp "$stream_url"

