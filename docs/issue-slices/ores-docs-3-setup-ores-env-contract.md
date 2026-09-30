# reusable setup-ores-env workflow

Driver: `ORESoftware/ores-docs#3`

This document defines one bounded, independently reviewable contract slice. It advances the driver issue without claiming full implementation.

## Invariants

- Refuse protected decryption on fork-originated pull requests.
- Install/pin ores-sops, SOPS, and age through reviewed immutable inputs.
- Decrypt only the authorized environment/profile slice into protected ephemeral storage.
- Mask sensitive values, validate key shape, and clean decrypted state on success or failure.

## Verification

- Test/verify the exact PR head.
- Preserve fail-closed behavior for malformed or untrusted inputs.
- Treat skipped/zero-step CI as missing evidence.
- Bind release or runtime evidence to immutable source identity.

## Non-goals

This slice does not add secrets, weaken repository protection, or silently rewrite consumer state.
