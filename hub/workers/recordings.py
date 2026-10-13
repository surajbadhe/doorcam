import os
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime
from pathlib import Path

DB_PATH = os.getenv("DATABASE_PATH", "/data/doorcam.sqlite3")
ROOT = Path(os.getenv("RECORDINGS_PATH", "/recordings"))
DEFAULT_DAYS = max(1, int(os.getenv("RETENTION_DEFAULT_DAYS", "7")))
FREE_LIMIT = max(1, min(50, int(os.getenv("RETENTION_FREE_PERCENT", "10"))))
SEGMENT_SECONDS = 60


def connect():
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=15000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def segment_start(path: Path, mtime: float) -> int:
    try:
        return int(datetime.strptime(path.stem, "%Y-%m-%d_%H-%M-%S-%f").timestamp())
    except ValueError:
        return max(0, int(mtime) - SEGMENT_SECONDS)


def index_completed():
    if not ROOT.exists():
        return
    current_time = time.time()
    with closing(connect()) as conn, conn:
        cameras = {row["id"] for row in conn.execute("SELECT id FROM cameras")}
        for path in ROOT.glob("cam/*/*.mp4"):
            try:
                stat = path.stat()
                # MediaMTX writes the active fragment; only index a closed, settled segment.
                if current_time - stat.st_mtime < 8:
                    continue
                camera_id = path.parent.name
                if camera_id not in cameras:
                    continue
                started = segment_start(path, stat.st_mtime)
                ended = max(started + 1, int(stat.st_mtime))
                conn.execute(
                    "INSERT OR IGNORE INTO recordings "
                    "(id,camera_id,started_at,ended_at,path,size_bytes,created_at) "
                    "VALUES(?,?,?,?,?,?,?)",
                    (uuid.uuid4().hex, camera_id, started, ended, str(path.resolve()),
                     stat.st_size, int(current_time)),
                )
                conn.execute(
                    "UPDATE recordings SET size_bytes=? WHERE path=?",
                    (stat.st_size, str(path.resolve())),
                )
                conn.execute(
                    "UPDATE events SET recording_id=(SELECT id FROM recordings WHERE camera_id=? "
                    "AND started_at<=events.ts AND ended_at>=events.ts ORDER BY started_at DESC LIMIT 1) "
                    "WHERE camera_id=? AND recording_id IS NULL AND ts BETWEEN ? AND ?",
                    (camera_id, camera_id, started, ended),
                )
            except (FileNotFoundError, PermissionError, sqlite3.Error):
                continue


def apply_retention():
    with closing(connect()) as conn, conn:
        rows = conn.execute(
            "SELECT r.*,COALESCE(cam.retention_days,?) AS retention_days "
            "FROM recordings r JOIN cameras cam ON cam.id=r.camera_id ORDER BY r.started_at",
            (DEFAULT_DAYS,),
        ).fetchall()
        cutoff = int(time.time())
        free_percent = shutil_free_percent(ROOT)
        for row in rows:
            path = Path(row["path"])
            expired = row["started_at"] < cutoff - row["retention_days"] * 86400
            low_disk = free_percent is not None and free_percent < FREE_LIMIT
            if not expired and not low_disk:
                continue
            try:
                # Recent files may still be open by MediaMTX, so never remove them.
                if path.exists() and time.time() - path.stat().st_mtime < 90:
                    continue
                path.unlink(missing_ok=True)
                conn.execute("DELETE FROM recordings WHERE id=?", (row["id"],))
                free_percent = shutil_free_percent(ROOT)
            except (FileNotFoundError, PermissionError, sqlite3.Error):
                continue


def shutil_free_percent(path: Path):
    import shutil

    try:
        usage = shutil.disk_usage(path if path.exists() else path.parent)
        return usage.free * 100 / usage.total
    except OSError:
        return None


def main():
    while True:
        index_completed()
        apply_retention()
        time.sleep(10)


if __name__ == "__main__":
    main()
