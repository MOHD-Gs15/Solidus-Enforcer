# Solidus Enforcer 2.1.1 — Reliability & Stability Audit Report

**Repository:** https://github.com/MOHD-Gs15/Solidus-Enforcer
**Audited commit (baseline):** `20c28b2` (`chore(ci): SA2-027 supply-chain hardening - pin every action to its full commit SHA`)
**Audit scope:** Reliability and stability, with the same dimensions applied to solidus-core 2.2.6 and Solidus-Governance: client–server conflicts, accounting/financial integrity, data duplication, data disappearance, hangs and deadlocks.
**Method:** Full read of every production source file (~5.3k LOC across 30 files), cross-checked against the Solidus-core 2.2.6 and Governance audit reports (bridge contract, `SolidusAPI` semantics, storage thread model), bytecode verification of the mapped `Inventory` slot contract, plus a dedicated pass over the kill→claim→payout money path. All fixes verified with `compileJava` and the full test suite.
**Result:** **14 findings fixed** (ENF‑01..ENF‑14), 9 lower-severity items documented as accepted/verified (ENF‑15..ENF‑23). One CRITICAL money-creation defect (ENF‑01) and two HIGH money-disappearance defects (ENF‑02, ENF‑03) were found in this round — all in paths introduced or left uncovered by the earlier E‑series hardening rounds, which were otherwise re-verified as present and coherent.

---

## 1. Context and architecture recap

Solidus Enforcer is a server-side Fabric mod that turns PvP on a Solidus economy server into an enforced market: player-funded bounties, tiered hunter licenses, blood tax with a burn sink, contract-fee decay, autonomous server-funded bounties, and collusion detection.

Two properties dominate its reliability profile:

1. **The escrow money model.** A placement subtracts the full amount from the placer immediately; the tax legs are ledgered; the post-tax bounty sits as a DB row (no account holds it). Money is re-minted only at a terminal outcome: hunter payment, placer refund, treasury confiscation, or autonomous AUTO_REFUND. Every defect that breaks a transition between those states is therefore a money-supply defect, not a bookkeeping nuisance.
2. **The single-thread storage worker.** Every SELECT+UPDATE pair inside one worker task is atomic with respect to all other storage operations — the entire claim/confiscate/fund machinery leans on this. The audit therefore focused on async chains that *leave* the worker and come back (kill payouts, autonomous placements, refunds), where ordering is no longer guaranteed.

Thread model: SQLite WAL on a single daemon worker; async chains on the ForkJoin common pool; Core calls through the reflection bridge; command feedback marshalled through `server.execute(...)`; the tick thread runs constant-time buckets only (per the ARCHITECTURE.md contract — re-verified, with two exceptions found and fixed as ENF‑08/ENF‑10).

The prior audit rounds (v1.1 E‑series, 2.1.1 security round) were verified during this pass — the fixes are present, coherent, and covered by tests. This round's findings are numbered **ENF‑01…** to distinguish them.

---

## 2. Findings (fixed)

### ENF-01 — Blood-tax legs were two separate transactions; a partial failure minted money (CRITICAL)

**Where:** `bounty/BountyManager.recordTaxMoves`, `storage/EnforcerStorage.adjustTreasury`.

**Defect:** Placement recorded the treasury leg and the burn leg as two independent `adjustTreasury` transactions. The rollback contract ("nothing was ledgered") only holds when BOTH fail. If the treasury leg committed and the burn leg failed (disk error, lock, disk full), `chargeAndCreate`'s `exceptionallyCompose` cancelled the bounty and refunded the placer the FULL paid amount — while the committed treasury leg stayed credited. Net effect: the tax share is minted from nothing, silently, behind a CRITICAL log that describes a clean rollback. ARCHITECTURE.md line "Treasury leg and burn leg are recorded in one DB transaction each" documented the flaw verbatim.

**Fix:** New `EnforcerStorage.recordTaxLegs(treasuryAmount, burnAmount, note)` — both ledger rows and both treasury-row updates land in ONE database transaction, fail-loud on SQLException (rolled back whole). `BountyManager.recordTaxMoves` now calls it directly; the rollback path is finally as safe as its log message claims. Covered by `recordTaxLegsAppliesBothLegsInOneOperation`, `recordTaxLegsWithZeroAmountsChangesNothing`, `recordTaxLegsWithOnlyOneLegStillLandsWhole`.

### ENF-02 — One-shot kills could pay the killer 30% and evaporate the other 70% (HIGH)

**Where:** `combat/KillProcessor.settlePayout`, `economy/EconomyMath.damageShares`.

**Defect:** The death mixin fires at `die()` HEAD; the killing blow's damage row is written at `hurtServer` RETURN — i.e., *after* the claim is already queued. `getDamageContributions` therefore races the final blow's INSERT. When the victim had no other recent attackers and the read loses that race (one-shot kills: strength-crit one-punch, sniper headshots), `damageShares` returned an empty map and the killer received only the finishing pool (default 30%). The damage pool (default 70%) was claimed, paid to no one, and evaporated — the placer had paid for it. Intermittent, unreproducible on demand, and biased against exactly the most impressive kills.

**Fix:** New pure helper `EconomyMath.payoutMap(contributions, killerUuid, split)` — when no assists are recorded, the killer takes the damage pool too, so a solo killer always receives the full payable regardless of the recording race. `KillProcessor` uses it. Covered by `payoutMapWithNoContributorsGivesKillerTheWholePot`, `payoutMapSplitsDamageAndAwardsFinishingBonus`, `payoutMapSoloAttackerReceivesEverything`.

### ENF-03 — Value-drop partial payouts silently destroyed the unpaid remainder (HIGH)

**Where:** `combat/KillProcessor.processKill` / `settlePayout`.

**Defect:** When the value-drop ratio lands strictly between 0 and 1 (victim under-geared but not naked), `payable = totalBounty × ratio` was distributed and the remainder — money the placer paid, now payable to no one — simply vanished from the economy. No ledger row, no treasury credit, no refund: a silent, unbounded money-disappearance channel misaligned with the plugin's own philosophy (the collusion path confiscates to the treasury; the naked path should too).

**Fix:** On the all-payments-succeeded path, the remainder is now confiscated to the treasury under a new `PENALTY("value-drop penalty")` ledger category (balance-only credit — deliberately not counted as tax; see ENF‑16). The confiscation leg is fail-loud with a CRITICAL manual-reconciliation log if the treasury write itself fails (money is already paid at that point). Covered by `penaltyCreditsBalanceWithoutTouchingTheTaxStat`.

### ENF-04 — Autonomous bounties never expired: permanent treasury escrow leak (HIGH)

**Where:** `storage/EnforcerStorage.expireOldBounties` (`AND autonomous = 0`), `bounty/BountyManager.refundPending`.

**Defect:** The expiry query excluded autonomous rows, so server-funded bounties stayed claimable *forever*. The treasury money funding them was locked in escrow with no terminal outcome, and the per-category de-duplication blocked re-placement for the same reason on the same target — the engine could never refresh a bounty for a permanently-too-strong or permanently-offline player. The `AUTO_REFUND` category existed but was unreachable from the expiry path.

**Fix:** `expireOldBounties` now expires autonomous rows like any other live bounty. In `refundPending`, autonomous rows (no `placedByUuid`) return their escrow to the treasury through `adjustTreasury(AUTO_REFUND, …)` — an atomic storage operation that cannot half-fail — instead of attempting a player payment. The claim-before-pay recovery loop (`refund_pending`) covers crashes mid-loop for both kinds. Covered by `autonomousBountiesExpireRefundPendingLikePlayerBounties`.

### ENF-05 — Interrupted settlements were invisible after a crash (MEDIUM)

**Where:** `storage/EnforcerStorage.claimBountiesForTarget`, `combat/KillProcessor`, `SolidusEnforcerMod.onServerStarted`.

**Defect:** A server crash (or a partial payout) between claim and settle left bounties CLAIMED with the money in escrow and *zero trace at the next startup*. CLAIMED is also the terminal state of a successful payment, so the two were indistinguishable — a kill interrupted mid-payout silently swallowed the placer's money with no report, no log, no reconciliation path.

**Fix:** New `bounties.settlement_pending` column (in-place migration, default 0): raised atomically by the claim, cleared by every terminal outcome — `clearSettlementPending` on full payment (first stage of `finalizeSettlement`), `revertClaim` on compensation, `confiscateBounties` on denial/cancel. At startup, `findStuckSettlements()` reports any still-flagged rows in a CRITICAL log naming ids, targets, and amounts. Nothing is auto-retried — payment state is unknowable after the fact, and the family rule is prefer-loss-over-double-pay; the partial-payout path deliberately keeps the flag so every startup re-reports it until an admin reconciles. Covered by `claimMarksSettlementPendingAndRevertClearsIt`, `settledBountiesKeepClaimedStatusWithoutThePendingFlag`, `confiscationClearsTheSettlementFlag`.

### ENF-06 — Unbounded `.join()` on the server thread at startup (MEDIUM)

**Where:** `SolidusEnforcerMod.onServerStarted` — `this.storage.loadTreasury().join()`.

**Defect:** `initialize()` was bounded with `orTimeout(10s)`, but the treasury load immediately after joined unbounded. A worker stalled mid-read (pathological disk, corrupted WAL) would hang server startup forever with no timeout and no fail-closed path.

**Fix:** The treasury load is now inside the same bounded try block with its own `orTimeout(10s)`; any failure takes the documented fail-closed disable path (Enforcer stays off this run, mixins no-op). The `treasury` field is additionally nulled on that path so a restart of the lifecycle cannot observe a half-built state.

### ENF-07 — Refunds failed for players who disconnected mid-chain (MEDIUM)

**Where:** `bounty/BountyManager.refundAndFail`, `license/HunterLicenseManager.purchaseLicense`.

**Defect:** Both refund paths called `SolidusBridge.addBalance(player, …)` with a `ServerPlayer` captured before the async chain ran. A player who disconnects between payment and refund (crash, kick, rage-quit after a failed placement) left Core's online op with a detached player object — the refund fails, and the only remedy is a CRITICAL log and manual admin work, even though Enforcer knows the refundee's UUID and name and Core exposes `addBalanceOffline`. Hunter payouts already used the online/offline pattern; the two refund paths had been missed.

**Fix:** Both refund paths now resolve the player list at refund time and fall back to `addBalanceOffline(uuid, name, amount)` — identical to `KillProcessor.payRecipient`. A genuinely failed offline refund still fails loud (CRITICAL + player-facing "contact an admin").

### ENF-08 — `/bounty place` rejection sent from the storage thread (MEDIUM)

**Where:** `commands/BountyCommand.executePlace`.

**Defect:** The "You need a Hunter License" failure was sent via `sendFailure` directly inside the `hasActiveLicense` continuation — i.e., from the storage worker thread, the only un-hopped feedback path in the command layer (the family's thread contract since the 2.1.1 round: command feedback is marshalled through `server.execute`).

**Fix:** The failure now hops through `ctx.getSource().getServer().execute(...)`, matching every other path in the file.

### ENF-09 — Autonomous announcements iterated the live player list off-thread (MEDIUM)

**Where:** `bounty/AutonomousBountyEngine.checkAndPlaceAutonomousBounty`.

**Defect:** `announceAutonomousBounty` was invoked from the `insertBounty` continuation (common pool) and walks `server.getPlayerList().getPlayers()` while composing per-viewer intel. Every actual message send was already hopped, but the list iteration itself raced joins/leaves off-thread.

**Fix:** The announcement call is wrapped in `server.execute(...)` (with a shutdown-rejection guard, same as the KillProcessor announcement paths), so the whole walk runs on the server thread.

### ENF-10 — Cold/expired shop cache mispriced the first kill and stalled the tick thread (MEDIUM)

**Where:** `integration/SolidusBridge.getShopSellPrices`, `economy/ValueCalculator`, `SolidusEnforcerMod.onServerStarted`.

**Defect:** The shop price cache starts empty on every boot. The first death-time valuation therefore read an empty table — valuing a fully-geared victim's inventory at 0 and flooring the payout at the naked-penalty minimum (5%) for that one kill. Additionally, on every 30-minute TTL expiry the *next* death performed the whole reflection walk synchronously on the server thread (the never-block rule's only remaining violation).

**Fix:** Stale-while-revalidate: an expired table is refreshed asynchronously (single-flight guarded by an `AtomicBoolean`, so a 41-slot valuation cannot stack 41 refresh tasks) and the stale copy is served meanwhile; only a truly cold cache still loads synchronously (bounded, and now made unlikely by an explicit `warmShopPriceCacheAsync()` at server start when Core is present).

### ENF-11 — `/hunter track` cooldown was check-then-act across async hops (LOW)

**Where:** `commands/HunterCommand.executeTrack`.

**Defect:** The cooldown check and the `TRACK_COOLDOWNS.put(...)` bracketed two async hops (license read, bounty read), so two rapid commands could both pass the check and both issue compasses, and the recorded timestamp was the stale pre-hop capture.

**Fix:** The authoritative gate is now a single atomic `ConcurrentHashMap.compute` (`tryConsumeTrackCooldown`) executed immediately before `issueCompass`; the early check remains as fast UX rejection only. The map stays bounded by distinct hunters (documented as ENF‑22).

### ENF-12 — `countMutualSwaps` loaded unbounded kill-event rows (LOW)

**Where:** `storage/EnforcerStorage.countMutualSwaps`.

**Defect:** The SQL had no timestamp filter — the full 24-hour retained history of a pair was pulled into memory and the collusion window (default 60 minutes) applied only in Java. A bot farm trading kills for hours inflated every collusion check's working set for no benefit.

**Fix:** `AND timestamp > ?` in SQL; the Java-side cutoff filter is gone. Semantics are unchanged (verified by the existing collusion policy tests; the counting logic itself is untouched).

### ENF-13 — Expiration-cycle failures vanished silently (LOW)

**Where:** `bounty/BountyManager.processExpirations`.

**Defect:** The method is fired-and-forgotten from startup and the tick bucket; an exceptional failure of the chain (e.g., `expireOldBounties`'s worker task throwing before the internal SQLException guards) completed the future with no handler anywhere — a dropped expiration cycle with no log line.

**Fix:** Terminal `.exceptionally` logger on the returned future: the failure is reported and the next cycle (or restart) retries, matching the documented crash-safety contract.

### ENF-14 — Failed refunds were counted as refunded (LOW)

**Where:** `bounty/BountyManager.refundPending`.

**Defect:** The recursion counted `refunded + 1` on every claimed row, including attempts that logged a refund FAILURE. The count is currently unused by callers, but any future consumer would silently over-report recoveries.

**Fix:** The loop now threads a success boolean out of the `handle` stage and counts only paid refunds (both the player leg and the new autonomous leg).

---

## 3. Findings (documented, accepted — with rationale)

### ENF-15 — Dead config key and dead public method

`collusion_detection.same_claim_penalty` was advertised in the default config and read by `ConfigManager.isSameClaimPenaltyEnabled()` but consumed by nothing; `DamageTracker.getLastHitter` has no callers. The key is removed from the bundled defaults (existing configs carrying it are simply ignored); the getter and the method stay for API stability — they are public surface, and the family contract forbids removing signatures in a patch release. Revisit at the next minor.

### ENF-16 — `Category.PAYOUT` is unreachable; `total_paid_bounties` is an autonomous-only stat

No code path invokes `Category.PAYOUT`: player-funded payouts escrow (destroy at placement, mint at payment) and never touch the treasury, so `/enforcer treasury`'s "Total Paid Bounties" reflects only autonomous funding legs (AUTO_FUND/AUTO_REFUND). This is a naming/UX wart, not a defect — the ledger itself is complete. The new PENALTY category deliberately credits balance without touching `total_collected_tax` for the same reason: stats must not launder semantics. A stat rename/rework is a minor-version decision.

### ENF-17 — Overlapping contract-fee cycles could double-credit the treasury (theoretical)

`processContractFees` computes each fee from a snapshot; `applyContractFee`'s CAS (status IN (0,4)) prevents compounding on the bounty row, but two overlapping cycles would each ledger a fee while the row only decays once — crediting the treasury twice for one unit of decay. Triggering it requires a fee cycle to outlast the 30-minute interval, i.e. thousands of live bounties with millisecond transactions; accepted as theoretical. If it ever matters, the fix is recomputing the fee inside the transaction from the row's live amount.

### ENF-18 — Overlapping autonomous cycles could double-place one category (theoretical)

The de-dup check (active bounties by category) and the insert are separate storage tasks; a cycle whose insert is still in flight when the next cycle checks could place a duplicate. Same feasibility analysis as ENF‑17 (cycle interval 30 min, chain latency milliseconds). Accepted.

### ENF-19 — Final-blow damage row records after `die()` HEAD (mitigated)

The mixin ordering (damage at `hurtServer` RETURN, death pipeline started at `die` HEAD) means the killing blow's damage row always lands after the claim is queued. With ENF‑02 the solo-killer case is fully protected; the residual effect in multi-attacker fights is a possible minor under-weighting of the finishing killer's damage share (the finishing pool still lands on the killer by construction). Reordering the injection points was considered and rejected — moving damage recording earlier in `hurtServer` risks recording damage for hits the game then cancels (invulnerability frames, totem pops), which would corrupt contributions in a worse way.

### ENF-20 — `claimNextRefundPending` is an N+1 scan loop

Each refund re-scans `status = EXPIRED AND refund_pending = 1` ordered by `expire_timestamp`. Bounded by the bounty count and covered by the status/expire indexes; a mass-expiry burst costs O(N²) index lookups on the worker with zero server-thread impact. Accepted.

### ENF-21 — Inventory slot mapping verified correct (negative finding)

Bytecode inspection of the mapped 26.1.2 `Inventory`: `getNonEquipmentItems()` returns only the 36-slot `items` list; `getItem(36..40)` routes through `EQUIPMENT_SLOT_MAPPING` to armor + offhand. `ValueCalculator.calculateInventoryValue` (0-35 + 36-39 + 40) therefore counts every slot exactly once. Verified rather than assumed because a double count would have silently inflated payouts.

### ENF-22 — `TRACK_COOLDOWNS` is an unbounded static map

One `Long` per player who ever ran `/hunter track`; never evicted. Growth is bounded by distinct hunters (a per-player row, not per-use) and lives for the server run only. Accepted; evicting on disconnect would trade a trivial leak for a new consistency question.

### ENF-23 — Shutdown closes the SQLite connection from the server thread

`shutdown()` awaits the worker 5 s, then force-interrupts and closes the connection from the calling thread. A task mid-transaction after the timeout fails loudly at worst (transaction rolls back); the worker is a daemon thread and the JVM is exiting anyway. Accepted — same posture Core and Governance ship.

---

## 4. Verification

- `./gradlew build` — **BUILD SUCCESSFUL** (JDK 25 toolchain, Loom 1.16.3, Minecraft 26.1.2).
- Test suite: **58 tests, all green** (46 carried over + 12 new: 3 payout-map, 3 tax-leg atomicity, 1 penalty semantics, 3 settlement-pending lifecycle, 1 autonomous expiry, plus the tightened mutual-swap SQL verified by the existing policy tests).
- Migration check: `settlement_pending` is added via `addColumnIfMissing` — an existing 2.1.1 database upgrades in place on first boot; old CLAIMED rows default to flag 0 (historical, unreported), which is the conservative direction (no false stuck-settlement reports).
- API compatibility: no public signature was changed or removed. Additions only: `EnforcerStorage.recordTaxLegs/clearSettlementPending/findStuckSettlements/StuckSettlement`, `TreasuryManager.Category.PENALTY`, `EconomyMath.payoutMap`, `SolidusBridge.warmShopPriceCacheAsync`. The config key removal (ENF‑15) is backwards-compatible (unknown keys are ignored on load).

## 5. Change footprint

14 production files touched (+624/−212 across production and tests), 1 bundled resource (default config), 2 test files extended, 1 new document (this report). ARCHITECTURE.md updated where behavior moved (§3 money-safety table, §4 lifecycle diagram, §9 schema, new §14 defects table for this round).
