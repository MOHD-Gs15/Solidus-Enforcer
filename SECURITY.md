# Security Policy

## Scope

Solidus Enforcer is a server-side Minecraft Fabric mod reconstructed from a decompiled artifact. Compilation and unit tests do not prove safe runtime behavior, so deployment should be tested on a disposable server with backups.

## Secrets

Never commit license files, signing keys, server databases, runtime logs, credentials, or environment files. Any license verification secret must be supplied through protected server configuration or environment-managed secret storage and must never be embedded in the public JAR.

If a credential or token is exposed, revoke or rotate it immediately and issue a replacement with the smallest possible scope.

## Economy and bounty safety

Bounty placement, contract fees, treasury updates, and hunter rewards must be treated as financial operations. Validate finite, non-negative amounts and ensure that money changes and state changes are idempotent or recoverable before production use. Do not assume that asynchronous completion callbacks form one database transaction.

## Core compatibility

Solidus Core is optional at startup and is accessed through a compatibility bridge. A Core API mismatch must fail closed and disable economy-dependent actions rather than silently returning success or inventing balances. Test the bridge against the exact Core build installed on the server.

## Reporting

Please report security issues privately to the repository owner rather than publishing exploit details before a fix is available.
