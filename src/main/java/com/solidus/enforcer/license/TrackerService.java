package com.solidus.enforcer.license;

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
    /** hunter uuid -> tracked target uuid */
    private final Map<UUID, UUID> tracked = new ConcurrentHashMap<>();

    public TrackerService(ConfigManager config, HunterLicenseManager licenseManager) {
        this.config = config;
        this.licenseManager = licenseManager;
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

    /** Periodic refresh; called from the mod scheduler every configured interval. */
    public void refreshTick(MinecraftServer server) {
        if (this.tracked.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : this.tracked.entrySet()) {
            ServerPlayer hunter = server.getPlayerList().getPlayer(entry.getKey());
            if (hunter == null) {
                continue; // offline: keep registration for relog
            }
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getValue());
            if (target == null) {
                continue; // target offline: keep last known position on the compass
            }
            ItemStack compass = findTrackingCompass(hunter, target);
            if (compass == null) {
                this.tracked.remove(entry.getKey());
                hunter.sendSystemMessage(TextUtil.branded(
                        "Tracking ended — your tracking compass is gone.", TextUtil.COLOR_MUTED));
                continue;
            }
            ServerLevel level = (ServerLevel) target.level();
            CompassUtil.applyTracker(compass, level,
                    target.getBlockX(), target.getBlockY(), target.getBlockZ());
        }
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
