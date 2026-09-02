# Solidus Enforcer — Architecture

> **Version**: 2.1.0 | **Minecraft**: 26.1.2 | **Fabric**: 0.19.4+ | **Java**: 25
> **License**: MIT | **Environment**: 100% Server-Side Only

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Design Principles](#2-design-principles)
3. [Money Safety Model](#3-money-safety-model)
4. [The Bounty Lifecycle](#4-the-bounty-lifecycle)
5. [Kill → Claim → Payout Pipeline](#5-kill--claim--payout-pipeline)
6. [Anti-Exploit & Collusion](#6-anti-exploit--collusion)
7. [Autonomous Bounties](#7-autonomous-bounties)
8. [Hunter Licenses & Tracking](#8-hunter-licenses--tracking)
9. [Storage Schema](#9-storage-schema)
10. [Thread Model](#10-thread-model)
11. [Integration With Solidus Core](#11-integration-with-solidus-core)
12. [Defects Fixed In v1.1](#12-defects-fixed-in-v11)

---

## 1. System Overview

Solidus Enforcer is the enforcement layer of the Solidus economy: it converts
PvP kills into settled bounty payouts, funds server-side bounties against
players who destabilise the economy, and price-attaches risk (blood tax, burn
sink, contract decay) to every placement.

```
┌──────────────────────────────────────────────────────────────────┐
│                       Minecraft Server                            │
│                                                                   │
│  Mixins (death + damage) ──► KillProcessor ──► SolidusBridge ──► │
│                                   │                  Solidus Core │
│                                   ▼                  (economy)    │
│                          BountyManager                            │
│                                   │                               │
│          ┌───────────────────────┼───────────────────┐          │
│          ▼                       ▼                   ▼          │
│   EnforcerStorage         TreasuryManager    AntiExploitEngine   │
│   (SQLite WAL worker)     (ledger mirror)    (collusion/value)  │
│                                                                   │
│  Commands: /bounty /hunter /enforcer  ── HuntersLicenseManager    │
└──────────────────────────────────────────────────────────────────┘
```

## 2. Design Principles

1. **Never block the tick thread.** Storage runs on a single daemon worker;
   Core calls are already async; announcements are composed through async
   chains (the old build called `.join()` from announcements and from the
   value check — both removed).
2. **Single-thread serialization over locking.** Every SELECT+UPDATE pair is
   atomic because it runs as one task on the storage worker (same pattern as
   Solidus Core). No `synchronized`, no table locks.
3. **Fail closed.** Missing Core, missing config, failed insert, failed
   refund — every failure path leaves money accounted for and features
   disabled with clear messages, never silently simulated.
4. **Pure logic is unit-testable.** All tax/fee/split/ratio/collusion-policy
   math lives in dependency-free classes (`EconomyMath`, `BountyEntry`,
   `LicenseTier`, `CollusionDetector.Decision`) covered by JUnit tests.
5. **Database is the source of truth.** The treasury mirror is updated only
   from snapshots returned by the storage worker — never mutated eagerly.

## 3. Money Safety Model

| Operation | Guarantee |
|---|---|
| Placing a bounty | One atomic `subtractBalance` (Core-side check+deduct). No pre-check → no TOCTOU. |
| Insert failure after payment | Automatic `addBalance` refund; failed refunds log CRITICAL. |
| Blood tax | Split via `EconomyMath.bloodTax`; 100% tax rates refuse to zero out bounties. |
| Contract fees | Computed + bounty row updated + treasury ledgered **together** (the old build lost fees on restart). |
| Claim | `claimBountiesForTarget` selects and marks CLAIMED in one task — double kills cannot double-claim. |
| Payout failure | `revertClaim` puts bounties back to ACTIVE (compensation transaction). |
| Expiry | Expired rows are returned by `expireOldBounties`, refunded through the offline bridge, and refunds are verified. |
| Autonomous funding | `tryFundAutonomousBounty` reads balance and deducts in one task; insert failure rolls funding back. |
| Every treasury movement | Appended to `treasury_ledger` with category + note. |

## 4. The Bounty Lifecycle

```
ACTIVE ──kill──► CLAIMED ──paid──► (final)
   │                │
   │                └─payment failed──► ACTIVE (revert)
   ├──admin cancel──► CANCELLED (confiscated to treasury)
   ├──collusion─────► CANCELLED (confiscated) + public denial notice
   └──duration end──► EXPIRED ──► refund placer (offline bridge)

AUTONOMOUS is a placement flag (status 4) — claimable exactly like ACTIVE.
```

Placer pays `amount`; blood tax `t` splits into treasury share and burn
share; the hunter fights over `amount − t`. Daily contract fees decay
`totalAmount` down to `contract_fees.minimum_bounty` after a grace period.

## 5. Kill → Claim → Payout Pipeline

1. `ServerPlayerDeathMixin` fires at `die()` HEAD: captures the killer and a
   **synchronous inventory value snapshot** (the victim may respawn before the
   async pipeline finishes — snapshotting at death is what keeps the
   value-drop check honest).
2. `KillProcessor.processKill` claims all payable bounties atomically.
3. Collusion verdict → value-drop ratio → payable = total × ratio.
4. `EconomyMath.allianceSplit` divides payable into damage pool +
   finishing pool; `EconomyMath.damageShares` distributes the pool by
   contribution with last-cent rounding absorbed by the top contributor.
5. Online recipients are paid via `addBalance`; offline via
   `addBalanceOffline` using the attacker name recorded in damage rows.
6. All paid → stats recorded, damage cleared, public announcement with
   breakdown. Any failure → `revertClaim` + error log.

## 6. Anti-Exploit & Collusion

**Value drop** — `required = bounty × minimum_gear_ratio`; carried less than
that and the payout scales linearly down to the naked-penalty floor.

**Collusion** — three independent signals (any one flags):

* kill farming: same killer→victim pair more than `max_same_pair_kills`
  times inside `kill_pair_window_minutes` (kill_events table),
* mutual swapping: ≥ `max_mutual_swaps` direction reversals in the window,
* money loop: > `max_mutual_transactions` killer↔victim transfers inside
  `transaction_lookback_days` (read through the Core bridge).

Flagged claims are denied, the bounties are confiscated to the treasury, and
a public notice names the reason. Flags are persisted in `collusion_flags`.

## 7. Autonomous Bounties

The check cycle (default every 30 minutes) evaluates:

* **Wealth monopoly** — top-100 balances from Core; a player holding ≥ 40%
  of tracked wealth (configurable) gets a bounty. Core's BalanceEntry has no
  UUID, so targets resolve from online players by name.
* **Rampage / K/D** — kill streaks and kill ratios from `kill_stats`.

Funding is atomic (`tryFundAutonomousBounty`); sizing scales with treasury
depth between `min_auto_bounty` and `max_auto_bounty`; one bounty per reason
category per target prevents duplicates.

## 8. Hunter Licenses & Tracking

Weekly licenses (duration configurable) gate:

| Perk | BRONZE | SILVER | GOLD |
|---|---|---|---|
| Bounty board (place/list/info/top) | ✔ | ✔ | ✔ |
| K/D + playtime intel in announcements | — | ✔ | ✔ |
| Tracking compass | — | ✔ (±50 blocks) | ✔ (±5 blocks, live refresh) |
| Wealth intel | — | — | ✔ |

`/hunter track <player>` requires SILVER+, an active bounty on the target,
and respects a per-player cooldown. Compasses are regular items with a
LodestoneTracker component and a hidden lore marker; GOLD trackers re-point
every `compass.update_interval_ticks` while both players remain online and
the bounty stands.

Purchases are atomic (single `subtractBalance`), strict on tier names
(a typo is an error, never a silent BRONZE sale), and refund automatically
if the DB write fails after payment.

## 9. Storage Schema

| Table | Purpose |
|---|---|
| `bounties` | bounty rows with tax/fee accounting and status lifecycle |
| `hunter_licenses` | license per player (tier, expiry, active flag) |
| `damage_records` | per-hit damage (+ attacker name), cleaned on schedule |
| `treasury` | single-row balance + totals |
| `treasury_ledger` | append-only audit of every movement |
| `kill_events` | killer/victim/timestamp stream for collusion analysis |
| `kill_stats` | kills/deaths/streak per player |
| `collusion_flags` | persisted detection events |

Migrations are handled by `PRAGMA table_info` checks (e.g. v1.0 → v1.1 adds
`damage_records.attacker_name` in place).

## 10. Thread Model

| Thread | Work |
|---|---|
| Server tick | constant-time counters, compass refresh (inventory I/O only) |
| Storage worker | ALL SQLite access, treasury adjustments, claims |
| ForkJoin/commonPool | announcement composition, collusion joins |
| Core executors | balance mutations (owned by Solidus Core) |

The tick thread never waits on a future. Startup blocks once for storage
init (10 s timeout) — on timeout the mod disables itself cleanly.

## 11. Integration With Solidus Core

Reflection bridge, zero compile dependency:

* cached `Method` handles for balance ops, top balances, transaction log,
  shop sections;
* `SolidusAPI.getInstance()` re-resolved per call so late Core init works;
* shop sell-prices cached (30 min TTL) — the kill pipeline never does
  reflection per item;
* absent Core ⇒ `isAvailable() == false` ⇒ every economy path refuses with
  a clear message.

## 12. Defects Fixed In v1.1

| # | Defect (v1.0) | Fix (v1.1) |
|---|---|---|
| 1 | `BountyEntry.create()` referenced undefined `expire` — **did not compile** | proper duration math + `expiryFor()` |
| 2 | `processContractFees()` referenced undefined `gracePeriodMs` | `ConfigManager.getContractGracePeriodMs()` |
| 3 | Contract fees lost on restart (memory-only) | fee ledgered through storage |
| 4 | Pay-then-mark payout race → double payouts | atomic claim-first + revert compensation |
| 5 | Balance pre-check TOCTOU in place/purchase | single atomic `subtractBalance` |
| 6 | Collusion detector was a stub (killer==victim) | kill-pair / swap / money-loop analysis |
| 7 | Monopoly bounties never fired (`UUID.fromString(null)`) | online name resolution |
| 8 | `.join()` on tick thread in announcements | fully async composition |
| 9 | Inventory value read after respawn | death-time synchronous snapshot |
| 10 | Tracking compass advertised but unimplemented | `/hunter track` + GOLD live refresh |
| 11 | `/hunter buy gd` silently sold BRONZE | strict parse + tab-complete |
| 12 | Hardcoded 500 min ignoring config | config-driven validation |
| 13 | Damage/kill rows grew forever | scheduled cleanup both layers |
| 14 | Admin cancel unexposed; no admin tooling | full `/enforcer` command set |
| 15 | Silent mixin failures (`defaultRequire: 0`) | `required: true`, `defaultRequire: 1` |
| 16 | `BalanceEntryData.uuid` always null | honest 2-field record + documented resolution path |
