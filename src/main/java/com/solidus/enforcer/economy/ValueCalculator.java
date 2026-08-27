/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Inventory
 *  net.minecraft.world.item.ItemStack
 */
package com.solidus.enforcer.economy;

import com.solidus.enforcer.integration.SolidusBridge;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class ValueCalculator {
    private ValueCalculator() {
    }

    public static double calculateInventoryValue(ServerPlayer player) {
        double totalValue = 0.0;
        Inventory inv = player.getInventory();
        for (ItemStack stack : inv.getNonEquipmentItems()) {
            totalValue += ValueCalculator.getItemValue(stack);
        }
        for (int i = 36; i <= 39; ++i) {
            totalValue += ValueCalculator.getItemValue(inv.getItem(i));
        }
        ItemStack offhand = inv.getItem(40);
        return totalValue += ValueCalculator.getItemValue(offhand);
    }

    private static double getItemValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey == null) {
            return 0.0;
        }
        String material = itemKey.getPath().toUpperCase(java.util.Locale.ROOT);
        Double sellPrice = ValueCalculator.lookupSellPrice(material);
        if (sellPrice != null && sellPrice > 0.0) {
            return sellPrice * (double)stack.getCount();
        }
        return 0.0;
    }

    private static Double lookupSellPrice(String material) {
        try {
            Map<String, List<SolidusBridge.ShopItemData>> sections = SolidusBridge.getShopSections();
            if (sections.isEmpty()) {
                return null;
            }
            for (Map.Entry<String, List<SolidusBridge.ShopItemData>> entry : sections.entrySet()) {
                for (SolidusBridge.ShopItemData item : entry.getValue()) {
                    if (!material.equals(item.material())) continue;
                    return item.sellPrice();
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    public static ValueCheckResult checkValueDropRequirement(ServerPlayer player, double bountyAmount, double minimumGearRatio) {
        double requiredValue;
        double inventoryValue = ValueCalculator.calculateInventoryValue(player);
        boolean passed = inventoryValue >= (requiredValue = bountyAmount * minimumGearRatio);
        return new ValueCheckResult(passed, inventoryValue, requiredValue, bountyAmount, minimumGearRatio);
    }

    public record ValueCheckResult(boolean passed, double inventoryValue, double requiredValue, double bountyAmount, double requiredRatio) {
        public double getPayoutRatio(double nakedPenaltyRatio) {
            if (this.passed) {
                return 1.0;
            }
            double ratio = this.inventoryValue / this.requiredValue;
            if (ratio <= 0.01) {
                return nakedPenaltyRatio;
            }
            return nakedPenaltyRatio + ratio * (1.0 - nakedPenaltyRatio);
        }
    }
}
