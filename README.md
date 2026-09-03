# Solidus Enforcer — Bounty & Enforcement Layer

[![Solidus Family](https://img.shields.io/badge/Solidus_Family-2.1.0-8B5CF6.svg)](VERSIONING.md)
[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**Bounty hunting, hunter licenses, alliance payouts, anti-exploit enforcement and collusion detection for the Solidus economy ecosystem — 100% server-side.**

*Rebuilt v1.1, aligned to the 2.1 family: this codebase was reconstructed from a decompiled artifact and then re-audited end to end. Every money path is now atomic, the tick thread never blocks, and every advertised feature actually exists — proven by CI on every push.*

*2.1.1 — security audit round 1: partial-payout revert duplication, inverted burn/refund treasury semantics, dead license refund path, non-atomic confiscation/contract fees, stranded expiry refunds, an inert money-loop collusion signal and unvalidated live tracking are all fixed and regression-tested (46 tests). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §12 and [VERSIONING.md](VERSIONING.md).*

---

## What it does

| Feature | Description |
|---|---|
| **Bounties** | Players place S$ bounties on others; a blood tax splits part to the treasury and part to the burn sink. Bounties decay daily (contract fees) and refund on expiry. |
| **Hunter licenses** | Weekly BRONZE / SILVER / GOLD tiers gate the bounty board and unlock intel (K/D, playtime, wealth) plus tracking compasses. |
| **Alliance payouts** | Kill rewards split between damage contributors (proportional, tracked window) and the finishing bonus (killer). |
| **Anti-exploit** | Value-drop check scales payouts by the victim's death-time gear value; collusion analysis flags kill farming, mutual swaps and money loops. |
| **Autonomous bounties** | The Enforcer funds bounties from the treasury on wealth monopolists and rampage streaks — atomically, with rollback. |
| **Treasury** | Single-row ledger-backed economy sink with full audit trail (`/enforcer treasury`, `/enforcer ledger`). |

## Commands

```
/hunter                         license menu
/hunter tiers                   perks + prices
/hunter buy <bronze|silver|gold>
/hunter info                    your license status
/hunter track <player>          tracking compass (SILVER+ license, active bounty required)

/bounty place <player> <amount>
/bounty list [page]
/bounty info <name>             works for OFFLINE targets
/bounty top

/enforcer treasury              (admin) balance + totals
/enforcer ledger [lines]        (admin) audit trail
/enforcer bounties              (admin) all active bounties
/enforcer cancel <id>           (admin) confiscate a bounty to the treasury
/enforcer stats                 (admin) top hunters
/enforcer reload                (admin) reload config
```

## Compatibility

| Component | Requirement |
| --- | --- |
| Minecraft | 26.1.2 (Mojang mappings) |
| Loader | Fabric 0.19.4+ |
| Fabric API | 0.155.2+26.1.2 |
| Java | 25 |
| Solidus Core | Optional — economy features fail closed without it |

## Fail-closed design

Without Solidus Core the mod boots, serves its state and *refuses* every
economy action with a clear message instead of simulating success. Storage
lives in `config/solidus-enforcer/enforcer.db` (WAL SQLite, single-thread
worker) and is fully independent of Core's databases.

## Build

```bash
./gradlew clean test
./gradlew build
```

## License

MIT — see [LICENSE](LICENSE). Part of the [Solidus Economy Ecosystem](https://github.com/MOHD-Gs15).
