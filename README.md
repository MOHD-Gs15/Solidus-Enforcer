# Solidus Enforcer

Server-side bounty, hunter licensing, combat tracking, anti-exploit, and collusion detection for Solidus on Minecraft Java 26.1.2.

## Status

This repository is a clean source reconstruction of the recovered `solidus-enforcer` artifact. The recovered Java files were decompiled with CFR, so this implementation is being rebuilt and audited rather than treated as authoritative original source. The project is intended to remain server-only and to fail closed when Solidus Core is unavailable.

## Compatibility

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.4 or newer |
| Fabric API | 0.155.2+26.1.2 |
| Fabric Loom | 1.16-SNAPSHOT (resolves to 1.16.3 in the current environment) |
| Java | 25 or newer |

## Features

Solidus Enforcer provides configurable bounties, hunter licenses, damage contribution tracking, treasury accounting, anti-exploit checks, collusion flags, combat rewards, and optional autonomous bounty processing. Solidus Core is discovered at runtime through a compatibility bridge; without Core, economy-dependent operations are disabled rather than simulated.

## Security and operational notes

The project stores its local state under `config/solidus-enforcer/enforcer.db`. Runtime databases, logs, license data, and credentials are intentionally ignored by Git. Economy operations are asynchronous and must be validated against the exact Solidus Core API version installed on the server. The source reconstruction has not yet been validated by a full Dedicated Server integration test.

Read [SECURITY.md](SECURITY.md) before deploying. In particular, do not expose license secrets or treat a successful compilation as proof that bounty payments are transactionally safe.

## Build

```bash
./gradlew clean test
./gradlew build
```

The mod JAR is written to `build/libs`. The build uses Mojang names for Minecraft 26.1.2 and Java 25.

## License

MIT. See [LICENSE](LICENSE).
