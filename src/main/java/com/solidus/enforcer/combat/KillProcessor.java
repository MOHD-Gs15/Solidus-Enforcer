package com.solidus.enforcer.combat;

import com.solidus.enforcer.bounty.BountyAnnouncer;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.EconomyMath;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.security.AntiExploitEngine;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a PvP kill into a settled bounty payout.
 *
 * Payout protocol (claim-first — fixes the old pay-then-mark double payout):
 * <ol>
 *   <li>Atomically CLAIM every payable bounty on the victim (single storage
 *       task on the serialized worker; a second simultaneous kill observes an
 *       empty set).</li>
 *   <li>Run anti-exploit checks (collusion + value drop on the DEATH-TIME
 *       inventory snapshot).</li>
 *   <li>Pay the split (damage pool by contribution, finishing pool to the
 *       killer) — online via addBalance, offline via addBalanceOffline.</li>
 *   <li>On any payment failure: REVERT the claim so the bounty returns to
 *       ACTIVE for the next kill; on success: record stats and announce.</li>
 * </ol>
 */
public final class KillProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final DamageTracker damageTracker;
    private final AntiExploitEngine antiExploit;
    private final BountyManager bountyManager;
    private final com.solidus.enforcer.economy.TreasuryManager treasury;

    public KillProcessor(EnforcerStorage storage, ConfigManager config, DamageTracker damageTracker,
                         AntiExploitEngine antiExploit, BountyManager bountyManager,
                         com.solidus.enforcer.economy.TreasuryManager treasury) {
        this.storage = storage;
        this.config = config;
        this.damageTracker = damageTracker;
        this.antiExploit = antiExploit;
        this.bountyManager = bountyManager;
        this.treasury = treasury;
    }

    /** Outcome of the payment fan-out: decides revert vs keep-claimed. */
    record PayoutVerdict(long succeeded, long failed) {
        /** Reverting is only safe while NO money has left the treasury-backed bounty. */
        boolean revertAll() {
            return this.failed > 0 && this.succeeded == 0;
        }

        static PayoutVerdict of(long succeeded, long failed) {
            return new PayoutVerdict(succeeded, failed);
        }
    }

    /**
     * @param inventorySnapshot gear value captured synchronously at death time
     */
    public void processKill(ServerPlayer victim, ServerPlayer killer, double inventorySnapshot, MinecraftServer server) {
        UUID victimUuid = victim.getUUID();
        this.storage.claimBountiesForTarget(victimUuid).thenAcceptAsync(claimed -> {
            if (claimed.isEmpty()) {
                return;
            }
            double totalBounty = claimed.stream().mapToDouble(BountyEntry::totalAmount).sum();
            if (!Double.isFinite(totalBounty) || totalBounty <= 0.0) {
                LOGGER.error("Claimed bounties for {} total {} — reverting claim", victimUuid, totalBounty);
                this.revert(claimed);
                return;
            }

            this.antiExploit.collusionDetector()
                    .checkForCollusion(killer.getUUID(), killer.getName().getString(),
                            victimUuid, victim.getName().getString())
                    .thenCompose(collusion -> this.antiExploit.evaluate(collusion, inventorySnapshot, totalBounty))
                    .thenCompose(exploitResult -> {
                        if (exploitResult.payoutRatio() <= 0.0) {
                            // Fraud: confiscate the claimed bounties to the treasury.
                            return this.confiscateClaimed(claimed, exploitResult, victim, killer, server)
                                    .thenApply(ignored -> (Boolean) null);
                        }
                        double payable = EconomyMath.round2(totalBounty * exploitResult.payoutRatio());
                        return this.damageTracker.getDamageContributions(victimUuid)
                                .thenCompose(contributions ->
                                        this.settlePayout(claimed, victim, killer, contributions,
                                                payable, exploitResult, totalBounty, server))
                                .thenApply(ignored -> (Boolean) null);
                    })
                    .exceptionally(error -> {
                        // Only PRE-PAYMENT failures reach here: settlePayout handles its
                        // own tail and never lets a post-payment failure revert paid money.
                        LOGGER.error("Bounty settlement failed for victim {} — reverting claim", victimUuid, error);
                        this.revert(claimed);
                        return null;
                    });
        });
    }

    private CompletableFuture<Void> settlePayout(List<BountyEntry> claimed, ServerPlayer victim,
                                                 ServerPlayer killer, Map<UUID, Double> contributions,
                                                 double payable, AntiExploitEngine.ExploitCheckResult exploitResult,
                                                 double totalBounty, MinecraftServer server) {
        EconomyMath.PayoutSplit split = EconomyMath.allianceSplit(payable,
                this.config.getAllianceDamageShare(), this.config.getAllianceFinishingBonus());

        LinkedHashMap<UUID, Double> payouts = EconomyMath.damageShares(contributions, split.damagePool());
        payouts.merge(killer.getUUID(), split.finishingPool(), Double::sum);

        return this.damageTracker.getAttackerNames(victim.getUUID()).thenCompose(names -> {
            names.putIfAbsent(killer.getUUID(), killer.getName().getString());

            List<CompletableFuture<Boolean>> payments = payouts.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && Double.isFinite(entry.getValue()) && entry.getValue() > 0.0)
                    .map(entry -> this.payRecipient(entry.getKey(), names.get(entry.getKey()),
                            entry.getValue(), victim, server))
                    .toList();

            // Compose the payment fan-out so that its failure handling knows exactly
            // how much money has already left — reverting a PARTIALLY paid claim would
            // re-arm the bounties and re-pay the successful recipients on the next kill.
            return CompletableFuture.allOf(payments.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        long succeeded = payments.stream().filter(p -> p.getNow(false)).count();
                        return PayoutVerdict.of(succeeded, payments.size() - succeeded);
                    })
                    .thenCompose(verdict -> {
                        if (verdict.failed() == 0) {
                            return this.finalizeSettlement(claimed, victim, killer, payouts,
                                    exploitResult, totalBounty, payable, server);
                        }
                        if (verdict.revertAll()) {
                            // Nothing was paid — safe to put the bounties back up for grabs.
                            LOGGER.error("All bounty payments failed for victim {} — reverting {} bounties to ACTIVE",
                                    victim.getUUID(), claimed.size());
                            this.revert(claimed);
                            return CompletableFuture.completedFuture((Void) null);
                        }
                        // Partial payout: money has left. The claim stays CLAIMED so it can
                        // never be re-paid; unpaid shares require manual reconciliation.
                        LOGGER.error("CRITICAL: partial bounty payout for victim {} — {} of {} payments failed; "
                                        + "bounties stay CLAIMED (ids {}) for manual reconciliation; damage records kept as evidence",
                                victim.getUUID(), verdict.failed(), verdict.succeeded() + verdict.failed(),
                                claimed.stream().map(BountyEntry::id).toList());
                        return CompletableFuture.completedFuture((Void) null);
                    });
        });
    }

    /**
     * Post-payment bookkeeping. Every stage is exception-safe: a failure here is
     * logged loudly but NEVER reverts the claim — the money has already moved.
     */
    private CompletableFuture<Void> finalizeSettlement(List<BountyEntry> claimed, ServerPlayer victim,
                                                        ServerPlayer killer, LinkedHashMap<UUID, Double> payouts,
                                                        AntiExploitEngine.ExploitCheckResult exploitResult,
                                                        double totalBounty, double payable, MinecraftServer server) {
        return this.storage.recordKill(killer.getUUID(), killer.getName().getString(),
                        victim.getUUID(), victim.getName().getString())
                .handle((ignored, error) -> {
                    if (error != null) {
                        LOGGER.error("Kill stats recording failed for victim {} (payout already settled)",
                                victim.getUUID(), error);
                    }
                    return null;
                })
                .thenRun(() -> {
                    try {
                        this.damageTracker.clearRecords(victim.getUUID());
                    } catch (RuntimeException ex) {
                        LOGGER.error("Damage record cleanup failed for victim {} (payout already settled)",
                                victim.getUUID(), ex);
                    }
                })
                .thenAccept(v -> {
                    try {
                        server.execute(() -> BountyAnnouncer.announceClaim(
                                claimed, victim, killer, totalBounty, payable, exploitResult, server,
                                payouts));
                    } catch (RuntimeException rejected) {
                        LOGGER.error("Claim announcement skipped (server shutting down?) — payout already settled",
                                rejected);
                    }
                });
    }

    /** Collusion path: bounties are cancelled and their value confiscated (atomic CAS). */
    private CompletableFuture<Void> confiscateClaimed(List<BountyEntry> claimed,
                                                      AntiExploitEngine.ExploitCheckResult exploitResult,
                                                      ServerPlayer victim, ServerPlayer killer, MinecraftServer server) {
        double confiscated = claimed.stream().mapToDouble(BountyEntry::totalAmount).sum();
        List<Integer> ids = claimed.stream().map(BountyEntry::id).toList();
        return this.storage.confiscateBounties(ids, EnumSet.of(BountyStatus.CLAIMED), confiscated,
                        "collusion: bounty on " + victim.getName().getString())
                .thenAccept(snapshot -> {
                    if (snapshot == null) {
                        LOGGER.error("CRITICAL: collusion confiscation failed for bounties {} — "
                                + "rows keep their status; manual reconciliation required", ids);
                    } else {
                        this.treasury.applySnapshot(snapshot);
                    }
                })
                .thenRun(() -> {
                    try {
                        server.execute(() -> BountyAnnouncer.announceDenial(
                                victim, killer, exploitResult.message(), confiscated, server));
                    } catch (RuntimeException rejected) {
                        LOGGER.error("Denial announcement skipped (server shutting down?)", rejected);
                    }
                });
    }

    /** Compensation path: puts an interrupted payout back up for grabs. */
    private void revert(List<BountyEntry> claimed) {
        this.storage.revertClaim(claimed.stream().map(BountyEntry::id).toList());
    }

    private CompletableFuture<Boolean> payRecipient(UUID recipientUuid, String recipientName,
                                                    double amount, ServerPlayer victim, MinecraftServer server) {
        ServerPlayer online = server.getPlayerList().getPlayer(recipientUuid);
        String name = recipientName == null ? "Unknown" : recipientName;
        CompletableFuture<Double> payment = online != null
                ? SolidusBridge.addBalance(online, amount)
                : SolidusBridge.addBalanceOffline(recipientUuid, name, amount);
        return payment.handle((newBalance, error) -> {
            if (error != null || newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                LOGGER.error("Failed to pay bounty share {} to {} ({})", amount, name, recipientUuid, error);
                return false;
            }
            if (online != null) {
                server.execute(() -> online.sendSystemMessage(TextUtil.prefix()
                        .append(Component.literal("Bounty Reward! ").withColor(TextUtil.COLOR_GOOD))
                        .append(TextUtil.currency(amount))
                        .append(Component.literal(" for eliminating ").withColor(TextUtil.COLOR_INFO))
                        .append(TextUtil.target(victim.getName().getString()))));
            }
            return true;
        });
    }
}
