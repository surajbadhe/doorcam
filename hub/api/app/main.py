import hashlib
import base64
import json
import os
import secrets
import shutil
import sqlite3
import time
import urllib.request
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

import qrcode
from qrcode.image.svg import SvgPathFillImage
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

DB_PATH = os.getenv("DATABASE_PATH", "/data/doorcam.sqlite3")
MEDIA_API = os.getenv("MEDIAMTX_API", "http://mediamtx:9997")
RECORDINGS_PATH = Path(os.getenv("RECORDINGS_PATH", "/recordings"))
SNAPSHOTS_PATH = Path(os.getenv("SNAPSHOTS_PATH", str(RECORDINGS_PATH / "snapshots")))
WORKER_TOKEN = os.getenv("INTERNAL_WORKER_TOKEN", "")
DEFAULT_RETENTION_DAYS = max(1, min(365, int(os.getenv("RETENTION_DEFAULT_DAYS", "7"))))
ph = PasswordHasher()
app = FastAPI(title="DoorCam Hub API", docs_url=None, redoc_url=None)


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


@contextmanager
def db():
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    c = sqlite3.connect(DB_PATH, timeout=15)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    c.execute("PRAGMA journal_mode=WAL")
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init_db():
    with db() as c:
        c.executescript(f"""
        CREATE TABLE IF NOT EXISTS users (
          id TEXT PRIMARY KEY,
          username TEXT UNIQUE NOT NULL,
          password_hash TEXT NOT NULL,
          role TEXT NOT NULL CHECK(role IN ('admin','viewer')),
          created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions (
          token_hash TEXT PRIMARY KEY,
          user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          expires_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          token_type TEXT NOT NULL DEFAULT 'access'
        );
        CREATE TABLE IF NOT EXISTS pairing_codes (
          code_hash TEXT PRIMARY KEY,
          owner_id TEXT NOT NULL REFERENCES users(id),
          expires_at INTEGER NOT NULL,
          used_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS devices (
          id TEXT PRIMARY KEY,
          owner_id TEXT NOT NULL REFERENCES users(id),
          token_hash TEXT NOT NULL,
          revoked_at INTEGER,
          created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS cameras (
          id TEXT PRIMARY KEY,
          device_id TEXT UNIQUE NOT NULL REFERENCES devices(id),
          owner_id TEXT NOT NULL REFERENCES users(id),
          name TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          retention_days INTEGER NOT NULL DEFAULT {DEFAULT_RETENTION_DAYS},
          motion_enabled INTEGER NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS recordings (
          id TEXT PRIMARY KEY,
          camera_id TEXT NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
          started_at INTEGER NOT NULL,
          ended_at INTEGER NOT NULL,
          path TEXT NOT NULL UNIQUE,
          size_bytes INTEGER NOT NULL,
          created_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS recordings_by_camera_time
          ON recordings(camera_id,started_at);
        CREATE TABLE IF NOT EXISTS events (
          id TEXT PRIMARY KEY,
          camera_id TEXT NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
          type TEXT NOT NULL,
          ts INTEGER NOT NULL,
          meta_json TEXT NOT NULL,
          thumbnail_path TEXT,
          recording_id TEXT REFERENCES recordings(id) ON DELETE SET NULL
        );
        CREATE INDEX IF NOT EXISTS events_by_camera_time ON events(camera_id,ts);
        CREATE TABLE IF NOT EXISTS doorbell_rings (
          id TEXT PRIMARY KEY,
          camera_id TEXT NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
          created_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS doorbell_rings_by_camera_time
          ON doorbell_rings(camera_id,created_at);
        CREATE TABLE IF NOT EXISTS attempts (
          ip TEXT NOT NULL,
          happened_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS schema_migrations (
          version INTEGER PRIMARY KEY,
          applied_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS attempts_by_ip ON attempts(ip,happened_at);
        """)
        columns = {row["name"] for row in c.execute("PRAGMA table_info(sessions)")}
        camera_columns = {row["name"] for row in c.execute("PRAGMA table_info(cameras)")}
        c.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(1,?)", (now(),)
        )
        if "token_type" not in columns:
            c.execute("ALTER TABLE sessions ADD COLUMN token_type TEXT NOT NULL DEFAULT 'access'")
        c.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(2,?)", (now(),)
        )
        if "retention_days" not in camera_columns:
            c.execute(
                "ALTER TABLE cameras ADD COLUMN retention_days INTEGER NOT NULL "
                f"DEFAULT {DEFAULT_RETENTION_DAYS}"
            )
        c.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(3,?)", (now(),)
        )
        if "motion_enabled" not in camera_columns:
            c.execute("ALTER TABLE cameras ADD COLUMN motion_enabled INTEGER NOT NULL DEFAULT 1")
        c.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(4,?)", (now(),)
        )
        c.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(5,?)", (now(),)
        )
        c.commit()


@app.on_event("startup")
def startup():
    init_db()


def now():
    return int(time.time())


def current_user(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Bearer token required")
    with db() as c:
        row = c.execute(
            """SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id
          WHERE s.token_hash=? AND s.expires_at>?""",
            (sha(authorization[7:]), now()),
        ).fetchone()
    if not row:
        raise HTTPException(401, "Session expired or invalid")
    return row


def administrator(user=Depends(current_user)):
    if user["role"] != "admin":
        raise HTTPException(403, "Administrator access required")
    return user


class Credentials(BaseModel):
    username: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=1, max_length=256)


class RefreshBody(BaseModel):
    refresh_token: str


class PairStart(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class PairExchange(BaseModel):
    code: str
    name: str = Field(min_length=1, max_length=80)


class PairingQr(BaseModel):
    hub_url: str = Field(min_length=10, max_length=512)
    code: str = Field(min_length=8, max_length=128)


class MotionEvent(BaseModel):
    camera_id: str = Field(min_length=1, max_length=80)
    occurred_at: int
    changed_area: float = Field(ge=0)
    thumbnail_jpeg: str = Field(min_length=1, max_length=700_000)


class RetentionSettings(BaseModel):
    retention_days: int = Field(ge=1, le=365)


class MotionSettings(BaseModel):
    enabled: bool


class BatchDelete(BaseModel):
    ids: list[str] = Field(min_length=1, max_length=500)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.post("/api/auth/login")
def login(body: Credentials, request: Request):
    ip, ts = request.client.host if request.client else "unknown", now()
    with db() as c:
        c.execute("DELETE FROM attempts WHERE happened_at<?", (ts - 900,))
        count = c.execute(
            "SELECT count(*) FROM attempts WHERE ip=? AND happened_at>?", (ip, ts - 900)
        ).fetchone()[0]
        if count >= 10:
            raise HTTPException(429, "Too many login attempts; wait 15 minutes")
        c.execute("INSERT INTO attempts VALUES(?,?)", (ip, ts))
        c.commit()
        user = c.execute("SELECT * FROM users WHERE username=?", (body.username,)).fetchone()
        if not user:
            raise HTTPException(401, "Invalid username or password")
        try:
            ph.verify(user["password_hash"], body.password)
        except VerifyMismatchError:
            raise HTTPException(401, "Invalid username or password")
        access, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
        c.execute(
            "INSERT INTO sessions(token_hash,user_id,expires_at,created_at,"
            "token_type) VALUES(?,?,?,?,?)",
            (sha(access), user["id"], ts + 900, ts, "access"),
        )
        c.execute(
            "INSERT INTO sessions(token_hash,user_id,expires_at,created_at,"
            "token_type) VALUES(?,?,?,?,?)",
            (sha(refresh), user["id"], ts + 2592000, ts, "refresh"),
        )
    return {
        "access_token": access,
        "refresh_token": refresh,
        "token_type": "bearer",
        "expires_in": 900,
        "user": {"id": user["id"], "username": user["username"], "role": user["role"]},
    }


@app.post("/api/auth/refresh")
def refresh(body: RefreshBody):
    ts = now()
    with db() as c:
        row = c.execute(
            "SELECT user_id FROM sessions WHERE token_hash=? AND "
            "token_type='refresh' AND expires_at>?",
            (sha(body.refresh_token), ts),
        ).fetchone()
        if not row:
            raise HTTPException(401, "Refresh token expired or already used")
        c.execute("DELETE FROM sessions WHERE token_hash=?", (sha(body.refresh_token),))
        access, refresh_token = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
        c.execute(
            "INSERT INTO sessions(token_hash,user_id,expires_at,created_at,"
            "token_type) VALUES(?,?,?,?,?)",
            (sha(access), row["user_id"], ts + 900, ts, "access"),
        )
        c.execute(
            "INSERT INTO sessions(token_hash,user_id,expires_at,created_at,"
            "token_type) VALUES(?,?,?,?,?)",
            (sha(refresh_token), row["user_id"], ts + 2592000, ts, "refresh"),
        )
    return {
        "access_token": access,
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "expires_in": 900,
    }


@app.post("/api/auth/logout", status_code=204)
def logout(user=Depends(current_user), authorization: str = Header()):
    with db() as c:
        c.execute("DELETE FROM sessions WHERE token_hash=?", (sha(authorization[7:]),))
    return None


@app.post("/api/pairing/start")
def pairing_start(body: PairStart, user=Depends(administrator)):
    code, expires = secrets.token_urlsafe(18), now() + 600
    with db() as c:
        c.execute("INSERT INTO pairing_codes VALUES(?,?,?,NULL)", (sha(code), user["id"], expires))
    return {
        "code": code,
        "name": body.name,
        "expires_at": datetime.fromtimestamp(expires, UTC).isoformat(),
    }


@app.post("/api/pairing/qr")
def pairing_qr(body: PairingQr, user=Depends(administrator)):
    hub_url = body.hub_url.rstrip("/")
    parsed = urlsplit(hub_url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise HTTPException(400, "Hub URL must be a plain HTTPS origin")
    with db() as c:
        row = c.execute(
            "SELECT 1 FROM pairing_codes WHERE code_hash=? AND owner_id=? "
            "AND used_at IS NULL AND expires_at>?",
            (sha(body.code), user["id"], now()),
        ).fetchone()
    if not row:
        raise HTTPException(401, "Pairing code invalid, expired, or already used")
    payload = json.dumps(
        {"type": "doorcam-pair-v1", "hub_url": hub_url, "code": body.code},
        separators=(",", ":"),
    )
    image = qrcode.make(payload, image_factory=SvgPathFillImage, border=4)
    return Response(
        content=image.to_string(encoding="unicode"),
        media_type="image/svg+xml",
        headers={"Cache-Control": "no-store"},
    )


@app.post("/api/pairing/exchange")
def pairing_exchange(body: PairExchange, request: Request):
    ts, ip = now(), request.client.host if request.client else "unknown"
    with db() as c:
        c.execute("DELETE FROM attempts WHERE happened_at<?", (ts - 900,))
        count = c.execute(
            "SELECT count(*) FROM attempts WHERE ip=? AND happened_at>?", (ip, ts - 900)
        ).fetchone()[0]
        if count >= 20:
            raise HTTPException(429, "Pairing rate limit exceeded")
        c.execute("INSERT INTO attempts VALUES(?,?)", (ip, ts))
        c.commit()
        pair = c.execute(
            "SELECT * FROM pairing_codes WHERE code_hash=? AND used_at IS NULL AND expires_at>?",
            (sha(body.code), ts),
        ).fetchone()
        if not pair:
            raise HTTPException(401, "Pairing code invalid, expired, or already used")
        camera, token = secrets.token_hex(8), secrets.token_urlsafe(32)
        c.execute(
            "UPDATE pairing_codes SET used_at=? WHERE code_hash=? AND used_at IS NULL",
            (ts, pair["code_hash"]),
        )
        c.execute(
            "INSERT INTO devices VALUES(?,?,?,NULL,?)", (camera, pair["owner_id"], sha(token), ts)
        )
        c.execute(
            "INSERT INTO cameras(id,device_id,owner_id,name,created_at) VALUES(?,?,?,?,?)",
            (camera, camera, pair["owner_id"], body.name, ts),
        )
    return {"camera_id": camera, "device_token": token, "path": f"cam/{camera}"}


@app.get("/api/cameras")
def cameras(user=Depends(current_user)):
    with db() as c:
        rows = c.execute(
            "SELECT id,name,retention_days,motion_enabled FROM cameras "
            "WHERE owner_id=? ORDER BY created_at", (user["id"],)
        ).fetchall()
    live_ids = None
    try:
        request = urllib.request.Request(f"{MEDIA_API}/v3/rtsp/sessions/list")
        with urllib.request.urlopen(request, timeout=2) as response:
            sessions = json.load(response).get("items", [])
        live_ids = {
            session.get("path", "").removeprefix("cam/")
            for session in sessions
            if session.get("path", "").startswith("cam/")
        }
    except Exception:
        # Keep camera listing useful even if MediaMTX's internal API is unavailable.
        pass
    return [
        {
            "id": row["id"],
            "name": row["name"],
            "retention_days": row["retention_days"],
            "motion_enabled": bool(row["motion_enabled"]),
            "path": f"cam/{row['id']}",
            "status": "unknown" if live_ids is None else (
                "online" if row["id"] in live_ids else "offline"
            ),
        }
        for row in rows
    ]


def authenticated_camera(camera_id: str, authorization: str | None):
    if not authorization or not authorization.startswith("Basic "):
        raise HTTPException(401, "Camera credentials required")
    try:
        credentials = base64.b64decode(authorization[6:], validate=True).decode("utf-8")
        device_id, token = credentials.split(":", 1)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(401, "Invalid camera credentials") from None
    if device_id != camera_id or not token:
        raise HTTPException(401, "Invalid camera credentials")
    with db() as c:
        row = c.execute(
            "SELECT token_hash,revoked_at FROM devices WHERE id=?", (camera_id,)
        ).fetchone()
    if not row or row["revoked_at"] is not None or not secrets.compare_digest(
        row["token_hash"], sha(token)
    ):
        raise HTTPException(401, "Camera is not authorized")


@app.post("/api/cameras/{camera_id}/ring")
def ring_doorbell(camera_id: str, authorization: str | None = Header(default=None)):
    authenticated_camera(camera_id, authorization)
    timestamp = now()
    with db() as c:
        previous = c.execute(
            "SELECT id,created_at FROM doorbell_rings WHERE camera_id=? AND created_at>? "
            "ORDER BY created_at DESC LIMIT 1",
            (camera_id, timestamp - 20),
        ).fetchone()
        if previous:
            return {"id": previous["id"], "camera_id": camera_id,
                    "created_at": previous["created_at"], "status": "ringing"}
        ring_id = uuid.uuid4().hex
        c.execute(
            "INSERT INTO doorbell_rings(id,camera_id,created_at) VALUES(?,?,?)",
            (ring_id, camera_id, timestamp),
        )
        c.execute("DELETE FROM doorbell_rings WHERE created_at<?", (timestamp - 86400,))
    return {"id": ring_id, "camera_id": camera_id, "created_at": timestamp,
            "status": "ringing"}


@app.get("/api/rings")
def list_doorbell_rings(since: int = 0, user=Depends(current_user)):
    # Keep ring requests short-lived in the viewer; a recent request is surfaced
    # for up to one minute and naturally disappears if nobody responds.
    cutoff = max(since, now() - 60)
    with db() as c:
        rows = c.execute(
            "SELECT r.id,r.camera_id,r.created_at,cam.name FROM doorbell_rings r "
            "JOIN cameras cam ON cam.id=r.camera_id "
            "WHERE cam.owner_id=? AND r.created_at>=? ORDER BY r.created_at,r.id LIMIT 50",
            (user["id"], cutoff),
        ).fetchall()
    return [dict(row) for row in rows]


@app.patch("/api/cameras/{camera_id}/retention")
def update_retention(camera_id: str, body: RetentionSettings, user=Depends(current_user)):
    with db() as c:
        result = c.execute(
            "UPDATE cameras SET retention_days=? WHERE id=? AND owner_id=?",
            (body.retention_days, camera_id, user["id"]),
        )
    if not result.rowcount:
        raise HTTPException(404, "Camera not found")
    return {"camera_id": camera_id, "retention_days": body.retention_days}


@app.patch("/api/cameras/{camera_id}/motion")
def update_motion(camera_id: str, body: MotionSettings, user=Depends(current_user)):
    with db() as c:
        result = c.execute(
            "UPDATE cameras SET motion_enabled=? WHERE id=? AND owner_id=?",
            (int(body.enabled), camera_id, user["id"]),
        )
    if not result.rowcount:
        raise HTTPException(404, "Camera not found")
    return {"camera_id": camera_id, "motion_enabled": body.enabled}


def owned_recording(recording_id: str, user):
    with db() as c:
        row = c.execute(
            "SELECT r.* FROM recordings r JOIN cameras cam ON cam.id=r.camera_id "
            "WHERE r.id=? AND cam.owner_id=?",
            (recording_id, user["id"]),
        ).fetchone()
    if not row:
        raise HTTPException(404, "Recording not found")
    return row


def camera_session_kick_count(camera_id: str) -> int:
    kicked = 0
    for protocol in ("rtsp", "webrtc"):
        try:
            endpoint = f"{MEDIA_API}/v3/{protocol}/sessions/list"
            with urllib.request.urlopen(endpoint, timeout=3) as response:
                items = json.load(response).get("items", [])
            for session in items:
                if session.get("path") in (f"cam/{camera_id}", f"talk/{camera_id}") and session.get("id"):
                    req = urllib.request.Request(
                        f"{MEDIA_API}/v3/{protocol}/sessions/kick/{session['id']}", method="POST"
                    )
                    with urllib.request.urlopen(req, timeout=3):
                        kicked += 1
        except Exception:
            continue
    return kicked


@app.get("/api/recordings")
def list_recordings(camera_id: str, user=Depends(current_user)):
    with db() as c:
        rows = c.execute(
            "SELECT r.id,r.camera_id,r.started_at,r.ended_at,r.size_bytes FROM recordings r "
            "JOIN cameras cam ON cam.id=r.camera_id WHERE cam.owner_id=? AND r.camera_id=? "
            "ORDER BY r.started_at DESC LIMIT 500",
            (user["id"], camera_id),
        ).fetchall()
    return [dict(row) for row in rows]


@app.get("/api/events")
def list_events(camera_id: str, user=Depends(current_user)):
    with db() as c:
        rows = c.execute(
            "SELECT e.id,e.camera_id,e.type,e.ts,e.meta_json,e.recording_id FROM events e "
            "JOIN cameras cam ON cam.id=e.camera_id WHERE cam.owner_id=? AND e.camera_id=? "
            "ORDER BY e.ts DESC LIMIT 500",
            (user["id"], camera_id),
        ).fetchall()
    return [
        {**dict(row), "meta": json.loads(row["meta_json"]), "thumbnail_url": f"/api/events/{row['id']}/thumbnail"}
        for row in rows
    ]


@app.get("/api/events/{event_id}/thumbnail")
def event_thumbnail(event_id: str, user=Depends(current_user)):
    with db() as c:
        row = c.execute(
            "SELECT e.thumbnail_path FROM events e JOIN cameras cam ON cam.id=e.camera_id "
            "WHERE e.id=? AND cam.owner_id=?",
            (event_id, user["id"]),
        ).fetchone()
    if not row or not row["thumbnail_path"]:
        raise HTTPException(404, "Thumbnail not found")
    path = (SNAPSHOTS_PATH / row["thumbnail_path"]).resolve()
    if SNAPSHOTS_PATH.resolve() not in path.parents or not path.is_file():
        raise HTTPException(404, "Thumbnail not found")
    return Response(path.read_bytes(), media_type="image/jpeg", headers={"Cache-Control": "no-store"})


@app.delete("/api/events/{event_id}", status_code=204)
def delete_event(event_id: str, user=Depends(current_user)):
    with db() as c:
        row = c.execute(
            "SELECT e.thumbnail_path FROM events e JOIN cameras cam ON cam.id=e.camera_id "
            "WHERE e.id=? AND cam.owner_id=?",
            (event_id, user["id"]),
        ).fetchone()
        if not row:
            raise HTTPException(404, "Motion event not found")
        c.execute(
            "DELETE FROM events WHERE id=? AND camera_id IN "
            "(SELECT id FROM cameras WHERE owner_id=?)",
            (event_id, user["id"]),
        )
    if row["thumbnail_path"]:
        path = (SNAPSHOTS_PATH / row["thumbnail_path"]).resolve()
        if SNAPSHOTS_PATH.resolve() in path.parents:
            path.unlink(missing_ok=True)
    return Response(status_code=204)


@app.delete("/api/events")
def delete_events(body: BatchDelete, user=Depends(current_user)):
    event_ids = list(dict.fromkeys(body.ids))
    placeholders = ",".join("?" for _ in event_ids)
    with db() as c:
        rows = c.execute(
            f"SELECT e.id,e.thumbnail_path FROM events e JOIN cameras cam ON cam.id=e.camera_id "
            f"WHERE cam.owner_id=? AND e.id IN ({placeholders})",
            (user["id"], *event_ids),
        ).fetchall()
        if len(rows) != len(event_ids):
            raise HTTPException(404, "One or more motion events were not found")
        c.execute(f"DELETE FROM events WHERE id IN ({placeholders})", event_ids)
    snapshots_root = SNAPSHOTS_PATH.resolve()
    for row in rows:
        if row["thumbnail_path"]:
            path = (SNAPSHOTS_PATH / row["thumbnail_path"]).resolve()
            if snapshots_root in path.parents:
                path.unlink(missing_ok=True)
    return {"deleted": event_ids}


@app.get("/api/recordings/{recording_id}/stream")
def recording_stream(recording_id: str, request: Request, user=Depends(current_user)):
    row = owned_recording(recording_id, user)
    path = Path(row["path"]).resolve()
    if RECORDINGS_PATH.resolve() not in path.parents or not path.is_file():
        raise HTTPException(404, "Recording file not found")
    size = path.stat().st_size
    range_header = request.headers.get("range")
    start, end = 0, size - 1
    status_code = 200
    if range_header:
        try:
            unit, value = range_header.split("=", 1)
            first, last = value.split("-", 1)
            if unit != "bytes" or not first:
                raise ValueError
            start = int(first)
            end = min(int(last), size - 1) if last else size - 1
            if start < 0 or end < start or start >= size:
                raise ValueError
            status_code = 206
        except ValueError:
            raise HTTPException(416, "Invalid byte range", headers={"Content-Range": f"bytes */{size}"})
    length = end - start + 1

    def content():
        with path.open("rb") as stream:
            stream.seek(start)
            remaining = length
            while remaining:
                chunk = stream.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    headers = {"Accept-Ranges": "bytes", "Content-Length": str(length), "Cache-Control": "no-store"}
    if status_code == 206:
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    return StreamingResponse(content(), status_code=status_code, media_type="video/mp4", headers=headers)


@app.delete("/api/recordings/{recording_id}", status_code=204)
def delete_recording(recording_id: str, user=Depends(current_user)):
    row = owned_recording(recording_id, user)
    path = Path(row["path"]).resolve()
    if RECORDINGS_PATH.resolve() not in path.parents:
        raise HTTPException(404, "Recording not found")
    try:
        path.unlink(missing_ok=True)
    except OSError:
        raise HTTPException(500, "Could not delete recording file")
    with db() as c:
        c.execute(
            "DELETE FROM recordings WHERE id=? AND camera_id IN "
            "(SELECT id FROM cameras WHERE owner_id=?)",
            (recording_id, user["id"]),
        )
    return Response(status_code=204)


@app.delete("/api/recordings")
def delete_recordings(body: BatchDelete, user=Depends(current_user)):
    recording_ids = list(dict.fromkeys(body.ids))
    placeholders = ",".join("?" for _ in recording_ids)
    with db() as c:
        rows = c.execute(
            f"SELECT r.id,r.path FROM recordings r JOIN cameras cam ON cam.id=r.camera_id "
            f"WHERE cam.owner_id=? AND r.id IN ({placeholders})",
            (user["id"], *recording_ids),
        ).fetchall()
        if len(rows) != len(recording_ids):
            raise HTTPException(404, "One or more recordings were not found")
        root = RECORDINGS_PATH.resolve()
        paths = [(row["id"], Path(row["path"]).resolve()) for row in rows]
        if any(root not in path.parents for _, path in paths):
            raise HTTPException(404, "One or more recording files were not found")
        c.execute(f"DELETE FROM recordings WHERE id IN ({placeholders})", recording_ids)
    for _, path in paths:
        path.unlink(missing_ok=True)
    return {"deleted": recording_ids}


@app.delete("/api/cameras/{camera_id}")
def delete_camera(camera_id: str, user=Depends(current_user)):
    with db() as c:
        camera = c.execute(
            "SELECT id,device_id FROM cameras WHERE id=? AND owner_id=?",
            (camera_id, user["id"]),
        ).fetchone()
        if not camera:
            raise HTTPException(404, "Camera not found")
        c.execute("UPDATE devices SET revoked_at=? WHERE id=?", (now(), camera["device_id"]))

    kicked = camera_session_kick_count(camera_id)
    with db() as c:
        thumbnails = [row["thumbnail_path"] for row in c.execute(
            "SELECT e.thumbnail_path FROM events e WHERE e.camera_id=? AND e.thumbnail_path IS NOT NULL",
            (camera_id,),
        )]
        c.execute("DELETE FROM cameras WHERE id=? AND owner_id=?", (camera_id, user["id"]))
        c.execute("DELETE FROM devices WHERE id=?", (camera["device_id"],))

    for name in thumbnails:
        path = (SNAPSHOTS_PATH / name).resolve()
        if SNAPSHOTS_PATH.resolve() in path.parents:
            path.unlink(missing_ok=True)
    camera_root = (RECORDINGS_PATH / "cam").resolve()
    camera_dir = (camera_root / camera_id).resolve()
    if camera_dir.parent == camera_root:
        shutil.rmtree(camera_dir, ignore_errors=True)
    return {"status": "deleted", "camera_id": camera_id, "sessions_kicked": kicked}


@app.post("/internal/events", status_code=201)
def create_motion_event(body: MotionEvent, x_worker_token: str | None = Header(default=None)):
    if not WORKER_TOKEN or not x_worker_token or not secrets.compare_digest(x_worker_token, WORKER_TOKEN):
        raise HTTPException(401, "Worker authentication required")
    try:
        image = base64.b64decode(body.thumbnail_jpeg, validate=True)
    except Exception:
        raise HTTPException(400, "Invalid JPEG thumbnail")
    if len(image) > 512_000 or not image.startswith(b"\xff\xd8\xff"):
        raise HTTPException(400, "Thumbnail must be a JPEG smaller than 512 KB")
    event_id = uuid.uuid4().hex
    SNAPSHOTS_PATH.mkdir(parents=True, exist_ok=True)
    thumbnail_name = f"{event_id}.jpg"
    (SNAPSHOTS_PATH / thumbnail_name).write_bytes(image)
    with db() as c:
        camera = c.execute(
            "SELECT id,motion_enabled FROM cameras WHERE id=?", (body.camera_id,)
        ).fetchone()
        if not camera:
            (SNAPSHOTS_PATH / thumbnail_name).unlink(missing_ok=True)
            raise HTTPException(404, "Camera not found")
        if not camera["motion_enabled"]:
            (SNAPSHOTS_PATH / thumbnail_name).unlink(missing_ok=True)
            raise HTTPException(409, "Motion detection is disabled for this camera")
        recording = c.execute(
            "SELECT id FROM recordings WHERE camera_id=? AND started_at<=? AND ended_at>=? "
            "ORDER BY started_at DESC LIMIT 1",
            (body.camera_id, body.occurred_at, body.occurred_at),
        ).fetchone()
        meta = json.dumps({"changed_area": round(body.changed_area, 2)}, separators=(",", ":"))
        c.execute(
            "INSERT INTO events(id,camera_id,type,ts,meta_json,thumbnail_path,recording_id) "
            "VALUES(?,?,'motion',?,?,?,?)",
            (event_id, body.camera_id, body.occurred_at, meta, thumbnail_name,
             recording["id"] if recording else None),
        )
    return {"id": event_id, "camera_id": body.camera_id, "type": "motion", "ts": body.occurred_at}


@app.post("/api/devices/{device_id}/revoke")
def revoke(device_id: str, user=Depends(administrator)):
    with db() as c:
        result = c.execute("UPDATE devices SET revoked_at=? WHERE id=?", (now(), device_id))
        if not result.rowcount:
            raise HTTPException(404, "Device not found")
    kicked = 0
    for protocol in ("rtsp", "webrtc"):
        endpoint = f"{MEDIA_API}/v3/{protocol}/sessions/list"
        try:
            with urllib.request.urlopen(endpoint, timeout=3) as response:
                items = json.load(response).get("items", [])
            for session in items:
                if session.get("path") in (f"cam/{device_id}", f"talk/{device_id}") and session.get("id"):
                    req = urllib.request.Request(
                        f"{MEDIA_API}/v3/{protocol}/sessions/kick/{session['id']}", method="POST"
                    )
                    urllib.request.urlopen(req, timeout=3).close()
                    kicked += 1
        except Exception:
            pass
    return {"status": "revoked", "device_id": device_id, "sessions_kicked": kicked}


@app.post("/internal/mediamtx/auth")
async def mediamtx_auth(request: Request):
    data = await request.json()
    action, path = data.get("action"), data.get("path", "")
    protocol = data.get("protocol", "")
    username, password = data.get("user", ""), data.get("password", "")
    camera_id = path.removeprefix("cam/")
    with db() as c:
        # Separate WebRTC lane: viewers can send audio only to owned cameras;
        # paired camera credentials can read only their own talk lane.
        if path.startswith("talk/"):
            talk_camera_id = path.removeprefix("talk/")
            if not talk_camera_id or "/" in talk_camera_id:
                raise HTTPException(401, "Not authorized")
            if protocol == "webrtc" and action == "publish" and username == "viewer" and password:
                row = c.execute(
                    """SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id
                    JOIN cameras cam ON cam.owner_id=u.id WHERE s.token_hash=?
                    AND s.expires_at>? AND cam.id=? AND u.role IN ('admin','viewer')""",
                    (sha(password), now(), talk_camera_id),
                ).fetchone()
                if row:
                    return {"status": "ok"}
            if protocol == "webrtc" and action == "read" and username == talk_camera_id and password:
                row = c.execute(
                    """SELECT 1 FROM cameras cam JOIN devices d ON d.id=cam.device_id
                    WHERE cam.id=? AND d.id=? AND d.revoked_at IS NULL AND d.token_hash=?""",
                    (talk_camera_id, talk_camera_id, sha(password)),
                ).fetchone()
                if row:
                    return {"status": "ok"}
            raise HTTPException(401, "Not authorized")
        if not path.startswith("cam/") or not camera_id or "/" in camera_id:
            raise HTTPException(401, "Not authorized")
        if action == "publish":
            row = c.execute(
                "SELECT token_hash,revoked_at FROM devices WHERE id=?", (camera_id,)
            ).fetchone()
            if (
                row
                and row["revoked_at"] is None
                and secrets.compare_digest(row["token_hash"], sha(password))
            ):
                return {"status": "ok"}
        if (action == "read" and username == "motion-worker" and WORKER_TOKEN
                and secrets.compare_digest(password, WORKER_TOKEN)):
            camera = c.execute(
                "SELECT d.revoked_at FROM cameras cam JOIN devices d ON d.id=cam.device_id "
                "WHERE cam.id=?", (camera_id,),
            ).fetchone()
            if camera and camera["revoked_at"] is None:
                return {"status": "ok"}
        if action == "read" and username == "viewer" and password:
            row = c.execute(
                """SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id
              JOIN cameras cam ON cam.owner_id=u.id WHERE s.token_hash=? AND s.expires_at>?
              AND cam.id=? AND u.role IN ('admin','viewer')""",
                (sha(password), now(), camera_id),
            ).fetchone()
            if row:
                return {"status": "ok"}
    raise HTTPException(401, "Not authorized")
