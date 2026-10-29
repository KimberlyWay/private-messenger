import asyncio
import base64
from collections import OrderedDict, deque
from contextlib import asynccontextmanager
import hashlib
import hmac
import os
import re
import time

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, SecretStr, Field
from psycopg.errors import UniqueViolation
from psycopg_pool import AsyncConnectionPool

SYNAPSE = os.environ.get("SYNAPSE_URL", "http://synapse:8008")
SERVER_NAME = os.environ["SERVER_NAME"]
PUBLIC_URL = os.environ["PUBLIC_BASE_URL"].rstrip("/")
SECRET = os.environ["REGISTRATION_SHARED_SECRET"]
LOGIN_RE = re.compile(r"[a-z0-9][a-z0-9_.-]{2,63}\Z")
USERNAME_RE = re.compile(r"[a-z0-9][a-z0-9_]{2,31}\Z")
pool = AsyncConnectionPool(os.environ["GATEWAY_DATABASE_URL"], open=False, min_size=1, max_size=5)
http = httpx.AsyncClient(base_url=SYNAPSE, timeout=30, follow_redirects=False)
attempts = OrderedDict()
rate_lock = asyncio.Lock()


@asynccontextmanager
async def lifespan(app):
    await pool.open()
    await pool.wait()
    async with pool.connection() as connection:
        await connection.execute("""CREATE TABLE IF NOT EXISTS accounts (
            login TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
            matrix_id TEXT UNIQUE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())""")
        await connection.execute("""CREATE TABLE IF NOT EXISTS direct_chats (
            user_low TEXT NOT NULL, user_high TEXT NOT NULL, room_id TEXT,
            PRIMARY KEY(user_low, user_high), CHECK(user_low < user_high))""")
        await connection.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS credential_version INTEGER NOT NULL DEFAULT 1")
        await connection.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS credential_salt TEXT")
        await connection.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS pending_salt TEXT")
        await connection.execute("ALTER TABLE direct_chats ADD COLUMN IF NOT EXISTS cloud_room_id TEXT")
    yield
    await http.aclose()
    await pool.close()


app = FastAPI(title="Private Messenger accounts", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


class Credentials(BaseModel):
    login: str
    password: SecretStr
    credential_version: int = 1
    credential_salt: str | None = None


class Registration(Credentials):
    username: str


@app.exception_handler(RequestValidationError)
async def validation_error(request, error):
    # Never echo password input in validation responses.
    return JSONResponse({"detail": "Проверьте поля запроса"}, status_code=400)


def normalize(value, pattern):
    value = value.strip().lower()
    if not pattern.fullmatch(value):
        raise HTTPException(400, "Логин: 3–64 символа a-z, 0-9, _, -, .; username: 3–32 символа a-z, 0-9, _")
    return value


def password_value(password):
    value = password.get_secret_value()
    if len(value) < 12 or len(value.encode("utf-8")) > 72 or "\0" in value:
        raise HTTPException(400, "Пароль: минимум 12 символов и не более 72 байт UTF-8")
    return value


async def rate_limit(request, action, limit):
    # Gateway has no published port; the edge proxy always overwrites X-Real-IP.
    ip = request.headers.get("x-real-ip", request.client.host)
    key = (action, ip)
    now = time.monotonic()
    async with rate_lock:
        queue = attempts.setdefault(key, deque())
        while queue and queue[0] < now - 600:
            queue.popleft()
        if len(queue) >= limit:
            raise HTTPException(429, "Слишком много попыток. Подождите несколько минут", headers={"Retry-After": "600"})
        queue.append(now)
        attempts.move_to_end(key)
        while len(attempts) > 4096:
            attempts.popitem(last=False)


async def matrix_login(username, password):
    try:
        response = await http.post("/_matrix/client/v3/login", json={
            "type": "m.login.password", "identifier": {"type": "m.id.user", "user": username},
            "password": password, "initial_device_display_name": "Private Messenger Android"})
    except httpx.HTTPError:
        raise HTTPException(503, "Сервер сообщений временно недоступен") from None
    if response.status_code != 200:
        if response.status_code == 429:
            raise HTTPException(429, "Слишком много попыток входа")
        raise HTTPException(401, "Неверный логин или пароль")
    result = response.json()
    return {"access_token": result["access_token"], "device_id": result["device_id"],
            "user_id": result["user_id"], "homeserver": PUBLIC_URL}


@app.get("/healthz")
async def health():
    return {"status": "ok"}


@app.get("/api/config")
async def config():
    return {"server_name": SERVER_NAME, "public_base_url": PUBLIC_URL,
            "chat_mode": "cloud-v1", "registration": True, "max_upload_bytes": 64 * 1024 * 1024}


@app.post("/api/register", status_code=201)
async def register(body: Registration, request: Request):
    await rate_limit(request, "register", int(os.environ.get("REGISTRATION_LIMIT", "5")))
    login = normalize(body.login, LOGIN_RE)
    username = normalize(body.username.removeprefix("@"), USERNAME_RE)
    password = password_value(body.password)
    if body.credential_version not in (1, 2):
        raise HTTPException(400, "Неизвестная версия авторизации")
    if body.credential_version == 2:
        validate_derived(password, body.credential_salt)
    matrix_id = f"@{username}:{SERVER_NAME}"
    try:
        async with pool.connection() as connection:
            async with connection.transaction():
                # Reserve both unique names before creating the Matrix account.
                await connection.execute("INSERT INTO accounts(login, username, matrix_id, credential_version, credential_salt) VALUES (%s,%s,%s,%s,%s)",
                                         (login, username, matrix_id, body.credential_version, body.credential_salt))
                nonce_response = await http.get("/_synapse/admin/v1/register")
                nonce_response.raise_for_status()
                nonce = nonce_response.json()["nonce"]
                data = "\0".join([nonce, username, password, "notadmin"])
                mac = hmac.new(SECRET.encode(), data.encode(), hashlib.sha1).hexdigest()
                response = await http.post("/_synapse/admin/v1/register", json={
                    "nonce": nonce, "username": username, "password": password, "admin": False,
                    "mac": mac, "inhibit_login": True})
                if response.status_code == 400 and response.json().get("errcode") == "M_USER_IN_USE":
                    raise HTTPException(409, "Логин или username уже занят")
                response.raise_for_status()
    except UniqueViolation:
        raise HTTPException(409, "Логин или username уже занят") from None
    except httpx.HTTPError:
        raise HTTPException(503, "Регистрация временно недоступна. Попробуйте позже") from None
    return {"username": username, "user_id": matrix_id}


@app.post("/api/login")
async def login(body: Credentials, request: Request):
    await rate_limit(request, "login", int(os.environ.get("LOGIN_LIMIT", "20")))
    key = normalize(body.login, LOGIN_RE)
    # Do not enforce the creation password policy on login.
    password = body.password.get_secret_value()
    if not password or len(password.encode("utf-8")) > 128:
        raise HTTPException(401, "Неверный логин или пароль")
    async with pool.connection() as connection:
        row = await (await connection.execute("SELECT username,credential_version,credential_salt,pending_salt FROM accounts WHERE login=%s", (key,))).fetchone()
    if row is None:
        # Avoid an immediate timing oracle for private login names.
        await asyncio.sleep(0.3)
        raise HTTPException(401, "Неверный логин или пароль")
    if body.credential_version == 2:
        validate_derived(password, body.credential_salt)
        if body.credential_salt not in (row[2], row[3]):
            raise HTTPException(401, "Неверный логин или пароль")
    elif row[1] == 2:
        raise HTTPException(401, "Для этого аккаунта нужен актуальный клиент")
    result = await matrix_login(row[0], password)
    if body.credential_version == 2:
        async with pool.connection() as connection:
            await connection.execute("UPDATE accounts SET credential_version=2,credential_salt=%s,pending_salt=NULL WHERE login=%s AND (credential_salt=%s OR pending_salt=%s)",
                                     (body.credential_salt, key, body.credential_salt, body.credential_salt))
    result["credential_version"] = body.credential_version
    result["server_name"] = SERVER_NAME
    return result


@app.get("/api/users/{username}")
async def find_user(username: str, request: Request):
    await rate_limit(request, "lookup", 120)
    authorization = request.headers.get("authorization", "")
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Требуется вход")
    try:
        response = await http.get("/_matrix/client/v3/account/whoami", headers={"Authorization": authorization})
    except httpx.HTTPError:
        raise HTTPException(503, "Сервер временно недоступен") from None
    if response.status_code != 200:
        raise HTTPException(401, "Сеанс истёк")
    name = normalize(username.removeprefix("@"), USERNAME_RE)
    async with pool.connection() as connection:
        row = await (await connection.execute("SELECT matrix_id FROM accounts WHERE username=%s", (name,))).fetchone()
    if row is None:
        raise HTTPException(404, "Пользователь не найден")
    return {"username": name, "user_id": row[0]}


class DirectChatRequest(BaseModel):
    username: str
    candidate_room_ids: list[str] = Field(default_factory=list, max_length=8)


async def authenticated_user(request):
    authorization = request.headers.get("authorization", "")
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Требуется вход")
    try:
        response = await http.get("/_matrix/client/v3/account/whoami", headers={"Authorization": authorization})
    except httpx.HTTPError:
        raise HTTPException(503, "Сервер временно недоступен") from None
    if response.status_code != 200:
        raise HTTPException(401, "Сеанс истёк")
    return response.json()["user_id"], {"Authorization": authorization}


async def reusable_dm(room_id, own, peer, headers):
    """Only adopt encrypted, joined, two-party rooms. Never trust a supplied ID."""
    from urllib.parse import quote
    if not room_id.startswith("!") or len(room_id) > 512:
        return False
    path = "/_matrix/client/v3/rooms/" + quote(room_id, safe="")
    members = await http.get(path + "/members", headers=headers)
    if members.status_code != 200:
        return False
    active = {event["state_key"]: event.get("content", {}).get("membership")
              for event in members.json().get("chunk", [])
              if event.get("content", {}).get("membership") in ("join", "invite")}
    if set(active) != {own, peer} or active.get(own) != "join":
        return False
    encryption = await http.get(path + "/state/m.room.encryption", headers=headers)
    return encryption.status_code == 404


@app.post("/api/chats")
async def direct_chat(body: DirectChatRequest, request: Request):
    """One canonical room per pair, even for concurrent requests from both sides."""
    from urllib.parse import quote
    await rate_limit(request, "chat", 120)
    own, headers = await authenticated_user(request)
    name = normalize(body.username.removeprefix("@"), USERNAME_RE)
    try:
        async with pool.connection() as connection:
            async with connection.transaction():
                own_account = await (await connection.execute("SELECT 1 FROM accounts WHERE matrix_id=%s", (own,))).fetchone()
                target = await (await connection.execute("SELECT matrix_id FROM accounts WHERE username=%s", (name,))).fetchone()
                if not own_account or not target:
                    raise HTTPException(404, "Пользователь не найден")
                peer = target[0]
                if own == peer:
                    raise HTTPException(400, "Выберите другого пользователя")
                low, high = sorted((own, peer))
                await connection.execute("INSERT INTO direct_chats(user_low,user_high) VALUES (%s,%s) ON CONFLICT DO NOTHING", (low, high))
                row = await (await connection.execute("SELECT cloud_room_id FROM direct_chats WHERE user_low=%s AND user_high=%s FOR UPDATE", (low, high))).fetchone()
                room_id = row[0]
                if room_id is None:
                    # A deterministic, secret-derived alias also closes the crash window
                    # between Synapse creating a room and PostgreSQL committing its ID.
                    local_alias = "pm_" + hmac.new(SECRET.encode(), ("cloud-direct-v1\0" + low + "\0" + high).encode(), hashlib.sha256).hexdigest()
                    alias = "#" + local_alias + ":" + SERVER_NAME
                    directory = await http.get("/_matrix/client/v3/directory/room/" + quote(alias, safe=""), headers=headers)
                    if directory.status_code == 200:
                        recovered_id = directory.json()["room_id"]
                        if not await reusable_dm(recovered_id, own, peer, headers):
                            # The other participant may be invited and cannot read members yet.
                            joined = await http.post("/_matrix/client/v3/join/" + quote(recovered_id, safe=""), headers=headers, json={})
                            if joined.status_code != 200 or not await reusable_dm(recovered_id, own, peer, headers):
                                raise HTTPException(409, "Не удалось восстановить существующий личный чат")
                        room_id = recovered_id
                    elif directory.status_code != 404:
                        directory.raise_for_status()
                    if room_id is None:
                        for candidate in sorted(set(body.candidate_room_ids)):
                            if await reusable_dm(candidate, own, peer, headers):
                                room_id = candidate
                                break
                    if room_id is None:
                        created = await http.post("/_matrix/client/v3/createRoom", headers=headers, json={
                            "room_alias_name": local_alias,
                            "is_direct": True, "visibility": "private", "preset": "private_chat", "invite": [peer],
                            "initial_state": [{"type": "m.room.history_visibility", "state_key": "",
                                               "content": {"history_visibility": "shared"}}]})
                        created.raise_for_status()
                        room_id = created.json()["room_id"]
                    await connection.execute("UPDATE direct_chats SET cloud_room_id=%s WHERE user_low=%s AND user_high=%s", (room_id, low, high))
                return {"room_id": room_id, "user_id": peer}
    except httpx.HTTPError:
        raise HTTPException(503, "Не удалось открыть личный чат. Попробуйте ещё раз; новый чат не будет создан повторно") from None


# v2 clients never send their human password. Synapse authenticates a domain-separated
# derived secret, which cannot be used as the key for the encrypted recovery envelope.
def validate_derived(secret, salt):
    if not re.fullmatch(r"pm2_[A-Za-z0-9_-]{43}", secret) or not isinstance(salt, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", salt):
        raise HTTPException(400, "Некорректные параметры защищённого входа")


class AuthParameters(BaseModel):
    login: str


@app.post("/api/auth/parameters")
async def auth_parameters(body: AuthParameters, request: Request):
    await rate_limit(request, "auth_parameters", 60)
    login = normalize(body.login, LOGIN_RE)
    async with pool.connection() as connection:
        row = await (await connection.execute("SELECT credential_salt,pending_salt FROM accounts WHERE login=%s", (login,))).fetchone()
    # Unknown/legacy accounts get a stable fake salt rather than a raw-password downgrade.
    fake = base64.urlsafe_b64encode(hmac.new(SECRET.encode(), ("auth-parameters\0" + login).encode(), hashlib.sha256).digest()).decode().rstrip("=")
    salts = [row[0] if row and row[0] else fake]
    if row and row[1] and row[1] not in salts:
        salts.append(row[1])
    return {"scheme": "pm-password-v2", "server_name": SERVER_NAME, "salts": salts}


class UpgradeCredentials(BaseModel):
    old_password: SecretStr
    new_auth_secret: SecretStr
    salt: str


@app.post("/api/auth/upgrade")
async def upgrade_credentials(body: UpgradeCredentials, request: Request):
    await rate_limit(request, "upgrade", 10)
    own, headers = await authenticated_user(request)
    new_auth = body.new_auth_secret.get_secret_value()
    validate_derived(new_auth, body.salt)
    old_password = body.old_password.get_secret_value()
    if not old_password or len(old_password.encode()) > 128:
        raise HTTPException(400, "Проверьте текущий пароль")
    lock_key = int.from_bytes(hashlib.sha256(("upgrade:" + own).encode()).digest()[:8], "big", signed=True)
    try:
        async with pool.connection() as connection:
            await connection.execute("SELECT pg_advisory_lock(%s)", (lock_key,))
            await connection.commit()
            try:
                row = await (await connection.execute("SELECT credential_version FROM accounts WHERE matrix_id=%s", (own,))).fetchone()
                if not row:
                    raise HTTPException(404, "Аккаунт не найден")
                if row[0] == 2:
                    raise HTTPException(409, "Защищённый вход уже настроен")
                # Commit the pending salt BEFORE changing the Synapse password.
                # A login can finish this migration if the process dies afterwards.
                await connection.execute("UPDATE accounts SET pending_salt=%s WHERE matrix_id=%s", (body.salt, own))
                await connection.commit()
                response = await http.post("/_matrix/client/v3/account/password", headers=headers, json={
                    "new_password": new_auth, "logout_devices": False,
                    "auth": {"type": "m.login.password", "identifier": {"type": "m.id.user", "user": own}, "password": old_password}})
                if response.status_code != 200:
                    raise HTTPException(401, "Не удалось подтвердить текущий пароль")
                await connection.execute("UPDATE accounts SET credential_version=2,credential_salt=%s,pending_salt=NULL WHERE matrix_id=%s", (body.salt, own))
                await connection.commit()
            finally:
                await connection.rollback()
                await connection.execute("SELECT pg_advisory_unlock(%s)", (lock_key,))
                await connection.commit()
        return {"credential_version": 2, "server_name": SERVER_NAME}
    except httpx.HTTPError:
        raise HTTPException(503, "Перенос не завершён. Повторите вход новым паролем или завершите настройку на этом телефоне") from None

@app.get("/api/auth/account")
async def own_auth_account(request: Request):
    own, _ = await authenticated_user(request)
    async with pool.connection() as connection:
        row = await (await connection.execute("SELECT login,credential_version,credential_salt FROM accounts WHERE matrix_id=%s", (own,))).fetchone()
    if not row:
        raise HTTPException(404, "Аккаунт не найден")
    return {"login": row[0], "credential_version": row[1], "salt": row[2], "server_name": SERVER_NAME}
