package com.solidus.enforcer.util;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Central text/formatting helpers for all Enforcer player-facing output.
 * Keeps the visual language consistent: every message is prefixed, every
 * amount is rendered as S$ currency, every duration is human-readable.
 */
public final class TextUtil {
    public static final int COLOR_BRAND = 0x55FFFF;
    public static final int COLOR_GOOD = 0x55FF55;
    public static final int COLOR_BAD = 0xFF5555;
    public static final int COLOR_WARN = 0xFFAA00;
    public static final int COLOR_GOLD = 0xFFAA00;
    public static final int COLOR_MUTED = 0x777777;
    public static final int COLOR_INFO = 0xAAAAAA;
    public static final int COLOR_ACCENT = 0xFF5555;
    public static final int COLOR_HEADER = 0xFFAA00;
    public static final int COLOR_SILVER = 0xC0C0C0;

    private TextUtil() {
    }

    /** "[Solidus] " prefix followed by the payload text. */
    public static MutableComponent branded(String text, int color) {
        return prefix().append(Component.literal(text == null ? "" : text).withColor(color));
    }

    public static MutableComponent prefix() {
        return Component.literal("[Solidus] ").withColor(COLOR_BRAND);
    }

    /** Compact currency form: S$1,234.56 */
    public static MutableComponent currency(double amount) {
        return Component.literal(String.format(Locale.ROOT, "S$%,.2f", amount)).withColor(COLOR_GOOD);
    }

    /** Grouped-integer form: 1,234.56 S$ (used inside longer sentences). */
    public static MutableComponent currencyDetailed(double amount) {
        return Component.literal(NumberFormat.getNumberInstance(Locale.ROOT).format(amount) + " S$")
                .withColor(COLOR_GOOD);
    }

    public static MutableComponent target(String name) {
        return Component.literal(name == null ? "Unknown" : name).withColor(COLOR_ACCENT);
    }

    public static MutableComponent player(String name) {
        return Component.literal(name == null ? "Unknown" : name).withColor(COLOR_BRAND);
    }

    public static MutableComponent licenseIcon() {
        return Component.literal("[License] ").withColor(COLOR_SILVER);
    }

    public static MutableComponent compassIcon() {
        return Component.literal("[Compass] ").withColor(COLOR_BRAND);
    }

    public static MutableComponent bountyIcon() {
        return Component.literal("\u2620 ").withColor(COLOR_ACCENT);
    }

    public static MutableComponent skullIcon() {
        return Component.literal("\u2620 ").withColor(COLOR_MUTED);
    }

    /** Human-readable remaining time, e.g. "29d 7h", "5h 12m", "42s". */
    public static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "expired";
        }
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    public static MutableComponent separator() {
        return Component.literal("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                .withColor(COLOR_MUTED);
    }

    public static MutableComponent thinSeparator() {
        return Component.literal("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
                .withColor(COLOR_MUTED);
    }

    /** Page footer that only advertises navigation that actually exists. */
    public static MutableComponent pageFooter(int currentPage, int totalPages) {
        MutableComponent footer = Component.literal("\n").withColor(COLOR_MUTED);
        if (totalPages <= 1) {
            return footer;
        }
        if (currentPage > 1) {
            footer = footer.append(Component.literal("\u25C0 Prev: /bounty list " + (currentPage - 1) + "   ")
                    .withColor(COLOR_MUTED));
        }
        if (currentPage < totalPages) {
            footer = footer.append(Component.literal("Next: /bounty list " + (currentPage + 1) + " \u25B6")
                    .withColor(COLOR_MUTED));
        }
        return footer;
    }
}
