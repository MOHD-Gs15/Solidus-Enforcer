package com.solidus.enforcer.license;

/**
 * Hunter license tiers and their perk ladder.
 *
 * BRONZE — bounty board access (place/list bounties)
 * SILVER — + K/D & playtime intel, tracking compass (50-block accuracy)
 * GOLD   — + wealth intel, high-precision compass (5-block accuracy)
 */
public enum LicenseTier {
    BRONZE("\u00A76Bronze", 500.0),
    SILVER("\u00A7fSilver", 700.0),
    GOLD("\u00A7bGold", 1200.0);

    private final String displayName;
    private final double defaultWeeklyCost;

    LicenseTier(String displayName, double defaultWeeklyCost) {
        this.displayName = displayName;
        this.defaultWeeklyCost = defaultWeeklyCost;
    }

    public String displayName() {
        return this.displayName;
    }

    public double defaultWeeklyCost() {
        return this.defaultWeeklyCost;
    }

    /**
     * Strict parse used by {@code /hunter buy} — an unknown tier is an error,
     * never a silent fallback to BRONZE (a typo used to charge the player for
     * the cheapest tier).
     */
    public static LicenseTier parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LicenseTier.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean canTrack() {
        return this != BRONZE;
    }

    public boolean canSeeKd() {
        return this != BRONZE;
    }

    public boolean canSeeWealth() {
        return this == GOLD;
    }

    public boolean canSeePlaytime() {
        return this != BRONZE;
    }

    public int accuracyBlocks(int silverAccuracy, int goldAccuracy) {
        return switch (this) {
            case GOLD -> goldAccuracy;
            case SILVER -> silverAccuracy;
            case BRONZE -> 0;
        };
    }

    public String perksLine() {
        return switch (this) {
            case BRONZE -> "Bounty board access";
            case SILVER -> "+ K/D & playtime intel, tracking compass (50 blocks)";
            case GOLD -> "+ wealth intel, precision compass (5 blocks)";
        };
    }
}
