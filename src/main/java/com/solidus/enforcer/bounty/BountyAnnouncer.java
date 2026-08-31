package com.solidus.enforcer.bounty;

import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.TextUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * All announcements are fully asynchronous — the old build called
 * {@code .join()} on storage futures from the tick thread, which could stall
 * the server every time a bounty was announced. Intel lines (K/D, wealth,
 * playtime) are composed per viewer through async chains and delivered in a
 * single message.
 */
public final class BountyAnnouncer {
    private final EnforcerStorage storage;
    private final HunterLicenseManager licenseManager;

    public BountyAnnouncer(EnforcerStorage storage, HunterLicenseManager licenseManager) {
        this.storage = storage;
        this.licenseManager = licenseManager;
    }

    // ------------------------------------------------------------------
    // New bounty (license-gated intel per viewer)
    // ------------------------------------------------------------------

    public void announceNewBounty(BountyEntry bounty, MinecraftServer server) {
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            this.sendIntelMessage(viewer, server, base -> base
                    .append(Component.literal("NEW BOUNTY!").withColor(TextUtil.COLOR_ACCENT))
                    .append(Component.literal("\n")).append(TextUtil.separator())
                    .append(Component.literal("\n  Target: ").withColor(TextUtil.COLOR_INFO))
                    .append(TextUtil.target(bounty.targetName()))
                    .append(Component.literal("\n  Bounty: ").withColor(TextUtil.COLOR_INFO))
                    .append(TextUtil.currency(bounty.totalAmount()))
                    .append(Component.literal("\n  Placed by: ").withColor(TextUtil.COLOR_INFO))
                    .append(TextUtil.player(bounty.placedByName()))
                    .append(Component.literal("\n  Expires in: ").withColor(TextUtil.COLOR_INFO))
                    .append(Component.literal(TextUtil.formatDuration(bounty.remainingTime()))
                            .withColor(TextUtil.COLOR_MUTED)),
                    bounty);
        }
    }

    public void announceAutonomousBounty(BountyEntry bounty, MinecraftServer server) {
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            this.sendIntelMessage(viewer, server, base -> base
                    .append(Component.literal("AUTONOMOUS BOUNTY!").withColor(TextUtil.COLOR_ACCENT))
                    .append(Component.literal("\n")).append(TextUtil.separator())
                    .append(Component.literal("\n  The Enforcer has placed a bounty!").withColor(TextUtil.COLOR_WARN))
                    .append(Component.literal("\n  Reason: ").withColor(TextUtil.COLOR_INFO))
                    .append(Component.literal(bounty.autonomousReason()).withColor(TextUtil.COLOR_HEADER))
                    .append(Component.literal("\n  Bounty: ").withColor(TextUtil.COLOR_INFO))
                    .append(TextUtil.currency(bounty.totalAmount()))
                    .append(Component.literal("\n  Expires in: ").withColor(TextUtil.COLOR_INFO))
                    .append(Component.literal(TextUtil.formatDuration(bounty.remainingTime()))
                            .withColor(TextUtil.COLOR_MUTED)),
                    bounty);
        }
    }

    /**
     * Composes the shared body plus the viewer's license-gated intel block
     * without ever blocking: license -> kill stats -> wealth -> send.
     */
    private void sendIntelMessage(ServerPlayer viewer, MinecraftServer server,
                                  java.util.function.Function<MutableComponent, MutableComponent> body,
                                  BountyEntry bounty) {
        UUID viewerUuid = viewer.getUUID();
        UUID targetUuid = bounty.targetUuid();
        this.licenseManager.getLicense(viewerUuid).thenCompose(licenseOpt -> {
            MutableComponent msg = body.apply(TextUtil.bountyIcon()).append(Component.literal("\n"));
            LicenseTier tier = licenseOpt.filter(LicenseData::isValid)
                    .map(LicenseData::tier).orElse(null);
            if (tier == null) {
                msg = msg.append(Component.literal("  [Buy a Hunter License for target intel — /hunter tiers]")
                        .withColor(0x666666));
                return CompletableFuture.completedFuture(msg);
            }
            CompletableFuture<MutableComponent> kd = intelKd(tier, targetUuid, msg);
            return kd.thenCompose(appended -> intelWealth(tier, server, targetUuid, appended))
                    .thenCompose(appended -> intelPlaytime(tier, server, targetUuid, appended));
        }).thenAccept(msg -> server.execute(() -> {
            ServerPlayer still = server.getPlayerList().getPlayer(viewerUuid);
            if (still != null) {
                still.sendSystemMessage(msg.append(Component.literal("\n")).append(TextUtil.separator()));
            }
        })).exceptionally(error -> {
            org.slf4j.LoggerFactory.getLogger("Solidus-Enforcer")
                    .error("Failed to compose bounty announcement for viewer {}", viewerUuid, error);
            return null;
        });
    }

    private CompletableFuture<MutableComponent> intelKd(LicenseTier tier, UUID targetUuid, MutableComponent msg) {
        if (!tier.canSeeKd()) {
            return CompletableFuture.completedFuture(msg);
        }
        return this.storage.getKillStats(targetUuid).thenApply(statsOpt -> statsOpt
                .map(stats -> msg.append(Component.literal("\n  K/D: ").withColor(TextUtil.COLOR_SILVER))
                        .append(Component.literal(String.format("%.1f  (%d kills / %d deaths)",
                                stats.kdRatio(), stats.kills(), stats.deaths()))
                                .withColor(TextUtil.COLOR_INFO)))
                .orElse(msg));
    }

    private CompletableFuture<MutableComponent> intelWealth(LicenseTier tier, MinecraftServer server,
                                                            UUID targetUuid, MutableComponent msg) {
        if (!tier.canSeeWealth()) {
            return CompletableFuture.completedFuture(msg);
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            return CompletableFuture.completedFuture(msg.append(
                    Component.literal("\n  Wealth: offline").withColor(TextUtil.COLOR_MUTED)));
        }
        return SolidusBridge.getBalance(target).thenApply(balance -> msg
                .append(Component.literal("\n  Wealth: ").withColor(TextUtil.COLOR_SILVER))
                .append(TextUtil.currency(balance)));
    }

    private CompletableFuture<MutableComponent> intelPlaytime(LicenseTier tier, MinecraftServer server,
                                                              UUID targetUuid, MutableComponent msg) {
        if (!tier.canSeePlaytime()) {
            return CompletableFuture.completedFuture(msg);
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            return CompletableFuture.completedFuture(msg);
        }
        try {
            int playtimeTicks = target.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            long hours = playtimeTicks / (60 * 60 * 20);
            return CompletableFuture.completedFuture(msg
                    .append(Component.literal("\n  Playtime: ").withColor(TextUtil.COLOR_SILVER))
                    .append(Component.literal(hours + "h").withColor(TextUtil.COLOR_INFO)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(msg);
        }
    }

    // ------------------------------------------------------------------
    // Claim / denial (same for everyone; no per-viewer intel)
    // ------------------------------------------------------------------

    public static void announceClaim(List<BountyEntry> claimed, ServerPlayer victim, ServerPlayer killer,
                                     double totalBounty, double payable,
                                     com.solidus.enforcer.security.AntiExploitEngine.ExploitCheckResult exploit,
                                     MinecraftServer server, Map<UUID, Double> payouts) {
        MutableComponent msg = TextUtil.bountyIcon()
                .append(Component.literal("BOUNTY CLAIMED!").withColor(TextUtil.COLOR_ACCENT))
                .append(Component.literal("\n")).append(TextUtil.separator())
                .append(Component.literal("\n  Target: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.target(victim.getName().getString()))
                .append(Component.literal("\n  Eliminated by: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.player(killer.getName().getString()))
                .append(Component.literal("\n  Bounties claimed: ").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal(String.valueOf(claimed.size())).withColor(TextUtil.COLOR_BRAND))
                .append(Component.literal("\n  Bounty value: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(totalBounty))
                .append(Component.literal("\n  Paid out: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(payable));

        UUID killerUuid = killer.getUUID();
        boolean killerShareShown = false;
        int shown = 0;
        for (Map.Entry<UUID, Double> entry : payouts.entrySet()) {
            if (shown >= 3) {
                break;
            }
            if (entry.getKey().equals(killerUuid)) {
                killerShareShown = true;
            }
            msg = msg.append(Component.literal("\n  \u2022 Share: ").withColor(TextUtil.COLOR_MUTED))
                    .append(TextUtil.currency(entry.getValue()));
            shown++;
        }
        if (!killerShareShown && payouts.containsKey(killerUuid)) {
            msg = msg.append(Component.literal("\n  \u2022 Finishing bonus: ").withColor(TextUtil.COLOR_MUTED))
                    .append(TextUtil.currency(payouts.get(killerUuid)));
        }
        if (!exploit.legitimate() && !exploit.message().isEmpty()) {
            msg = msg.append(Component.literal("\n  Note: ").withColor(TextUtil.COLOR_WARN))
                    .append(Component.literal(exploit.message()).withColor(TextUtil.COLOR_WARN));
        }
        msg = msg.append(Component.literal("\n")).append(TextUtil.separator());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg);
        }
    }

    public static void announceDenial(ServerPlayer victim, ServerPlayer killer, String reason,
                                      double confiscated, MinecraftServer server) {
        MutableComponent msg = TextUtil.bountyIcon()
                .append(Component.literal("BOUNTY DENIED").withColor(TextUtil.COLOR_WARN))
                .append(Component.literal("\n")).append(TextUtil.separator())
                .append(Component.literal("\n  Kill: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.player(killer.getName().getString()))
                .append(Component.literal(" \u2192 ").withColor(TextUtil.COLOR_MUTED))
                .append(TextUtil.target(victim.getName().getString()))
                .append(Component.literal("\n  Reason: ").withColor(TextUtil.COLOR_INFO))
                .append(Component.literal(reason).withColor(TextUtil.COLOR_WARN))
                .append(Component.literal("\n  Confiscated to treasury: ").withColor(TextUtil.COLOR_INFO))
                .append(TextUtil.currency(confiscated))
                .append(Component.literal("\n")).append(TextUtil.separator());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg);
        }
    }
}
