"""Integration checks against the actual Docker stack. No real user data."""
import json
import os
import urllib.error
import urllib.request
import uuid
import time

BASE = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:8088")


def call(path, body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    assert status == expected, (path, status, data[:500])
    return json.loads(data) if data else {}


for attempt in range(30):
    try:
        call("/healthz")
        break
    except (urllib.error.URLError, AssertionError):
        time.sleep(1)
else:
    raise RuntimeError("Edge proxy did not become ready")

suffix = uuid.uuid4().hex[:10]
login, username, password = "login_" + suffix, "user_" + suffix, "Test-" + uuid.uuid4().hex
body = {"login": login, "username": username, "password": password}
created = call("/api/register", body, expected=201)
assert created["user_id"].startswith("@" + username + ":")
call("/api/register", body, expected=409)
call("/api/register", {**body, "login": login + "x"}, expected=409)
call("/api/login", {"login": login, "password": "incorrect-password"}, expected=401)
session = call("/api/login", {"login": login, "password": password})
assert session["user_id"] == created["user_id"]
found = call("/api/users/" + username, token=session["access_token"])
assert found["user_id"] == created["user_id"] and "login" not in found
call("/api/users/" + username, expected=401)
response = call("/api/register", {"login": "bad", "username": [], "password": "do-not-echo-this-password"}, expected=400)
assert "do-not-echo" not in json.dumps(response)
call("/_synapse/admin/v1/server_version", expected=404)
print("PASS: registration, uniqueness, login, private login separation, authentication, validation redaction, blocked admin routes")

# Canonical direct rooms must be shared by both accounts, including concurrent first contact.
from concurrent.futures import ThreadPoolExecutor
bob_name = "bob_" + suffix
bob_login = "bob_login_" + suffix
bob = call("/api/register", {"login": bob_login, "username": bob_name, "password": password}, expected=201)
bob_session = call("/api/login", {"login": bob_login, "password": password})
call("/api/chats", {"username": bob_name}, expected=401)
requests = [(session, bob_name), (bob_session, username)] * 3
with ThreadPoolExecutor(max_workers=6) as executor:
    rooms = list(executor.map(lambda pair: call("/api/chats", {"username": pair[1]}, token=pair[0]["access_token"]), requests))
room_ids = {room["room_id"] for room in rooms}
assert len(room_ids) == 1, "Concurrent first contact created duplicate rooms"
room_id = next(iter(room_ids))
from urllib.parse import quote
room_path = "/_matrix/client/v3/rooms/" + quote(room_id, safe="")
# Either caller may win the race; the other remains invited until opening the chat.
alice_joined = room_id in call("/_matrix/client/v3/joined_rooms", token=session["access_token"])["joined_rooms"]
creator, invitee = (session, bob_session) if alice_joined else (bob_session, session)
call(room_path + "/members", token=invitee["access_token"], expected=403)
encryption = call(room_path + "/state/m.room.encryption", token=creator["access_token"])
assert encryption["algorithm"] == "m.megolm.v1.aes-sha2"
call("/_matrix/client/v3/join/" + quote(room_id, safe=""), {}, token=invitee["access_token"])
assert call("/api/chats", {"username": username}, token=bob_session["access_token"])["room_id"] == room_id

# Adopt a valid legacy two-person encrypted room, never a room belonging to another pair.
carol_name = "carol_" + suffix
carol = call("/api/register", {"login": "carol_login_" + suffix, "username": carol_name, "password": password}, expected=201)
old = call("/_matrix/client/v3/createRoom", {
    "is_direct": True, "preset": "private_chat", "invite": [carol["user_id"]],
    "initial_state": [{"type": "m.room.encryption", "state_key": "", "content": {"algorithm": "m.megolm.v1.aes-sha2"}}]
}, token=session["access_token"])["room_id"]
assert call("/api/chats", {"username": carol_name, "candidate_room_ids": [old]}, token=session["access_token"])["room_id"] == old
other = call("/api/chats", {"username": carol_name, "candidate_room_ids": [room_id]}, token=bob_session["access_token"])["room_id"]
assert other not in (old, room_id), "An unrelated room must never be adopted"
print("PASS: concurrent canonical DMs, encrypted room state, invite permissions, legacy adoption and pair validation")

# Current clients derive separate authentication and recovery secrets on the phone.
import base64
import hashlib
import hmac

def derived(password, salt_text, name):
    raw = base64.urlsafe_b64decode(salt_text + "=")
    salt = hashlib.sha256(raw + ("PrivateMessenger/password-v2\n" + "ci.invalid" + "\n" + name).encode()).digest()
    master = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600000, 32)
    return "pm2_" + base64.urlsafe_b64encode(hmac.digest(master, b"PrivateMessenger/authentication/v2", "sha256")).decode().rstrip("=")

new_salt = base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")
new_password = "New-" + uuid.uuid4().hex
new_auth = derived(new_password, new_salt, login)
call("/api/auth/upgrade", {"old_password": password, "new_auth_secret": new_auth, "salt": new_salt}, token=session["access_token"])
params = call("/api/auth/parameters", {"login": login})
assert params["scheme"] == "pm-password-v2" and new_salt in params["salts"]
modern = call("/api/login", {"login": login, "password": new_auth, "credential_version": 2, "credential_salt": new_salt})
assert modern["user_id"] == session["user_id"]
assert call("/api/auth/account", token=modern["access_token"])["credential_version"] == 2
call("/api/login", {"login": login, "password": password}, expected=401)
print("PASS: legacy credential migration, password-derived login and no automatic raw-password downgrade")
