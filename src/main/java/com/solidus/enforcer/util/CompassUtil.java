package com.solidus.enforcer.util;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

/**
 * Builds hunter tracking compasses. SILVER holders get a fuzzy static fix on
 * the target's last known position; GOLD holders get near-exact coordinates
 * and a live-refresh component (see TrackerService).
 */
public final class CompassUtil {
    /** Marker embedded in the lore so TrackerService can find its compasses. */
    public static final String TRACK_MARKER = "solidus-track";

    private CompassUtil() {
    }

    public static ItemStack createTrackingCompass(String targetName, @Nullable String targetDim,
                                                  int targetX, int targetY, int targetZ,
                                                  String tier, int accuracyBlocks) {
        ItemStack compass = new ItemStack((ItemLike) Items.COMPASS);
        MutableComponent displayName = TextUtil.licenseIcon()
                .append(Component.literal("Tracking: ").withColor(TextUtil.COLOR_BRAND))
                .append(TextUtil.target(targetName));
        compass.set(DataComponents.CUSTOM_NAME, displayName);

        ArrayList<Component> lore = new ArrayList<>();
        lore.add(TextUtil.compassIcon()
                .append(Component.literal("Points to " + targetName + "'s tracked position")
                        .withColor(TextUtil.COLOR_INFO)));
        lore.add(Component.literal("  [" + TRACK_MARKER + "]").withColor(0x444444));
        lore.add(Component.literal("  Accuracy: \u00B1" + accuracyBlocks + " blocks")
                .withColor(TextUtil.COLOR_SILVER));
        if (targetDim != null) {
            lore.add(Component.literal("  Dimension: " + formatDimension(targetDim))
                    .withColor(TextUtil.COLOR_MUTED));
        }
        lore.add(TextUtil.thinSeparator());
        lore.add(Component.literal("  Tier: " + tier).withColor(TextUtil.COLOR_MUTED));
        compass.set(DataComponents.LORE, new ItemLore(lore));
        return compass;
    }

    public static ItemStack createLiveTrackingCompass(String targetName, ServerLevel level,
                                                      int targetX, int targetY, int targetZ,
                                                      String tier, int accuracyBlocks) {
        ItemStack compass = createTrackingCompass(targetName,
                level.dimension().identifier().toString(), targetX, targetY, targetZ, tier, accuracyBlocks);
        applyTracker(compass, level, targetX, targetY, targetZ);
        return compass;
    }

    public static void applyTracker(ItemStack compass, ServerLevel level, int x, int y, int z) {
        try {
            GlobalPos targetPos = new GlobalPos(level.dimension(), new BlockPos(x, y, z));
            compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(targetPos), true));
        } catch (Exception ignored) {
            // Tracker component quirks must never crash a kill event.
        }
    }

    /** Fuzzes coordinates down to the tier accuracy so SILVER is not exact. */
    public static int fuzz(int coordinate, int accuracyBlocks, java.util.random.RandomGenerator random) {
        if (accuracyBlocks <= 1) {
            return coordinate;
        }
        int spread = accuracyBlocks / 2;
        int offset = spread == 0 ? 0 : random.nextInt(-spread, spread + 1);
        return coordinate + offset;
    }

    public static String dimensionKey(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static String formatDimension(String dimKey) {
        return switch (dimKey) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dimKey.replace("minecraft:", "");
        };
    }
}
