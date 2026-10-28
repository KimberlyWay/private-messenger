import asyncio
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
from pydantic import BaseModel, SecretStr
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
    yield
    await http.aclose()
    await pool.close()


app = FastAPI(title="Private Messenger accounts", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


class Credentials(BaseModel):
    login: str
    password: SecretStr


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
            "registration": True, "max_upload_bytes": 64 * 1024 * 1024}


@app.post("/api/register", status_code=201)
async def register(body: Registration, request: Request):
    await rate_limit(request, "register", int(os.environ.get("REGISTRATION_LIMIT", "5")))
    login = normalize(body.login, LOGIN_RE)
    username = normalize(body.username.removeprefix("@"), USERNAME_RE)
    password = password_value(body.password)
    matrix_id = f"@{username}:{SERVER_NAME}"
    try:
        async with pool.connection() as connection:
            async with connection.transaction():
                # Reserve both unique names before creating the Matrix account.
                await connection.execute("INSERT INTO accounts(login, username, matrix_id) VALUES (%s,%s,%s)",
                                         (login, username, matrix_id))
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
    await rate_limit(request, "login", 20)
    key = normalize(body.login, LOGIN_RE)
    # Do not enforce the creation password policy on login.
    password = body.password.get_secret_value()
    if not password or len(password.encode("utf-8")) > 128:
        raise HTTPException(401, "Неверный логин или пароль")
    async with pool.connection() as connection:
        row = await (await connection.execute("SELECT username FROM accounts WHERE login=%s", (key,))).fetchone()
    if row is None:
        # Avoid an immediate timing oracle for private login names.
        await asyncio.sleep(0.3)
        raise HTTPException(401, "Неверный логин или пароль")
    return await matrix_login(row[0], password)


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
