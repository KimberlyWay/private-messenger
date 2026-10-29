# Security status

This project is an experimental alpha, not an independently audited messenger. Do not use it for sensitive communications before independent review and physical-device verification. CI exercises native encrypted exchange, SAS, legacy recovery, unsigned-device rejection and the registration UI. Check the current Actions results for the exact commit you are evaluating.

The SDK policy is `CrossSignedOrLegacy`: fresh unsigned sessions are rejected, but imported legacy backup sessions can be displayed with the SDK provenance warning. Matrix v1 asymmetric backups do not authenticate historical sender provenance against a malicious homeserver; warning-bearing recovered history is not proof of authorship. `Untrusted` decryption is not enabled.

Message and media cryptography is delegated to Matrix Rust SDK; server TLS is provided by a trusted certificate. The homeserver can observe account and traffic metadata. Device compromise, malicious APK updates, and unverified first-contact identities remain outside content-encryption guarantees.

Never post real passwords, access tokens, recovery keys, keystores, server `.env`, generated configuration, database dumps or decrypted message logs in issues. Use synthetic accounts to reproduce a problem. If no private reporting channel is available, open an issue requesting one without disclosing exploit details or personal data.

Do not reset cross-signing keys or disable encrypted-room checks to make a test pass. Recovery requires the user-held recovery key or an already trusted device. Server backups alone cannot recover plaintext.

The current registration limiter is in memory and is not comprehensive abuse protection. Background push and account lifecycle management remain incomplete. Release APKs use a permanent signing key stored in GitHub Actions Secrets; do not rotate it during routine updates. Follow upstream Synapse, Matrix SDK, Android and container security updates.

Password-only restoration of encrypted history is planned, not implemented. The current server receives the authentication password, so this password must not simply be reused to encrypt a server-hosted recovery key. See [the recovery design requirements](docs/RECOVERY.md).
