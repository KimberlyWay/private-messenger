# Security status

This project is an experimental alpha, not an independently audited messenger. Do not use it for sensitive communications until native E2EE integration and physical-device verification have passed.

Message and media cryptography is delegated to Matrix Rust SDK; server TLS is provided by a trusted certificate. The homeserver can observe account and traffic metadata. Device compromise, malicious APK updates, and unverified first-contact identities remain outside content-encryption guarantees.

Never post real passwords, access tokens, recovery keys, keystores, server `.env`, generated configuration, database dumps or decrypted message logs in issues. Use synthetic accounts to reproduce a problem. If no private reporting channel is available, open an issue requesting one without disclosing exploit details or personal data.

Do not reset cross-signing keys or disable encrypted-room checks to make a test pass. Recovery requires the user-held recovery key or an already trusted device. Server backups alone cannot recover plaintext.

The current registration limiter is in memory and is not comprehensive abuse protection. Background push, account lifecycle management and a production APK signing/update process remain incomplete. Follow upstream Synapse, Matrix SDK, Android and container security updates.
