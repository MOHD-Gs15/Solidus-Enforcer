package com.solidus.enforcer.util;

import java.text.NumberFormat;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class TextUtil {
    private TextUtil() {
    }

    public static MutableComponent branded(String text, int color) {
        return Component.literal("[Solidus] ").withColor(0x55FFFF)
            .append(Component.literal(text == null ? "" : text).withColor(color));
    }

    public static MutableComponent currency(double amount) {
        return Component.literal(String.format(Locale.ROOT, "S$%.2f", amount)).withColor(0x55FF55);
    }

    public static MutableComponent currencyDetailed(double amount) {
        return Component.literal(NumberFormat.getNumberInstance(Locale.ROOT).format(amount) + " S$").withColor(0x55FF55);
    }

    public static MutableComponent target(String name) {
        return Component.literal(name == null ? "Unknown" : name).withColor(0xFF5555);
    }

    public static MutableComponent player(String name) {
        return Component.literal(name == null ? "Unknown" : name).withColor(0x55FFFF);
    }

    public static MutableComponent prefix() {
        return Component.literal("[Solidus] ").withColor(0x55FFFF);
    }

    public static MutableComponent licenseIcon() {
        return Component.literal("[License] ").withColor(0xC0C0C0);
    }

    public static MutableComponent compassIcon() {
        return Component.literal("[Compass] ").withColor(0x55FFFF);
    }

    public static MutableComponent bountyIcon() {
        return Component.literal("[Bounty] ").withColor(0xFFAA00);
    }

    public static MutableComponent skullIcon() {
        return Component.literal("[Enforcer] ").withColor(0xFF5555);
    }

    public static MutableComponent separator() {
        return Component.literal("====================").withColor(0x777777);
    }

    public static MutableComponent thinSeparator() {
        return Component.literal("--------------------").withColor(0x777777);
    }
}
