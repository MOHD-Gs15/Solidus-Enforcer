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
                                    .thenApply(ignored -> true);
                        }
                        double payable = EconomyMath.round2(totalBounty * exploitResult.payoutRatio());
                        return this.damageTracker.getDamageContributions(victimUuid)
                                .thenCompose(contributions ->
                                        this.settlePayout(claimed, victim, killer, contributions,
                                                payable, exploitResult, totalBounty, server));
                    })
                    .exceptionally(error -> {
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

            return CompletableFuture.allOf(payments.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> payments.stream().allMatch(CompletableFuture::join))
                    .thenCompose(allPaid -> {
                        if (!allPaid) {
                            LOGGER.error("Partial payout failure for victim {} — reverting {} bounties",
                                    victim.getUUID(), claimed.size());
                            this.revert(claimed);
                            return CompletableFuture.completedFuture(false);
                        }
                        return this.storage.recordKill(killer.getUUID(), killer.getName().getString(),
                                        victim.getUUID(), victim.getName().getString())
                                .thenCompose(v -> this.damageTracker.clearRecords(victim.getUUID()))
                                .thenRun(() -> server.execute(() -> BountyAnnouncer.announceClaim(
                                        claimed, victim, killer, totalBounty, payable, exploitResult, server,
                                        payouts)))
                                .thenApply(v -> true);
                    });
        });
    }

    /** Collusion path: bounties are cancelled and their value confiscated. */
    private CompletableFuture<Void> confiscateClaimed(List<BountyEntry> claimed,
                                                      AntiExploitEngine.ExploitCheckResult exploitResult,
                                                      ServerPlayer victim, ServerPlayer killer, MinecraftServer server) {
        double confiscated = claimed.stream().mapToDouble(BountyEntry::totalAmount).sum();
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        for (BountyEntry bounty : claimed) {
            tail = tail.thenCompose(v -> this.storage.updateBountyStatus(bounty.id(), BountyStatus.CANCELLED));
        }
        return tail.thenCompose(v -> this.storage.adjustTreasury(
                        com.solidus.enforcer.economy.TreasuryManager.Category.CONFISCATION, confiscated,
                        "collusion: bounty on " + victim.getName().getString()))
                .thenAccept(this.treasury::applySnapshot)
                .thenRun(() -> server.execute(() -> BountyAnnouncer.announceDenial(
                        victim, killer, exploitResult.message(), confiscated, server)));
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
