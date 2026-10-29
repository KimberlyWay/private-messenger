# Сервер личного чата

Docker Compose запускает PostgreSQL, Synapse, шлюз регистрации и Caddy. Рабочий экземпляр использует HTTPS на `144.31.107.221:8443`; наружу открыты только TCP 80 и 8443. Caddy работает в контейнере. Внутренний HTTP edge слушает `127.0.0.1:8088`.

## Новое развёртывание

Нужны Docker Engine с Compose v2, Git и Python 3. Команды ниже предназначены для нового экземпляра. На существующем сервере не запускайте генерацию конфигурации заново.

```bash
cd /opt
git clone https://github.com/KimberlyWay/private-messenger.git
cd /opt/private-messenger/server
python3 setup.py --domain 144.31.107.221 --url https://144.31.107.221:8443
```

Для голого публичного IP замените `server/Caddyfile.tls` этим содержимым:

```caddyfile
{
    admin off
    auto_https disable_redirects
    default_sni {$SERVER_NAME}
}
https://{$SERVER_NAME} {
    tls {
        issuer acme https://acme-v02.api.letsencrypt.org/directory {
            profile shortlived
            disable_tlsalpn_challenge
        }
    }
    reverse_proxy edge:8080 {
        header_up X-Real-IP {remote_host}
    }
}
```

Нужен выпуск Caddy с поддержкой короткоживущих сертификатов Let's Encrypt для IP (в проекте закреплён 2.11.7). IP должен быть публичным и доступным снаружи по TCP 80: этот порт используется для HTTP-01. Порт 443 на хосте этому экземпляру не нужен. Caddy продлевает сертификат автоматически, его данные сохраняются в volume.

Если UFW уже включён:

```bash
ufw allow 80/tcp
ufw allow 8443/tcp
ufw status
```

Разрешите эти порты также в firewall провайдера. Затем:

```bash
docker compose --profile tls up -d --build --wait --wait-timeout 600
curl --fail https://144.31.107.221:8443/healthz
```

Замените IP своим во всех местах. Для домена с корректной DNS-записью достаточно штатного `Caddyfile.tls`; укажите домен в `--domain` и `--url`. Клиент принимает HTTPS с доверенным сертификатом. Его адрес задан при сборке APK в `BuildConfig.DEFAULT_SERVER`.

`--domain` задаёт постоянную идентичность Matrix **без порта**. После создания аккаунтов её менять нельзя. `setup.py` создаёт `.env` и `generated/` со случайными секретами и отказывается перезаписывать `.env`.

## Обновление существующего экземпляра

Сначала сделайте резервную копию. Сохраните локальный `Caddyfile.tls`, `.env` и `generated/`. Обновите код через Git, разрешив возможные конфликты с вашей конфигурацией, затем из `server/`:

```bash
docker compose --profile tls up -d --build --wait --wait-timeout 600
docker compose ps
docker compose logs --tail=100 gateway synapse edge tls
```

Не запускайте `setup.py` при обычном обновлении. Изменения оформления клиента не требуют обновлять сервер. Перед обновлением Synapse проверяйте его release notes; major-версию PostgreSQL нельзя менять без миграции данных.

## Данные и резервные копии

Данные находятся в именованных Docker volumes `private-messenger_*`. Обычный `docker compose --profile tls down` сохраняет их. **`down -v` удаляет данные** и применяется только в изолированном CI.

Для согласованной копии остановите сервисы и сохраните все volumes, `.env`, `generated/` и конфигурацию Caddy. Храните копию приватно и проверьте восстановление на отдельном хосте. Серверная копия не заменяет ключи расшифровки пользователей.

Регистрация открыта: логин 3–64 символа `a-z`, цифры, `_`, `-`, `.`; публичный username 3–32 символа `a-z`, цифры, `_`; пароль от 12 символов, до 72 байт UTF-8. Имена приводятся к нижнему регистру. Шлюз ограничивает попытки по IP, но пока не содержит CAPTCHA и почтового восстановления. Не публикуйте наружу PostgreSQL, Synapse 8008 или административный API.
