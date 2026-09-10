<h1 align="center">Solidus Enforcer</h1>

<p align="center"><strong>Death has a price, and fraud has a bill</strong><br>
Player bounties, licensed bounty hunters, and economic anti-exploit enforcement for the Solidus ecosystem.</p>

<p align="center">
  <a href="https://github.com/MOHD-Gs15/Solidus-Enforcer/actions/workflows/test.yml"><img src="https://github.com/MOHD-Gs15/Solidus-Enforcer/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
  <a href="https://github.com/MOHD-Gs15/Solidus-Enforcer/actions/workflows/codeql.yml"><img src="https://github.com/MOHD-Gs15/Solidus-Enforcer/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <img src="https://img.shields.io/badge/version-2.1.1-blue" alt="Version 2.1.1">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-brightgreen" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT License"></a>
  <a href="https://github.com/MOHD-Gs15"><img src="https://img.shields.io/badge/mod%20by-MOHD--Gs-6f42c1" alt="Mod by MOHD-Gs"></a>
</p>

**Solidus Enforcer** is a server-side Fabric mod for Minecraft 26.1.2 that turns PvP on a server running the [Solidus](https://github.com/MOHD-Gs15/solidus-core) economy mod into a full economic system: place a bounty on any player's head, buy a hunter license to earn tracking tools and kill rewards, and let the system handle everything else — splitting rewards between assisted attackers, a "blood tax" that shrinks the money supply, and automatic detection of colluding players who trade kills between fake accounts. Every coin that moves is the Solidus currency (S$) through the official Core bridge — there is no parallel currency and no way to mint money by killing. Free and open source under the MIT license.

## Why server owners pick Solidus Enforcer

- **Consequences for griefing** — a bounty system your players actually fund themselves; the server pays nothing.
- **An economy sink that fights inflation** — the blood tax burns part of every kill's loot instead of recycling it.
- **Anti-fraud built in** — kill-trading and collusion rings get detected and their payouts get denied automatically.
- **Purely server-side** — damage and death are monitored server-side; players install nothing.

## Features

- **Safe bounties** — placing a bounty deducts the full amount from your balance in a single atomic operation; the pot is paid in full to whoever lands the kill, with daily limits and no duplicate bounties on the same target.
- **Hunter licenses in three tiers** — Bronze, Silver, and Gold with weekly costs; higher tiers mean better earnings and sharper tracking.
- **Hunter compass** — licensed hunters track their targets with tier-based precision and a cooldown between tracks, so hunting never becomes harassment.
- **Blood tax** — a percentage of every kill's payout is taken: part goes to the public treasury and part is burned (removed from the economy), so growth stays healthy instead of inflating.
- **Assist splitting** — when several players damage the same target, the reward is split automatically by each attacker's actual contribution, with a bonus for the finishing blow.
- **Collusion detector** — players who repeatedly kill each other in pairs or shuttle money back and forth during the watch window have suspicious payouts denied and face joint penalties.
- **Autonomous bounties** — a dedicated engine places bounties with no admin involvement: on kill streakers and on wealth monopolizers, with an announcer telling the server about new heads.
- **Contract fees** — long-lived bounties accrue a daily fee after a grace period, so the bounty board can't be choked with permanent offers.
- **Value-drop requirement** — anti-"naked-kill" protection: a payout can be denied if the target had almost no gear, according to a configurable power ratio, with a penalty for violators.
- **Anti-exploit engine** — monitors kill and transaction patterns together, not in isolation.
- **Public treasury & ledger** — every income and expense lands in a ledger any admin can page through with one command.

## Commands

### Bounties (players)

| Command | What it does |
|---------|--------------|
| `/bounty place <player> <amount>` | Put a bounty on a player's head |
| `/bounty list [page]` | Browse open bounties |
| `/bounty info <player>` | Details of a specific player's bounties |
| `/bounty top` | Highest current bounties |

### Hunters (players)

| Command | What it does |
|---------|--------------|
| `/hunter` | Main hunter menu |
| `/hunter tiers` | The three tiers, prices, and perks |
| `/hunter buy <tier>` | Buy or renew a hunter license |
| `/hunter info` | Your current license status |
| `/hunter track <player>` | Track a target (requires an active license; cooldown applies) |

### Administration (server console or admin permission)

| Command | What it does |
|---------|--------------|
| `/enforcer treasury` | Public treasury status |
| `/enforcer ledger [lines]` | Latest ledger entries (1–50 lines) |
| `/enforcer bounties` | All active bounties |
| `/enforcer cancel <id>` | Cancel a bounty as an admin |
| `/enforcer stats` | Kill and bounty statistics |
| `/enforcer reload` | Reload the configuration |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server (Minecraft 26.1.2).
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the [Solidus Core](https://github.com/MOHD-Gs15/solidus-core) mod into `mods` — Enforcer is an add-on to Core, not a replacement.
3. Drop `solidus-enforcer-2.1.1.jar` into `mods`.
4. Start the server — the bounty and hunter database is created automatically.

> Server-side only: kills and damage are monitored at the server endpoint; players need zero downloads.

## Quick configuration

After first start you'll find `config/solidus-enforcer/enforcer-config.json`, organized into clear sections:

| Section | What you tune |
|---------|---------------|
| `license_tiers` (bronze/silver/gold) | Weekly cost per tier |
| `license.duration_days` | How long a license stays valid |
| `bounty_limits` | Min/max amount, bounties per player and per target, bounty lifetime |
| `blood_tax` | Tax rate and the treasury/burn shares |
| `contract_fees` | Daily fee for long-lived bounties and the grace period |
| `value_drop_requirement` | Required gear-power ratio and the naked-kill penalty |
| `alliance_split` | Per-attacker share, finisher bonus, and the assist window |
| `autonomous_bounties` | Enable auto-bounties, wealth-monopoly threshold, kill-streak base |
| `collusion_detection` | Pair-kill window, mutual-kill cap, financial-trace duration |
| `compass` | Silver/Gold tracking precision and cooldown |

## For advanced users

**Architecture.** Server mixins on the damage and death events feed `DamageTracker` with a per-hit record. On death, `KillProcessor` builds a payout verdict (`PayoutVerdict`): who hit and how much, who finished, whether payment is gated by the value-drop rule — then `TreasuryManager` distributes the money across the treasury, hunters, and the burn sink. All balances are read and written through the official Solidus integration bridge, so there is exactly one source of money.

**Collusion detection.** Statistical queries over the kill-event table find repeatedly-killing pairs within the window, mutual kill-trading (each alternately killing the other), and reciprocal money transfers during the financial-trace period — exceeding the thresholds triggers denials and penalties automatically, all logged.

**Autonomous bounties.** A standalone engine periodically scans kill streaks and wealth concentration and places bounties computed from a configurable base, with an announcer broadcasting new bounties to the server.

**Testing.** 6 test classes (46 test methods) run on every push, covering payout verdicts, bounty lifecycles, economy math, license/collusion policy, and storage. Build locally with JDK 25: `./gradlew build`.

## Documentation

| Document | Contents |
|----------|----------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture: mixins, tracking, payout verdict, storage |
| [VERSIONING.md](VERSIONING.md) | Version policy and 2.1.x family compatibility |
| [SECURITY.md](SECURITY.md) | Security reporting policy |

## The Solidus family

| Mod | What it adds | Repository |
|-----|--------------|------------|
| **Solidus Core** | The economy engine itself | [MOHD-Gs15/solidus-core](https://github.com/MOHD-Gs15/solidus-core) |
| **Solidus Analytics** | Monitoring, dashboards, fraud detection | [MOHD-Gs15/solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) |
| **Solidus Governance** | Taxes, limits, policies, audits, recovery | [MOHD-Gs15/Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) |
| **Solidus Enforcer** (this repo) | Bounties, hunter licenses, anti-exploit enforcement | [MOHD-Gs15/Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) |

## License & credits

- **Mod by [MOHD-Gs](https://github.com/MOHD-Gs15)**
- Licensed under the [MIT License](LICENSE) — free to use, modify, and ship with your server.
