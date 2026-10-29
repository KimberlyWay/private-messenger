# Security status

This project is an experimental alpha, not an independently audited messenger. Do not use it for sensitive communications before independent review and physical-device verification. CI exercises native encrypted exchange, SAS, legacy recovery, unsigned-device rejection and the registration UI. Check the current Actions results for the exact commit you are evaluating.

The SDK policy is `CrossSignedOrLegacy`: fresh unsigned sessions are rejected, but imported legacy backup sessions can be displayed with the SDK provenance warning. Matrix v1 asymmetric backups do not authenticate historical sender provenance against a malicious homeserver; warning-bearing recovered history is not proof of authorship. `Untrusted` decryption is not enabled.

Message and media cryptography is delegated to Matrix Rust SDK; server TLS is provided by a trusted certificate. The homeserver can observe account and traffic metadata. Device compromise, malicious APK updates, and unverified first-contact identities remain outside content-encryption guarantees.

Never post real passwords, access tokens, recovery keys, keystores, server `.env`, generated configuration, database dumps or decrypted message logs in issues. Use synthetic accounts to reproduce a problem. If no private reporting channel is available, open an issue requesting one without disclosing exploit details or personal data.

Do not reset cross-signing keys or disable encrypted-room checks to make a test pass. Legacy recovery requires a user-held recovery key or an already trusted device. New v2 accounts restore an authenticated, client-encrypted recovery envelope using the human password. Server backups alone cannot recover plaintext.

The current registration limiter is in memory and is not comprehensive abuse protection. Background push and account lifecycle management remain incomplete. Release APKs use a permanent signing key stored in GitHub Actions Secrets; do not rotate it during routine updates. Follow upstream Synapse, Matrix SDK, Android and container security updates.

Current v2 clients derive a master with PBKDF2-HMAC-SHA256 (600,000 iterations, account-specific random salt plus server identity and private login), then derive separate authentication and recovery-envelope secrets with domain-separated HMAC-SHA256. Only the authentication derivative is sent to Synapse. An AES-256-GCM envelope with a fresh 96-bit nonce protects the Matrix recovery key and binds it to the server identity and user ID as authenticated data. The messaging protocol remains the upstream Matrix SDK. This is not PAKE and has not had an independent cryptographic review. A malicious server or stolen credential derivative can support offline password guessing; strong passwords remain necessary. Never use the authentication derivative as the recovery key.

Legacy accounts must choose a different new human password when migrating, because the old password was previously sent to the server. Raw-password login is an explicit legacy UI mode, never an automatic fallback. Identity reset after loss of all keys requires explicit user confirmation and does not restore old history. See [the recovery implementation](docs/RECOVERY.md).
