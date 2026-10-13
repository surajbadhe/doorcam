import io
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app import main


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", str(tmp_path / "test.sqlite3"))
    main.init_db()
    with main.db() as conn:
        conn.execute(
            "INSERT INTO users VALUES(?,?,?,?,?)",
            ("owner-1", "owner", main.ph.hash("test-password-123"), "admin", int(time.time())),
        )
        conn.execute(
            "INSERT INTO users VALUES(?,?,?,?,?)",
            ("viewer-1", "viewer", main.ph.hash("viewer-password-123"), "viewer", int(time.time())),
        )
    with TestClient(main.app) as test_client:
        yield test_client


def sign_in(client, username="owner", password="test-password-123"):
    response = client.post("/api/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200
    return response.json()


def test_refresh_tokens_are_rotated_and_access_token_is_not_refresh(client):
    tokens = sign_in(client)
    assert (
        client.post("/api/auth/refresh", json={"refresh_token": tokens["access_token"]}).status_code
        == 401
    )
    refreshed = client.post("/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 200
    assert (
        client.post(
            "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
        ).status_code
        == 401
    )


def test_pair_code_is_single_use_and_expires(client):
    tokens = sign_in(client)
    pair = client.post(
        "/api/pairing/start",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
        json={"name": "Front door"},
    ).json()
    body = {"code": pair["code"], "name": "Front door"}
    assert client.post("/api/pairing/exchange", json=body).status_code == 200
    assert client.post("/api/pairing/exchange", json=body).status_code == 401

    expired = client.post(
        "/api/pairing/start",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
        json={"name": "Expired"},
    ).json()
    with main.db() as conn:
        conn.execute(
            "UPDATE pairing_codes SET expires_at=? WHERE code_hash=?",
            (int(time.time()) - 1, main.sha(expired["code"])),
        )
    assert (
        client.post(
            "/api/pairing/exchange", json={"code": expired["code"], "name": "Expired"}
        ).status_code
        == 401
    )


def test_pair_exchange_is_rate_limited(client):
    for _ in range(20):
        response = client.post(
            "/api/pairing/exchange", json={"code": uuid.uuid4().hex, "name": "Nope"}
        )
        assert response.status_code == 401
    assert (
        client.post(
            "/api/pairing/exchange", json={"code": uuid.uuid4().hex, "name": "Nope"}
        ).status_code
        == 429
    )


def test_media_auth_scopes_publisher_and_viewer_and_revoke(client, monkeypatch):
    owner = sign_in(client)
    viewer = sign_in(client, "viewer", "viewer-password-123")
    pairing = client.post(
        "/api/pairing/start",
        headers={"Authorization": f"Bearer {owner['access_token']}"},
        json={"name": "Front door"},
    ).json()
    camera = client.post(
        "/api/pairing/exchange", json={"code": pairing["code"], "name": "Front door"}
    ).json()
    path = camera["path"]
    auth_url = "/internal/mediamtx/auth"

    good_publish = {
        "action": "publish",
        "path": path,
        "user": camera["camera_id"],
        "password": camera["device_token"],
    }
    assert client.post(auth_url, json=good_publish).status_code == 200
    wrong_path = {**good_publish, "path": "cam/someone-elses-camera"}
    assert client.post(auth_url, json=wrong_path).status_code == 401
    assert client.post(auth_url, json={**good_publish, "password": "invalid"}).status_code == 401

    good_read = {
        "action": "read",
        "path": path,
        "user": "viewer",
        "password": owner["access_token"],
    }
    assert client.post(auth_url, json=good_read).status_code == 200
    assert (
        client.post(auth_url, json={**good_read, "password": viewer["access_token"]}).status_code
        == 401
    )
    assert client.post(auth_url, json={**good_read, "path": "cam/not-owned"}).status_code == 401

    kicked = []

    class Response(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *args):
            self.close()

    def fake_urlopen(request, timeout=3):
        url = request.full_url if hasattr(request, "full_url") else request
        if url.endswith("/sessions/list"):
            return Response(
                f'{{"items":[{{"id":"stream-1","path":"cam/{camera["camera_id"]}"}}]}}'.encode()
            )
        kicked.append(url)
        return Response(b"{}")

    monkeypatch.setattr(main.urllib.request, "urlopen", fake_urlopen)
    revoked = client.post(
        f"/api/devices/{camera['camera_id']}/revoke",
        headers={"Authorization": f"Bearer {owner['access_token']}"},
    )
    assert revoked.status_code == 200
    assert revoked.json()["sessions_kicked"] == 2
    assert any(url.endswith("/v3/rtsp/sessions/kick/stream-1") for url in kicked)
    assert any(url.endswith("/v3/webrtc/sessions/kick/stream-1") for url in kicked)
    assert client.post(auth_url, json=good_publish).status_code == 401


def test_login_is_rate_limited(client):
    for _ in range(10):
        assert (
            client.post(
                "/api/auth/login", json={"username": "owner", "password": "wrong"}
            ).status_code
            == 401
        )
    assert (
        client.post("/api/auth/login", json={"username": "owner", "password": "wrong"}).status_code
        == 429
    )
