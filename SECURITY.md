# Security model — Lean cloud chats (0.6.0)

Messages and attachments are stored by the Matrix homeserver and are readable by its operator. This is a cloud messenger, with no end-to-end encryption or device verification requirement for cloud conversations.

Production connections use HTTPS/TLS with normal certificate validation. The Android release rejects HTTP; cleartext networking is enabled only in the separate isolated E2E test application. TLS protects data in transit, not against the server operator or a compromised server. This project does not configure database or disk encryption at rest; enable encrypted server storage and encrypted backups at the infrastructure level if required.

One login form accepts the user's login and password on every device. Existing password credential formats are detected automatically. No recovery key, identity reset, password migration dialog or previous-device approval is needed. Local SDK storage remains encrypted with a random key stored through Android Keystore.

Cloud rooms are private two-person rooms with shared history. Authenticated devices of the same account can read the account's server history and download its attachments. Server membership and media authentication remain enforced. Back up PostgreSQL, Synapse media storage, signing keys and configuration to preserve cloud history.

Pre-0.5 encrypted messages cannot be converted without their original decryption keys. They are not adopted as cloud rooms. The new client hides these rooms and the server keeps a separate canonical cloud room per pair. No existing accounts, encrypted rooms or databases are deleted by this update.

Never post real passwords, access tokens, signing keys, server configuration or message logs in issues. This is an experimental alpha without an independent audit. Background messaging uses a user-visible remoteMessaging foreground service, not FCM. Android force-stop and device power restrictions can prevent delivery until the app is opened. Preserve the existing APK signing key during updates.

Lean profile names, photos, chosen avatar shapes and status text are visible to authenticated users. Per-account hidden/starred flags are stored under the authenticated user ID and room membership is checked. Hiding a message does not erase it for the other participant. Matrix redaction removes event content from normal history, but cannot revoke downloaded files or other copies already saved by recipients.
