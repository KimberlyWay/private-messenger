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
