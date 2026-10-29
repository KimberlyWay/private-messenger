#!/usr/bin/env python3
"""Generate local deployment configuration. No production secrets are committed."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent


def configure(domain, url, test=False, port=8088):
    if len(domain) > 253 or "." not in domain or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in domain.split(".")):
        raise ValueError("Use a lowercase DNS server name without a port")
    if not 1 <= port <= 65535:
        raise ValueError("Port must be between 1 and 65535")
    parsed = urlsplit(url)
    if parsed.scheme != ("http" if test else "https") or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ValueError("Use a base HTTPS URL, optionally with a port")
    if (ROOT / ".env").exists():
        raise ValueError(".env already exists. Back up and edit configuration; do not regenerate an existing server identity")
    generated = ROOT / "generated"
    generated.mkdir(mode=0o700, exist_ok=True)
    os.chmod(generated, 0o700)
    admin, synapse, gateway, registration, macaroon, form = [secrets.token_hex(32) for _ in range(6)]
    values = {"SERVER_NAME": domain, "PUBLIC_BASE_URL": url.rstrip("/"), "BIND_PORT": str(port),
              "HTTPS_PORT": str(parsed.port or 443), "POSTGRES_ADMIN_PASSWORD": admin,
              "SYNAPSE_DATABASE_PASSWORD": synapse, "GATEWAY_DATABASE_PASSWORD": gateway,
              "REGISTRATION_SHARED_SECRET": registration, "REGISTRATION_LIMIT": "1000" if test else "5", "LOGIN_LIMIT": "1000" if test else "20"}
    env = ROOT / ".env"
    fd = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as file:
        file.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    config = {
        "server_name": domain, "public_baseurl": url.rstrip("/") + "/", "pid_file": "/data/homeserver.pid",
        "listeners": [{"port": 8008, "tls": False, "type": "http", "x_forwarded": True,
                       "bind_addresses": ["0.0.0.0"], "resources": [{"names": ["client"], "compress": False}]}],
        "database": {"name": "psycopg2", "args": {"user": "synapse", "password": synapse,
                       "database": "synapse", "host": "db", "port": 5432, "cp_min": 2, "cp_max": 5}},
        "log_config": "/config/log.config", "media_store_path": "/data/media_store", "max_upload_size": "64M",
        "signing_key_path": f"/data/{domain}.signing.key", "registration_shared_secret": registration,
        "macaroon_secret_key": macaroon, "form_secret": form, "enable_registration": False,
        "allow_guest_access": False, "report_stats": False, "enable_metrics": False,
        "url_preview_enabled": False, "trusted_key_servers": [], "federation_domain_whitelist": [],
        "suppress_key_server_warning": True, "password_config": {"enabled": True},
        "rc_login": {"address": {"per_second": 100 if test else 0.2, "burst_count": 1000 if test else 30},
                     "account": {"per_second": 100 if test else 0.1, "burst_count": 1000 if test else 10}},
    }
    # JSON is also valid YAML; using stdlib avoids an installer dependency.
    (generated / "homeserver.yaml").write_text(json.dumps(config, indent=2) + "\n")
    (generated / "init.sql").write_text(
        f"CREATE USER synapse WITH PASSWORD '{synapse}';\n"
        "CREATE DATABASE synapse OWNER synapse ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0;\n"
        f"CREATE USER gateway WITH PASSWORD '{gateway}';\n"
        "CREATE DATABASE gateway OWNER gateway ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0;\n")
    (generated / "log.config").write_text(json.dumps({
        "version": 1, "formatters": {"brief": {"format": "%(asctime)s %(levelname)s %(name)s: %(message)s"}},
        "handlers": {"console": {"class": "logging.StreamHandler", "formatter": "brief"}},
        "loggers": {"synapse.http.site": {"level": "WARNING"}},
        "root": {"level": "WARNING", "handlers": ["console"]}}))
    # Files mounted directly must be readable by non-root container users.
    # Their containing host directory remains private (0700).
    for file in generated.iterdir():
        os.chmod(file, 0o644)
    print(f"Configuration created for {domain}. Secrets are in server/.env and server/generated; keep them private.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--test", action="store_true", help="CI only: allow HTTP and a higher registration limit")
    args = parser.parse_args()
    configure(args.domain, args.url, args.test, args.port)
