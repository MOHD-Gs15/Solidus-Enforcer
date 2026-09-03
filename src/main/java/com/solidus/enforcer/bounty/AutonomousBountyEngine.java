package com.solidus.enforcer.bounty;

import com.solidus.enforcer.economy.EconomyMath;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places server-funded bounties on destabilising players: wealth monopolists
 * and rampage streaks.
 *
 * Repairs over the previous build:
 * <ul>
 *   <li>Core's BalanceEntry has no UUID — the old code called
 *       {@code UUID.fromString(null)} and every monopoly bounty silently died.
 *       Monopoly targets are now resolved from online players by name.</li>
 *   <li>Funding is atomic (read + deduct in one storage task) — the treasury
 *       can never be overdrawn by two cycles.</li>
 *   <li>De-duplication uses the bounty's reason category instead of a fragile
 *       string split on the formatted reason.</li>
 * </ul>
 */
public final class AutonomousBountyEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final TreasuryManager treasury;
    private final BountyAnnouncer announcer;

    public AutonomousBountyEngine(EnforcerStorage storage, ConfigManager config, TreasuryManager treasury,
                                  BountyAnnouncer announcer) {
        this.storage = storage;
        this.config = config;
        this.treasury = treasury;
        this.announcer = announcer;
    }

    public void runCheckCycle(MinecraftServer server) {
        if (!this.config.isAutonomousBountiesEnabled()) {
            return;
        }
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
                double wealthRatio = entry.balance() / totalWealth;
                if (wealthRatio < threshold) {
                    continue;
                }
                // Core's BalanceEntry carries no UUID; resolve online players by name.
                ServerPlayer online = server.getPlayerList().getPlayerByName(entry.playerName());
                if (online == null) {
                    LOGGER.debug("Monopoly candidate {} is offline — skipped this cycle", entry.playerName());
                    continue;
                }
                String reason = String.format("Wealth Monopoly: %.0f%% of tracked server wealth",
                        wealthRatio * 100.0);
                this.checkAndPlaceAutonomousBounty(online.getUUID(), entry.playerName(), reason,
                        this.config.getMonopolyBountyBase(), server);
            }
        }).exceptionally(error -> {
            LOGGER.error("Monopoly check cycle failed", error);
            return null;
        });
    }

    private void checkKillStreakPlayers(MinecraftServer server) {
        this.storage.getTopKillers(20).thenAccept(killers -> {
            for (EnforcerStorage.KillStatsEntry entry : killers) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(entry.uuid());
                } catch (Exception e) {
                    continue;
                }
                if (entry.currentStreak() >= this.config.getKillStreakThreshold()) {
                    String reason = String.format("Rampage: %d kills without dying", entry.currentStreak());
                    this.checkAndPlaceAutonomousBounty(uuid, entry.name(), reason,
                            this.config.getKillStreakBountyBase() * (1.0 + entry.currentStreak() / 10.0), server);
                    continue;
                }
                double kd = entry.deaths() == 0 ? entry.kills() : (double) entry.kills() / entry.deaths();
                if (kd >= this.config.getKdRatioThreshold() && entry.kills() >= this.config.getMinKillsForKdCheck()) {
                    String reason = String.format("Dangerous Player: K/D %.1f (%d kills)", kd, entry.kills());
                    this.checkAndPlaceAutonomousBounty(uuid, entry.name(), reason,
                            this.config.getKillStreakBountyBase() * kd, server);
                }
            }
        }).exceptionally(error -> {
            LOGGER.error("Kill-streak check cycle failed", error);
            return null;
        });
    }

    private void checkAndPlaceAutonomousBounty(UUID targetUuid, String targetName, String reason,
                                               double baseAmount, MinecraftServer server) {
        this.storage.getBountiesForTarget(targetUuid).thenAccept(existing -> {
            String category = reason.contains(":") ? reason.substring(0, reason.indexOf(':')).trim() : reason.trim();
            boolean alreadyPlaced = existing.stream()
                    .filter(BountyEntry::autonomous)
                    .anyMatch(b -> b.reasonCategory().equalsIgnoreCase(category));
            if (alreadyPlaced) {
                return;
            }
            double suggested = EconomyMath.suggestedAutoBounty(baseAmount, this.treasury.getBalance(),
                    this.config.getMinAutoBounty(), this.config.getMaxAutoBounty());
            this.storage.tryFundAutonomousBounty(suggested, "auto bounty on " + targetName)
                    .thenAccept(snapshot -> {
                        if (snapshot == null) {
                            LOGGER.info("Treasury cannot fund autonomous bounty on {} (needs {})",
                                    targetName, suggested);
                            return;
                        }
                        this.treasury.applySnapshot(snapshot);
                        BountyEntry bounty = BountyEntry.createAutonomous(targetUuid, targetName, suggested,
                                reason, this.config.getBountyDurationDays());
                        this.storage.insertBounty(bounty).thenAccept(id -> {
                            if (id == null || id <= 0) {
                                // Roll the funding back so the money is not lost: AUTO_REFUND
                                // restores the balance and rolls the paid-bounty stat back.
                                this.storage.adjustTreasury(TreasuryManager.Category.AUTO_REFUND, suggested,
                                                "auto bounty insert failed: " + targetName)
                                        .handle((restored, rollbackError) -> {
                                            if (rollbackError != null) {
                                                LOGGER.error("CRITICAL: auto-bounty funding rollback failed for {} ({}) "
                                                        + "— treasury short by {}; manual reconciliation required",
                                                        targetName, suggested, suggested, rollbackError);
                                            } else {
                                                this.treasury.applySnapshot(restored);
                                            }
                                            return null;
                                        });
                                LOGGER.error("Failed to persist autonomous bounty on {} — funding refunded", targetName);
                                return;
                            }
                            BountyEntry placed = bounty.withId(id);
                            this.announcer.announceAutonomousBounty(placed, server);
                            LOGGER.info("Autonomous bounty placed: S$ {} on {} — {}", suggested, targetName, reason);
                        });
                    });
        });
    }
}
