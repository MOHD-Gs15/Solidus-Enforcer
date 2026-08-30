package com.solidus.enforcer.economy;

import com.solidus.enforcer.integration.SolidusBridge;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Estimates the market value of a player's carried gear using the live shop
 * price table. Prices are cached by the bridge (30-minute TTL), so the kill
 * pipeline never performs reflection per item.
 *
 * Contract: requiredValue = bounty * minimumGearRatio; payout scales linearly
 * down to the naked-penalty floor when the victim carries less (see
 * {@link EconomyMath#payoutRatio}).
 */
public final class ValueCalculator {
    private ValueCalculator() {
    }

    /**
     * Synchronous inventory valuation — must be called at death time on the
     * tick thread, BEFORE any async hop, because the victim may respawn and
     * rebuild their inventory while the payout pipeline is still running.
     */
    public static double calculateInventoryValue(ServerPlayer player) {
        Inventory inv = player.getInventory();
        double total = 0.0;
        for (ItemStack stack : inv.getNonEquipmentItems()) {
            total += getItemValue(stack);
        }
        for (int i = 36; i <= 39; i++) {
            total += getItemValue(inv.getItem(i));
        }
        total += getItemValue(inv.getItem(40));
        return EconomyMath.round2(total);
    }

    public record ValueCheckResult(double inventoryValue, double requiredValue, boolean passed) {
        public double payoutRatio(double nakedPenaltyRatio) {
            return EconomyMath.payoutRatio(this.requiredValue, this.inventoryValue,
                    1.0, nakedPenaltyRatio);
        }
    }

    public static ValueCheckResult checkValueDropRequirement(double inventoryValue,
                                                             double totalBounty,
                                                             double minimumGearRatio) {
        double required = EconomyMath.round2(totalBounty * minimumGearRatio);
        return new ValueCheckResult(inventoryValue, required, inventoryValue >= required);
    }

    private static double getItemValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey == null) {
            return 0.0;
        }
        String material = itemKey.getPath().toUpperCase(Locale.ROOT);
        Double sellPrice = lookupSellPrice(material);
        return sellPrice == null ? 0.0 : sellPrice * stack.getCount();
    }

    private static Double lookupSellPrice(String material) {
        Map<String, Double> prices = SolidusBridge.getShopSellPrices();
        return prices.get(material);
    }
}
