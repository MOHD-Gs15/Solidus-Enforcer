/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.stats.Stats
 */
package com.solidus.enforcer.bounty;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.TextUtil;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

public final class BountyAnnouncer {
    private BountyAnnouncer() {
    }

    public static void announceNewBounty(BountyEntry bounty, MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MutableComponent msg = BountyAnnouncer.buildNewBountyMessage(bounty, player);
            player.sendSystemMessage((Component)msg);
        }
    }

    public static void announceAutonomousBounty(BountyEntry bounty, MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MutableComponent msg = TextUtil.skullIcon().append((Component)Component.literal((String)"AUTONOMOUS BOUNTY!").withColor(0xFF3333)).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator()).append((Component)Component.literal((String)"\n  The Enforcer has placed a bounty!").withColor(0xFFAA00)).append((Component)Component.literal((String)"\n  Reason: ").withColor(0xAAAAAA)).append((Component)Component.literal((String)bounty.autonomousReason()).withColor(16766720)).append((Component)Component.literal((String)"\n"));
            msg = BountyAnnouncer.addLicenseDependentInfo(msg, bounty, player);
            msg = msg.append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator());
            player.sendSystemMessage((Component)msg);
        }
    }

    private static MutableComponent buildNewBountyMessage(BountyEntry bounty, ServerPlayer viewer) {
        MutableComponent msg = TextUtil.bountyIcon().append((Component)Component.literal((String)"NEW BOUNTY!").withColor(0xFF3333)).append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator()).append((Component)Component.literal((String)"\n  Target: ").withColor(0xAAAAAA)).append((Component)TextUtil.target(bounty.targetName())).append((Component)Component.literal((String)"\n  Bounty: ").withColor(0xAAAAAA)).append((Component)TextUtil.currency(bounty.totalAmount())).append((Component)Component.literal((String)"\n  Placed by: ").withColor(0xAAAAAA)).append((Component)TextUtil.player(bounty.placedByName()));
        msg = BountyAnnouncer.addLicenseDependentInfo(msg, bounty, viewer);
        return msg.append((Component)Component.literal((String)"\n")).append((Component)TextUtil.separator());
    }

    private static MutableComponent addLicenseDependentInfo(MutableComponent msg, BountyEntry bounty, ServerPlayer viewer) {
        ServerPlayer targetPlayer;
        EnforcerStorage storage;
        Optional<EnforcerStorage.KillStats> killStats;
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        if (mod == null) {
            return msg;
        }
        HunterLicenseManager licenseManager = mod.getLicenseManager();
        Optional<LicenseData> licenseOpt = licenseManager.getLicense(viewer.getUUID()).join();
        LicenseTier tier = licenseOpt.filter(LicenseData::isValid).map(LicenseData::tier).orElse(null);
        if (tier == null) {
            return msg.append((Component)Component.literal((String)"\n  [Buy a Hunter License to see more details]").withColor(0x777777));
        }
        if (tier.canSeeKD() && (killStats = (storage = mod.getStorage()).getKillStats(bounty.targetUuid()).join()).isPresent()) {
            EnforcerStorage.KillStats stats = killStats.get();
            msg = msg.append((Component)Component.literal((String)"\n  K/D Ratio: ").withColor(0xC0C0C0)).append((Component)Component.literal((String)String.format("%.1f (%d kills / %d deaths)", stats.kdRatio(), stats.kills(), stats.deaths())).withColor(0xAAAAAA));
        }
        if (tier.canSeeWealth() && (targetPlayer = viewer.level().getServer().getPlayerList().getPlayer(bounty.targetUuid())) != null) {
            SolidusBridge.getBalance(targetPlayer).thenAccept(balance -> viewer.level().getServer().execute(() -> viewer.sendSystemMessage((Component)Component.literal((String)"  Wealth: ").withColor(16766720).append((Component)TextUtil.currencyDetailed(balance)))));
        }
        if (tier.canSeeJoinDate() && (targetPlayer = viewer.level().getServer().getPlayerList().getPlayer(bounty.targetUuid())) != null) {
            try {
                long firstPlayed = targetPlayer.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
                msg = msg.append((Component)Component.literal((String)"\n  Playtime: ").withColor(16766720)).append((Component)Component.literal((String)BountyAnnouncer.formatPlaytime(firstPlayed)).withColor(0xAAAAAA));
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        if (tier.hasCompassTracking()) {
            msg = msg.append((Component)Component.literal((String)"\n  [Click on target name to get tracking compass]").withColor(0x55FFFF));
        }
        return msg;
    }

    private static String formatPlaytime(long ticks) {
        long minutes = ticks / 1200L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0L) {
            return days + "d " + hours % 24L + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes % 60L + "m";
        }
        return minutes + "m";
    }
}
