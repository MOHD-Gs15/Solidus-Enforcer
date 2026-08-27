/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.GlobalPos
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.item.component.ItemLore
 *  net.minecraft.world.item.component.LodestoneTracker
 *  net.minecraft.world.level.ItemLike
 *  org.jetbrains.annotations.Nullable
 */
package com.solidus.enforcer.util;

import com.solidus.enforcer.util.TextUtil;
import java.util.ArrayList;
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

public final class CompassUtil {
    private CompassUtil() {
    }

    public static ItemStack createTrackingCompass(String targetName, @Nullable String targetDim, int targetX, int targetZ, String tier) {
        ItemStack compass = new ItemStack((ItemLike)Items.COMPASS);
        MutableComponent displayName = TextUtil.licenseIcon().append((Component)Component.literal((String)"Tracking: ").withColor(0x55FFFF)).append((Component)TextUtil.target(targetName));
        compass.set(DataComponents.CUSTOM_NAME, displayName);
        ArrayList<Component> lore = new ArrayList<Component>();
        lore.add(TextUtil.compassIcon().append((Component)Component.literal((String)"Points to target's last known location").withColor(0xAAAAAA)));
        if ("GOLD".equalsIgnoreCase(tier)) {
            lore.add(Component.literal((String)"  Real-time tracking (5-block accuracy)").withColor(16766720));
        } else {
            lore.add(Component.literal((String)"  Updates every 60s (50-block accuracy)").withColor(0xC0C0C0));
        }
        if (targetDim != null) {
            lore.add(Component.literal((String)("  Dimension: " + CompassUtil.formatDimension(targetDim))).withColor(0x777777));
        }
        lore.add(TextUtil.thinSeparator());
        lore.add(Component.literal((String)"  Expires when bounty is claimed/cancelled").withColor(0x777777));
        compass.set(DataComponents.LORE, new ItemLore(lore));
        return compass;
    }

    public static ItemStack createLiveTrackingCompass(String targetName, ServerLevel level, int targetX, int targetZ, String tier) {
        ItemStack compass = CompassUtil.createTrackingCompass(targetName, level.dimension().identifier().toString(), targetX, targetZ, tier);
        try {
            GlobalPos targetPos = new GlobalPos(level.dimension(), new BlockPos(targetX, 64, targetZ));
            LodestoneTracker tracker = new LodestoneTracker(Optional.of(targetPos), true);
            compass.set(DataComponents.LODESTONE_TRACKER, tracker);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return compass;
    }

    public static void updateCompassTarget(ItemStack compass, ServerLevel level, int targetX, int targetZ) {
        try {
            GlobalPos targetPos = new GlobalPos(level.dimension(), new BlockPos(targetX, 64, targetZ));
            LodestoneTracker tracker = new LodestoneTracker(Optional.of(targetPos), true);
            compass.set(DataComponents.LODESTONE_TRACKER, tracker);
        }
        catch (Exception exception) {
            // empty catch block
        }
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
