package com.solidus.enforcer.bounty;

import com.solidus.enforcer.economy.EconomyMath;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounty placement, decay, expiry and admin cancellation.
 *
 * Money safety rules enforced here:
 * <ul>
 *   <li>Payment is a single atomic {@code subtractBalance} — the old
 *       check-then-subtract TOCTOU is gone.</li>
 *   <li>Every late failure (DB insert) triggers an automatic refund attempt
 *       and a loud log if the refund itself fails.</li>
 *   <li>Tax moves are ledgered through the storage worker and the in-memory
 *       treasury mirror is updated from the DB snapshot, never eagerly.</li>
 * </ul>
 */
public final class BountyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final TreasuryManager treasury;

    public BountyManager(EnforcerStorage storage, ConfigManager config, TreasuryManager treasury) {
        this.storage = storage;
        this.config = config;
        this.treasury = treasury;
    }

    public CompletableFuture<BountyResult> placeBounty(ServerPlayer placer, ServerPlayer target, double amount) {
        UUID placerUuid = placer.getUUID();
        UUID targetUuid = target.getUUID();
        String targetName = target.getName().getString();
        String placerName = placer.getName().getString();

        if (placerUuid.equals(targetUuid)) {
            return CompletableFuture.completedFuture(BountyResult.fail("You cannot place a bounty on yourself"));
        }
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return CompletableFuture.completedFuture(BountyResult.fail("Bounty amount must be a positive number"));
        }
        if (amount < this.config.getMinBounty()) {
            return CompletableFuture.completedFuture(BountyResult.fail(
                    "Minimum bounty is " + TextUtil.currency(this.config.getMinBounty()).getString()));
        }
        if (amount > this.config.getMaxBounty()) {
            return CompletableFuture.completedFuture(BountyResult.fail(
                    "Maximum bounty is " + TextUtil.currency(this.config.getMaxBounty()).getString()));
        }
        if (!SolidusBridge.isAvailable()) {
            return CompletableFuture.completedFuture(BountyResult.fail("Economy system is not available"));
        }

        return this.storage.getActiveBountyCountByPlacer(placerUuid).thenCompose(activeByPlacer -> {
            if (activeByPlacer >= this.config.getMaxActivePerPlayer()) {
                return CompletableFuture.completedFuture(BountyResult.fail(
                        "You already have " + activeByPlacer + " active bounties (max: "
                                + this.config.getMaxActivePerPlayer() + ")"));
            }
            return this.storage.getActiveBountyCountForTarget(targetUuid).thenCompose(activeOnTarget -> {
                if (activeOnTarget >= this.config.getMaxBountiesPerTarget()) {
                    return CompletableFuture.completedFuture(BountyResult.fail(
                            targetName + " already carries " + activeOnTarget + " bounties (max: "
                                    + this.config.getMaxBountiesPerTarget() + ")"));
                }
                return this.chargeAndCreate(placer, placerUuid, placerName, targetUuid, targetName, amount);
            });
        });
    }

    private CompletableFuture<BountyResult> chargeAndCreate(ServerPlayer placer, UUID placerUuid,
                                                            String placerName, UUID targetUuid,
                                                            String targetName, double amount) {
        return SolidusBridge.subtractBalance(placer, amount).thenCompose(paid -> {
            if (paid == null || !Double.isFinite(paid) || paid < 0.0) {
                return CompletableFuture.completedFuture(BountyResult.fail(
                        "Insufficient balance — you need " + TextUtil.currency(amount).getString()));
            }

            EconomyMath.TaxSplit tax = this.config.isBloodTaxEnabled()
                    ? EconomyMath.bloodTax(amount, this.config.getBloodTaxRate(),
                            this.config.getTreasuryShare(), this.config.getBurnShare())
                    : new EconomyMath.TaxSplit(0.0, 0.0, 0.0, EconomyMath.round2(amount));
            double bountyAmount = tax.remainingBounty();
            if (bountyAmount <= 0.0) {
                return this.refundAndFail(placer, amount, "Tax consumed the entire bounty; payment refunded");
            }

            BountyEntry bounty = BountyEntry.create(targetUuid, targetName, bountyAmount,
                    placerUuid, placerName, this.config.getBountyDurationDays())
                    .withTax(bountyAmount, tax.totalTax());

            return this.storage.insertBounty(bounty).thenCompose(id -> {
                if (id == null || id <= 0) {
                    LOGGER.error("Bounty insert failed after payment — refunding {} ({})", placerName, amount);
                    return this.refundAndFail(placer, amount, "Bounty could not be recorded; payment refunded");
                }
                BountyEntry storedBounty = bounty.withId(id);
                return this.recordTaxMoves(tax, "bounty #" + id + " on " + targetName).thenApply(v ->
                        new BountyResult(true,
                                "Bounty of " + TextUtil.currency(bountyAmount).getString() + " placed on "
                                        + TextUtil.target(targetName).getString()
                                        + (tax.totalTax() > 0.0
                                                ? " (blood tax: " + TextUtil.currency(tax.totalTax()).getString() + ")"
                                                : ""),
                                amount, bountyAmount, storedBounty))
                        .exceptionallyCompose(error -> {
                            // Tax legs are transactional and fail-loud: nothing was
                            // ledgered. Roll the whole placement back — cancel the
                            // bounty (only while still unclaimed) and refund the placer.
                            LOGGER.error("CRITICAL: treasury tax recording failed for bounty #{} — rolling placement back",
                                    id, error);
                            return this.storage.cancelIfActive(id).thenCompose(cancelled -> {
                                if (!cancelled) {
                                    LOGGER.error("CRITICAL: bounty #{} was claimed during rollback — placer refund "
                                            + "would double-pay; manual reconciliation required (placer {} owed {})",
                                            id, placerName, amount);
                                    return CompletableFuture.completedFuture(BountyResult.fail(
                                            "Bounty claimed mid-rollback — contact an admin for refund " + amount));
                                }
                                return this.refundAndFail(placer, amount, "Bounty could not be finalised; payment refunded");
                            });
                        });
            });
        });
    }

    private CompletableFuture<BountyResult> refundAndFail(ServerPlayer placer, double amount, String message) {
        return SolidusBridge.addBalance(placer, amount).handle((refund, error) -> {
            if (error != null || refund == null || refund < 0.0) {
                LOGGER.error("REFUND FAILED for {} ({} S$) — manual intervention required", placer.getName().getString(), amount);
                return BountyResult.fail(message + " — CRITICAL: refund failed, contact an admin");
            }
            return BountyResult.fail(message);
        });
    }

    /**
     * Records the blood-tax legs. Both movements are transactional and fail-loud
     * (an exception propagates to the caller, which rolls the placement back);
     * the treasury mirror is refreshed from the returned DB snapshots only.
     */
    private CompletableFuture<Void> recordTaxMoves(EconomyMath.TaxSplit tax, String note) {
        CompletableFuture<TreasuryManager.TreasurySnapshot> treasuryMove =
                tax.treasuryAmount() > 0.0
                        ? this.storage.adjustTreasury(TreasuryManager.Category.TAX, tax.treasuryAmount(), note)
                        : CompletableFuture.completedFuture(this.treasury.snapshot());
        CompletableFuture<TreasuryManager.TreasurySnapshot> burnMove =
                tax.burnAmount() > 0.0
                        ? this.storage.adjustTreasury(TreasuryManager.Category.BURN, tax.burnAmount(), note)
                        : CompletableFuture.completedFuture(this.treasury.snapshot());
        return CompletableFuture.allOf(treasuryMove, burnMove)
                .thenRun(() -> {
                    treasuryMove.thenAccept(this.treasury::applySnapshot);
                    burnMove.thenAccept(this.treasury::applySnapshot);
                });
    }

    // ------------------------------------------------------------------
    // Decay + expiry + admin
    // ------------------------------------------------------------------

    public CompletableFuture<Integer> processContractFees() {
        if (!this.config.isContractFeesEnabled()) {
            return CompletableFuture.completedFuture(0);
        }
        return this.storage.getActiveBounties().thenCompose(bounties -> {
            int processed = 0;
            CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
            long graceMs = this.config.getContractGracePeriodMs();
            for (BountyEntry bounty : bounties) {
                if (bounty.autonomous()
                        || System.currentTimeMillis() - bounty.placedTimestamp() < graceMs) {
                    continue;
                }
                EconomyMath.FeeSplit fee = EconomyMath.contractFee(bounty.totalAmount(),
                        this.config.getContractDailyRate(), this.config.getContractMinimumBounty());
                if (fee.fee() <= 0.0) {
                    continue;
                }
                processed++;
                // One task, one transaction: bounty row + ledger + treasury row
                // move together or not at all (silent money drift is impossible).
                tail = tail.thenCompose(v -> this.storage.applyContractFee(
                                bounty.id(), fee.newAmount(), fee.fee(), "bounty #" + bounty.id())
                        .thenAccept(snapshot -> {
                            if (snapshot == null) {
                                LOGGER.error("CRITICAL: contract fee for bounty #{} failed atomically — skipped this cycle",
                                        bounty.id());
                            } else {
                                this.treasury.applySnapshot(snapshot);
                            }
                        }));
            }
            int count = processed;
            return tail.thenApply(ignored -> count);
        });
    }

    /**
     * Expires overdue player bounties and refunds each placer through the
     * offline bridge. Every refund is claimed in the database BEFORE the money
     * is attempted (prefer-loss-over-double-pay), so a crash mid-loop only
     * postpones the not-yet-attempted refunds — they are retried on the next
     * cycle and at startup. Failed refund attempts are logged with the bounty
     * id for manual recovery instead of vanishing silently.
     */
    public CompletableFuture<Integer> processExpirations() {
        return this.storage.expireOldBounties()
                .thenCompose(expired -> this.refundPending(0));
    }

    /** Recursive per-row claim-and-refund loop (crash-safe, see processExpirations). */
    private CompletableFuture<Integer> refundPending(int refunded) {
        return this.storage.claimNextRefundPending().thenCompose(bounty -> {
            if (bounty == null) {
                return CompletableFuture.completedFuture(refunded);
            }
            if (bounty.placedByUuid() == null) {
                return this.refundPending(refunded);
            }
            return SolidusBridge.addBalanceOffline(
                            bounty.placedByUuid(), bounty.placedByName(), bounty.totalAmount())
                    .handle((balance, error) -> {
                        if (error != null || balance == null || balance < 0.0) {
                            LOGGER.error("Expiry refund FAILED for bounty #{} ({} to {}) — manual recovery needed",
                                    bounty.id(), bounty.totalAmount(), bounty.placedByName(), error);
                        } else {
                            LOGGER.info("Expiry refund paid: bounty #{} -> {} ({})", bounty.id(),
                                    bounty.placedByName(), bounty.totalAmount());
                        }
                        return null;
                    })
                    .thenCompose(v -> this.refundPending(refunded + 1));
        });
    }

    /**
     * Admin cancellation: funds are confiscated to the treasury, never refunded.
     * The status transition and the treasury credit happen in one atomic
     * compare-and-set transaction — two concurrent cancels (or a cancel racing
     * a kill claim) can never double-credit the treasury.
     */
    public CompletableFuture<BountyResult> adminCancelBounty(int bountyId, String adminName) {
        return this.storage.getActiveBounties().thenCompose(bounties -> {
            BountyEntry target = bounties.stream().filter(b -> b.id() == bountyId).findFirst().orElse(null);
            if (target == null) {
                return CompletableFuture.completedFuture(BountyResult.fail(
                        "Bounty #" + bountyId + " not found or already resolved"));
            }
            return this.storage.confiscateBounties(List.of(bountyId),
                            java.util.EnumSet.of(BountyStatus.ACTIVE, BountyStatus.AUTONOMOUS),
                            target.totalAmount(), "bounty #" + bountyId + " cancelled by " + adminName)
                    .thenApply(snapshot -> {
                        if (snapshot == null) {
                            return BountyResult.fail(
                                    "Bounty #" + bountyId + " was resolved concurrently — nothing confiscated");
                        }
                        this.treasury.applySnapshot(snapshot);
                        return new BountyResult(true,
                                "Bounty #" + bountyId + " cancelled; "
                                        + TextUtil.currency(target.totalAmount()).getString()
                                        + " confiscated to treasury (no refund)", 0.0, target.totalAmount(), target);
                    });
        });
    }

    public CompletableFuture<List<BountyEntry>> getActiveBounties() {
        return this.storage.getActiveBounties();
    }

    public CompletableFuture<List<BountyEntry>> getBountiesForTarget(UUID targetUuid) {
        return this.storage.getBountiesForTarget(targetUuid);
    }

    public CompletableFuture<Double> getTotalBountyForTarget(UUID targetUuid) {
        return this.storage.getTotalBountyForTarget(targetUuid);
    }

    public record BountyResult(boolean success, String message, double amountPaid,
                               double bountyAmount, BountyEntry bounty) {
        public static BountyResult fail(String message) {
            return new BountyResult(false, message, 0.0, 0.0, null);
        }

        /** Compatibility for simple results without a bounty payload. */
        public BountyResult(boolean success, String message, double amountPaid, double bountyAmount) {
            this(success, message, amountPaid, bountyAmount, null);
        }
    }
}
