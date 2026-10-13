import argparse
import getpass
import os
import sqlite3
import uuid

from argon2 import PasswordHasher

from app.main import DB_PATH, init_db


def main():
    parser = argparse.ArgumentParser(description="DoorCam Hub administration")
    parser.add_argument("command", choices=["create-admin"])
    parser.add_argument("--username")
    args = parser.parse_args()
    username = args.username or input("Admin username: ").strip()
    password = getpass.getpass("Admin password (at least 12 characters): ")
    if len(password) < 12:
        raise SystemExit("Password must contain at least 12 characters")
    init_db()
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    c = sqlite3.connect(DB_PATH)
    try:
        c.execute(
            "INSERT INTO users(id,username,password_hash,role,created_at) "
            "VALUES(?,?,?,?,strftime('%s','now'))",
            (uuid.uuid4().hex, username, PasswordHasher().hash(password), "admin"),
        )
        c.commit()
    except sqlite3.IntegrityError:
        raise SystemExit("That username already exists")
    finally:
        c.close()
    print(f"Administrator {username!r} created")


if __name__ == "__main__":
    main()
