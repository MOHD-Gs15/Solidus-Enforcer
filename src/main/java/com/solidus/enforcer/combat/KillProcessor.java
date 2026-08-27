package com.solidus.enforcer.combat;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.security.AntiExploitEngine;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KillProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");
    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final DamageTracker damageTracker;
    private final AntiExploitEngine antiExploit;
    private final TreasuryManager treasury;
    private final HunterLicenseManager licenseManager;

    public KillProcessor(EnforcerStorage storage, ConfigManager config, DamageTracker damageTracker,
                         AntiExploitEngine antiExploit, TreasuryManager treasury,
                         HunterLicenseManager licenseManager) {
        this.storage = storage;
        this.config = config;
        this.damageTracker = damageTracker;
        this.antiExploit = antiExploit;
        this.treasury = treasury;
        this.licenseManager = licenseManager;
    }

    public void processKill(ServerPlayer victim, ServerPlayer killer, MinecraftServer server) {
        UUID victimUuid = victim.getUUID();
        this.storage.getBountiesForTarget(victimUuid).thenAcceptAsync(bounties -> {
            if (bounties.isEmpty()) {
                return;
            }
            double totalBounty = bounties.stream().mapToDouble(BountyEntry::totalAmount).sum();
            if (!Double.isFinite(totalBounty) || totalBounty <= 0.0) {
                LOGGER.error("Ignoring invalid total bounty {} for victim {}", totalBounty, victimUuid);
                return;
            }
            this.antiExploit.runAllChecks(victim, killer, totalBounty)
                .thenComposeAsync(exploitResult -> this.damageTracker.getDamageContributions(victimUuid)
                    .thenComposeAsync(contributions -> {
                        double adjustedBounty = totalBounty * exploitResult.payoutRatio();
                        return this.distributeReward(victim, killer, contributions, adjustedBounty, exploitResult, server);
                    }))
                .thenAcceptAsync(paid -> server.execute(() -> {
                    if (!paid) {
                        LOGGER.error("Bounty payout failed for victim {}; leaving bounties claimable for recovery", victimUuid);
                        return;
                    }
                    for (BountyEntry bounty : bounties) {
                        this.storage.updateBountyStatus(bounty.id(), BountyStatus.CLAIMED);
                    }
                    this.damageTracker.clearRecords(victimUuid);
                    this.storage.recordKill(killer.getUUID(), killer.getName().getString(), victimUuid, victim.getName().getString());
                }))
                .exceptionally(error -> {
                    LOGGER.error("Bounty processing failed for victim {}", victimUuid, error);
                    return null;
                });
        });
    }

    private CompletableFuture<Boolean> distributeReward(ServerPlayer victim, ServerPlayer killer,
                                                          Map<UUID, Double> damageContributions,
                                                          double totalReward,
                                                          AntiExploitEngine.ExploitCheckResult exploitResult,
                                                          MinecraftServer server) {
        if (!SolidusBridge.isAvailable() || !Double.isFinite(totalReward) || totalReward <= 0.0) {
            LOGGER.error("Cannot distribute invalid reward or use unavailable Solidus Core: {}", totalReward);
            return CompletableFuture.completedFuture(false);
        }
        double damageShare = clamp(this.config.getAllianceDamageShare(), 0.0, 1.0);
        double finishingShare = clamp(this.config.getAllianceFinishingBonus(), 0.0, 1.0);
        double shareTotal = damageShare + finishingShare;
        if (shareTotal <= 0.0) {
            finishingShare = 1.0;
            damageShare = 0.0;
        } else if (shareTotal > 1.0) {
            damageShare /= shareTotal;
            finishingShare /= shareTotal;
        }
        double totalDamage = damageContributions.values().stream()
            .filter(value -> value != null && Double.isFinite(value) && value > 0.0)
            .mapToDouble(Double::doubleValue)
            .sum();
        if (totalDamage <= 0.0) {
            return payRecipient(killer.getUUID(), killer, totalReward, victim, server)
                .thenApply(paid -> {
                    if (paid) {
                        server.execute(() -> this.announceClaim(victim, killer, totalReward, exploitResult, server));
                    }
                    return paid;
                });
        }

        double damagePool = totalReward * damageShare;
        double finishingPool = totalReward * finishingShare;
        LinkedHashMap<UUID, Double> payouts = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : damageContributions.entrySet()) {
            double attackerDamage = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!Double.isFinite(attackerDamage) || attackerDamage <= 0.0) {
                continue;
            }
            payouts.merge(entry.getKey(), damagePool * attackerDamage / totalDamage, Double::sum);
        }
        payouts.merge(killer.getUUID(), finishingPool, Double::sum);

        List<CompletableFuture<Boolean>> payments = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : payouts.entrySet()) {
            double amount = entry.getValue();
            if (!Double.isFinite(amount) || amount <= 0.0) {
                return CompletableFuture.completedFuture(false);
            }
            ServerPlayer recipient = server.getPlayerList().getPlayer(entry.getKey());
            payments.add(payRecipient(entry.getKey(), recipient, amount, victim, server));
        }
        return CompletableFuture.allOf(payments.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> {
                boolean paid = payments.stream().allMatch(CompletableFuture::join);
                if (paid) {
                    server.execute(() -> this.announceClaim(victim, killer, totalReward, exploitResult, server));
                }
                return paid;
            });
    }

    private CompletableFuture<Boolean> payRecipient(UUID recipientUuid, ServerPlayer recipient,
                                                     double amount, ServerPlayer victim,
                                                     MinecraftServer server) {
        CompletableFuture<Double> payment = recipient != null
            ? SolidusBridge.addBalance(recipient, amount)
            : SolidusBridge.addBalanceOffline(recipientUuid, "Unknown", amount);
        return payment.handle((newBalance, error) -> {
            if (error != null || newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                LOGGER.error("Failed to pay bounty recipient {} amount {}", recipientUuid, amount, error);
                return false;
            }
            if (recipient != null) {
                server.execute(() -> recipient.sendSystemMessage(TextUtil.prefix()
                    .append(Component.literal("Bounty Reward! ").withColor(0x55FF55))
                    .append(TextUtil.currency(amount))
                    .append(Component.literal(" for eliminating ").withColor(0xAAAAAA))
                    .append(TextUtil.target(victim.getName().getString()))));
            }
            return true;
        });
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private void announceClaim(ServerPlayer victim, ServerPlayer killer, double totalReward,
                               AntiExploitEngine.ExploitCheckResult exploitResult, MinecraftServer server) {
        MutableComponent announcement = TextUtil.bountyIcon()
            .append(Component.literal("BOUNTY CLAIMED!").withColor(0xFF3333))
            .append(Component.literal("\n"))
            .append(TextUtil.separator())
            .append(Component.literal("\n  Target: ").withColor(0xAAAAAA))
            .append(TextUtil.target(victim.getName().getString()))
            .append(Component.literal("\n  Eliminated by: ").withColor(0xAAAAAA))
            .append(TextUtil.player(killer.getName().getString()))
            .append(Component.literal("\n  Reward: ").withColor(0xAAAAAA))
            .append(TextUtil.currency(totalReward));
        if (!exploitResult.legitimate()) {
            announcement = announcement.append(Component.literal("\n  Note: ").withColor(0xFFAA00))
                .append(Component.literal(exploitResult.message()).withColor(0xFFAA00));
        }
        announcement = announcement.append(Component.literal("\n")).append(TextUtil.separator());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(announcement);
        }
    }
}
