package com.solidus.enforcer.license;

import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.util.CompassUtil;
import com.solidus.enforcer.util.ConfigManager;
import com.solidus.enforcer.util.TextUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Live tracking for GOLD hunters: re-points their Solidus compass at the
 * target's current position every configured interval while the bounty is
 * active. SILVER holders keep their static fuzzy fix (issued once).
 *
 * Runs on the tick thread with pure inventory reads/writes — no I/O — so it
 * respects the ecosystem's never-block rule.
 */
public final class TrackerService {
    private final ConfigManager config;
    private final HunterLicenseManager licenseManager;
    private final BountyManager bountyManager;
    /** hunter uuid -> tracked target uuid */
    private final Map<UUID, UUID> tracked = new ConcurrentHashMap<>();

    public TrackerService(ConfigManager config, HunterLicenseManager licenseManager, BountyManager bountyManager) {
        this.config = config;
        this.licenseManager = licenseManager;
        this.bountyManager = bountyManager;
    }

    public boolean isTracking(UUID hunterUuid) {
        return this.tracked.containsKey(hunterUuid);
    }

    public void startTracking(UUID hunterUuid, UUID targetUuid) {
        this.tracked.put(hunterUuid, targetUuid);
    }

    public void stopTracking(UUID hunterUuid) {
        this.tracked.remove(hunterUuid);
    }

    /**
     * Periodic refresh; called from the mod scheduler every configured interval.
     *
     * Each refresh REVALIDATES the contract the compass was issued under: the
     * hunter must still hold a valid GOLD license and the target must still
     * carry an active bounty ("while both players remain online and the bounty
     * stands"). A lapsed license or a resolved bounty stops live tracking.
     */
    public void refreshTick(MinecraftServer server) {
        if (this.tracked.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : this.tracked.entrySet()) {
            UUID hunterUuid = entry.getKey();
            UUID targetUuid = entry.getValue();
            ServerPlayer hunter = server.getPlayerList().getPlayer(hunterUuid);
            if (hunter == null) {
                continue; // offline: keep registration for relog
            }
            ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
            if (target == null) {
                continue; // target offline: keep last known position on the compass
            }
            // Async revalidation, then the compass hop happens on the server thread.
            this.licenseManager.getLicense(hunterUuid).thenAccept(licenseOpt -> {
                boolean goldValid = licenseOpt.isPresent() && licenseOpt.get().isValid()
                        && licenseOpt.get().tier() == com.solidus.enforcer.license.LicenseTier.GOLD;
                if (!goldValid) {
                    this.stopTrackingWithNotice(server, hunterUuid,
                            "Tracking ended — your GOLD license is no longer active.");
                    return;
                }
                if (this.bountyManager == null) {
                    return;
                }
                this.bountyManager.getTotalBountyForTarget(targetUuid).thenAccept(totalBounty ->
                        server.execute(() -> {
                            if (!targetUuid.equals(this.tracked.get(hunterUuid))) {
                                return; // superseded or already stopped
                            }
                            if (totalBounty == null || totalBounty <= 0.0) {
                                this.tracked.remove(hunterUuid);
                                ServerPlayer h = server.getPlayerList().getPlayer(hunterUuid);
                                if (h != null) {
                                    h.sendSystemMessage(TextUtil.branded(
                                            "Tracking ended — the bounty on your target no longer stands.",
                                            TextUtil.COLOR_MUTED));
                                }
                                return;
                            }
                            ServerPlayer h = server.getPlayerList().getPlayer(hunterUuid);
                            ServerPlayer t = server.getPlayerList().getPlayer(targetUuid);
                            if (h == null || t == null) {
                                return;
                            }
                            ItemStack compass = findTrackingCompass(h, t);
                            if (compass == null) {
                                this.tracked.remove(hunterUuid);
                                h.sendSystemMessage(TextUtil.branded(
                                        "Tracking ended — your tracking compass is gone.", TextUtil.COLOR_MUTED));
                                return;
                            }
                            ServerLevel level = (ServerLevel) t.level();
                            CompassUtil.applyTracker(compass, level,
                                    t.getBlockX(), t.getBlockY(), t.getBlockZ());
                        }));
            });
        }
    }

    private void stopTrackingWithNotice(MinecraftServer server, UUID hunterUuid, String message) {
        this.tracked.remove(hunterUuid);
        server.execute(() -> {
            ServerPlayer hunter = server.getPlayerList().getPlayer(hunterUuid);
            if (hunter != null) {
                hunter.sendSystemMessage(TextUtil.branded(message, TextUtil.COLOR_MUTED));
            }
        });
    }

    private ItemStack findTrackingCompass(ServerPlayer hunter, ServerPlayer target) {
        String targetName = target.getName().getString();
        for (int i = 0; i < hunter.getInventory().getContainerSize(); i++) {
            ItemStack stack = hunter.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.LORE)) {
                continue;
            }
            String plain = stack.getHoverName().getString();
            if (plain.contains("Tracking: ") && plain.contains(targetName)
                    && hasMarker(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean hasMarker(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            if (line.getString().contains(CompassUtil.TRACK_MARKER)) {
                return true;
            }
        }
        return false;
    }
}
