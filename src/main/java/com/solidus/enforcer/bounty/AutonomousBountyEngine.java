/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.server.MinecraftServer
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer.bounty;

import com.solidus.enforcer.bounty.BountyAnnouncer;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutonomousBountyEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus-Enforcer");
    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final TreasuryManager treasury;
    private final BountyManager bountyManager;

    public AutonomousBountyEngine(EnforcerStorage storage, ConfigManager config, TreasuryManager treasury, BountyManager bountyManager) {
        this.storage = storage;
        this.config = config;
        this.treasury = treasury;
        this.bountyManager = bountyManager;
    }

    public void runCheckCycle(MinecraftServer server) {
        if (!this.config.isAutonomousBountiesEnabled()) {
            return;
        }
        LOGGER.debug("Running autonomous bounty check cycle...");
        this.checkMonopolyPlayers(server);
        this.checkKillStreakPlayers(server);
    }

    private void checkMonopolyPlayers(MinecraftServer server) {
        if (!SolidusBridge.isAvailable()) {
            return;
        }
        SolidusBridge.getTopBalances(100).thenAccept(topBalances -> {
            if (topBalances.isEmpty()) {
                return;
            }
            double totalWealth = topBalances.stream().mapToDouble(SolidusBridge.BalanceEntryData::balance).sum();
            if (totalWealth <= 0.0) {
                return;
            }
            double threshold = this.config.getWealthMonopolyThreshold();
            for (SolidusBridge.BalanceEntryData entry : topBalances) {
                UUID uuid;
                double wealthRatio = entry.balance() / totalWealth;
                if (!(wealthRatio >= threshold)) continue;
                try {
                    uuid = UUID.fromString(entry.uuid());
                }
                catch (Exception e) {
                    continue;
                }
                this.checkAndPlaceAutonomousBounty(uuid, entry.playerName(), String.format("Wealth Monopoly: %.0f%% of server wealth", wealthRatio * 100.0), this.config.getMonopolyBountyBase(), server);
            }
        });
    }

    private void checkKillStreakPlayers(MinecraftServer server) {
        this.storage.getTopKillers(20).thenAccept(killers -> {
            for (EnforcerStorage.KillStatsEntry entry : killers) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(entry.uuid());
                }
                catch (Exception e) {
                    continue;
                }
                if (entry.currentStreak() >= this.config.getKillStreakThreshold()) {
                    this.checkAndPlaceAutonomousBounty(uuid, entry.name(), String.format("Kill Streak: %d consecutive kills", entry.currentStreak()), this.config.getKillStreakBountyBase() * (1.0 + (double)entry.currentStreak() / 10.0), server);
                    continue;
                }
                double d = entry.deaths() == 0 ? (double)entry.kills() : (double)entry.kills() / (double)entry.deaths();
                double kd = d;
                if (!(kd >= this.config.getKdRatioThreshold()) || entry.kills() < 10) continue;
                this.checkAndPlaceAutonomousBounty(uuid, entry.name(), String.format("Dangerous Player: K/D %.1f (%d kills)", kd, entry.kills()), this.config.getKillStreakBountyBase() * kd, server);
            }
        });
    }

    private void checkAndPlaceAutonomousBounty(UUID targetUuid, String targetName, String reason, double baseAmount, MinecraftServer server) {
        this.storage.getBountiesForTarget(targetUuid).thenAccept(existingBounties -> {
            boolean alreadyHasAutoBounty = existingBounties.stream().anyMatch(b -> b.autonomous() && b.autonomousReason() != null && b.autonomousReason().contains(reason.split(":")[0]));
            if (alreadyHasAutoBounty) {
                return;
            }
            double amount = this.treasury.getSuggestedAutoBounty(baseAmount);
            if (!this.treasury.fundAutonomousBounty(amount)) {
                LOGGER.info("Treasury insufficient for autonomous bounty on {} (need {})", (Object)targetName, (Object)amount);
                return;
            }
            BountyEntry bounty = BountyEntry.createAutonomous(targetUuid, targetName, amount, reason, this.config.getBountyDurationDays());
            this.storage.insertBounty(bounty).thenAccept(id -> {
                if (id == null || id <= 0) {
                    this.treasury.refundAutonomousBounty(amount);
                    LOGGER.error("Failed to persist autonomous bounty on {}. Treasury amount refunded.", targetName);
                    return;
                }
                this.storage.addToTreasury(amount, "paid");
                server.execute(() -> BountyAnnouncer.announceAutonomousBounty(new BountyEntry(id, targetUuid, targetName, amount, amount, 0.0, 0.0, null, "SERVER", System.currentTimeMillis(), System.currentTimeMillis() + (long)this.config.getBountyDurationDays() * 24L * 60L * 60L * 1000L, BountyStatus.AUTONOMOUS, true, reason), server));
                LOGGER.info("Autonomous bounty placed: {} S$ on {} - {}", new Object[]{amount, targetName, reason});
            });
        });
    }
}
