/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerPlayer
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer.bounty;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BountyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus-Enforcer");
    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final TreasuryManager treasury;

    public BountyManager(EnforcerStorage storage, ConfigManager config, TreasuryManager treasury) {
        this.storage = storage;
        this.config = config;
        this.treasury = treasury;
    }

    public CompletableFuture<BountyResult> placeBounty(ServerPlayer placer, ServerPlayer target, double amount) {
        UUID targetUuid;
        String targetName = target.getName().getString();
        String placerName = placer.getName().getString();
        UUID placerUuid = placer.getUUID();
        if (placerUuid.equals(targetUuid = target.getUUID())) {
            return CompletableFuture.completedFuture(new BountyResult(false, "You cannot place a bounty on yourself!", 0.0, 0.0));
        }
        if (!Double.isFinite(amount) || amount <= 0.0) {
            return CompletableFuture.completedFuture(new BountyResult(false, "Bounty amount must be a finite positive number", amount, 0.0));
        }
        if (amount < this.config.getMinBounty()) {
            return CompletableFuture.completedFuture(new BountyResult(false, "Minimum bounty is " + TextUtil.currency(this.config.getMinBounty()).getString(), amount, 0.0));
        }
        if (amount > this.config.getMaxBounty()) {
            return CompletableFuture.completedFuture(new BountyResult(false, "Maximum bounty is " + TextUtil.currency(this.config.getMaxBounty()).getString(), amount, 0.0));
        }
        if (!SolidusBridge.isAvailable()) {
            return CompletableFuture.completedFuture(new BountyResult(false, "Economy system not available", amount, 0.0));
        }
        return this.storage.getActiveBountyCountByPlacer(placerUuid).thenComposeAsync(activeCount -> {
            if (activeCount >= this.config.getMaxActivePerPlayer()) {
                return CompletableFuture.completedFuture(new BountyResult(false, "You already have " + activeCount + " active bounties (max: " + this.config.getMaxActivePerPlayer() + ")", amount, 0.0));
            }
            return this.storage.getActiveBountyCountForTarget(targetUuid).thenComposeAsync(targetCount -> {
                if (targetCount >= this.config.getMaxBountiesPerTarget()) {
                    return CompletableFuture.completedFuture(new BountyResult(false, targetName + " already has " + targetCount + " bounties (max: " + this.config.getMaxBountiesPerTarget() + ")", amount, 0.0));
                }
                final double amountToDeduct = amount;
                return SolidusBridge.hasSufficientBalance(placer, amountToDeduct).thenComposeAsync(hasFunds -> {
                    if (!hasFunds.booleanValue()) {
                        return CompletableFuture.completedFuture(new BountyResult(false, "Insufficient balance. You need " + TextUtil.currency(amountToDeduct).getString(), amountToDeduct, 0.0));
                    }
                    return SolidusBridge.subtractBalance(placer, amountToDeduct).thenComposeAsync(newBalance -> {
                        if (newBalance == null || newBalance < 0.0) {
                            return CompletableFuture.completedFuture(new BountyResult(false, "Payment failed", amountToDeduct, 0.0));
                        }
                        TreasuryManager.TaxResult taxResult = this.treasury.processBloodTax(amountToDeduct);
                        double bountyAmount = taxResult.remainingBounty();
                        if (!Double.isFinite(bountyAmount) || bountyAmount <= 0.0) {
                            return SolidusBridge.addBalance(placer, amountToDeduct)
                                .thenApply(refundBalance -> new BountyResult(false,
                                    refundBalance != null && refundBalance >= 0.0
                                        ? "Bounty amount became invalid; payment refunded"
                                        : "Critical error: invalid bounty amount and refund failed",
                                    amountToDeduct, 0.0));
                        }
                        BountyEntry bounty = BountyEntry.create(targetUuid, targetName, bountyAmount, placerUuid, placerName, this.config.getBountyDurationDays());
                        bounty = new BountyEntry(bounty.id(), bounty.targetUuid(), bounty.targetName(), bounty.totalAmount(), bounty.originalAmount(), taxResult.totalTax(), bounty.contractFeesDeducted(), bounty.placedByUuid(), bounty.placedByName(), bounty.placedTimestamp(), bounty.expireTimestamp(), bounty.status(), bounty.autonomous(), bounty.autonomousReason());
                        return this.storage.insertBounty(bounty).thenCompose(id -> {
                            if (id == null || id <= 0) {
                                return SolidusBridge.addBalance(placer, amountToDeduct).thenApply(refundBalance -> {
                                    if (refundBalance == null || refundBalance < 0.0) {
                                        return new BountyResult(false, "Critical error: bounty save failed and refund failed", amountToDeduct, 0.0);
                                    }
                                    return new BountyResult(false, "Bounty save failed; payment refunded", amountToDeduct, 0.0);
                                });
                            }
                            this.treasury.applyTax(taxResult);
                            if (taxResult.treasuryAmount() > 0.0) {
                                this.storage.addToTreasury(taxResult.treasuryAmount(), "tax");
                            }
                            if (taxResult.burnAmount() > 0.0) {
                                this.storage.addToTreasury(taxResult.burnAmount(), "burn");
                            }
                            return CompletableFuture.completedFuture(new BountyResult(true, "Bounty of " + TextUtil.currency(bountyAmount).getString() + " placed on " + targetName + " (Tax: " + TextUtil.currency(taxResult.totalTax()).getString() + ")", amountToDeduct, bountyAmount));
                        });
                    });
                });
            });
        });
    }

    public CompletableFuture<BountyResult> adminCancelBounty(int bountyId) {
        return this.storage.getActiveBounties().thenComposeAsync(bounties -> {
            BountyEntry target = null;
            for (BountyEntry b : bounties) {
                if (b.id() != bountyId) continue;
                target = b;
                break;
            }
            if (target == null) {
                return CompletableFuture.completedFuture(new BountyResult(false, "Bounty #" + bountyId + " not found or already resolved", 0.0, 0.0));
            }
            double confiscatedAmount = target.totalAmount();
            this.treasury.loadFromStorage(this.treasury.getBalance() + confiscatedAmount, this.treasury.getTotalCollectedTax(), this.treasury.getTotalBurned(), this.treasury.getTotalPaidBounties());
            this.storage.addToTreasury(confiscatedAmount, "tax");
            return this.storage.updateBountyStatus(bountyId, BountyStatus.CANCELLED).thenApply(v -> new BountyResult(true, "Bounty #" + bountyId + " cancelled. " + TextUtil.currency(confiscatedAmount).getString() + " moved to treasury (no refund issued)", 0.0, confiscatedAmount));
        });
    }

    public CompletableFuture<Integer> processContractFees() {
        if (!this.config.isContractFeesEnabled()) {
            return CompletableFuture.completedFuture(0);
        }
        return this.storage.getActiveBounties().thenComposeAsync(bounties -> {
            int processed = 0;
            long gracePeriodMs = (long)this.config.getContractGracePeriodHours() * 60L * 60L * 1000L;
            for (BountyEntry bounty : bounties) {
                TreasuryManager.ContractFeeResult feeResult;
                if (System.currentTimeMillis() - bounty.placedTimestamp() < gracePeriodMs || bounty.autonomous() || !((feeResult = this.treasury.processContractFee(bounty.totalAmount())).feeDeducted() > 0.0)) continue;
                this.storage.updateBountyAmount(bounty.id(), feeResult.newAmount(), feeResult.feeDeducted());
                ++processed;
            }
            int count = processed;
            return CompletableFuture.completedFuture(count);
        });
    }

    public CompletableFuture<Integer> processExpirations() {
        return this.storage.getActiveBounties().thenComposeAsync(bounties -> {
            int expired = 0;
            for (BountyEntry bounty : bounties) {
                if (!bounty.isExpired()) continue;
                if (SolidusBridge.isAvailable() && bounty.placedByUuid() != null && !bounty.autonomous()) {
                    SolidusBridge.addBalanceOffline(bounty.placedByUuid(), bounty.placedByName(), bounty.totalAmount());
                }
                this.storage.updateBountyStatus(bounty.id(), BountyStatus.EXPIRED);
                ++expired;
            }
            int count = expired;
            return CompletableFuture.completedFuture(count);
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

    public record BountyResult(boolean success, String message, double amountPaid, double bountyAmount) {
    }
}
