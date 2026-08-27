package com.solidus.enforcer.license;

public enum LicenseTier {
    BRONZE,
    SILVER,
    GOLD;

    public static LicenseTier fromString(String value) {
        if (value == null) {
            return BRONZE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BRONZE;
        }
    }

    public boolean canTrack() {
        return hasCompassTracking();
    }

    public boolean canSeeKD() {
        return this != BRONZE;
    }

    public boolean canSeeWealth() {
        return this == GOLD;
    }

    public boolean canSeeJoinDate() {
        return this != BRONZE;
    }

    public boolean hasCompassTracking() {
        return this != BRONZE;
    }

    public int accuracyBlocks(int silverAccuracy, int goldAccuracy) {
        return switch (this) {
            case GOLD -> goldAccuracy;
            case SILVER -> silverAccuracy;
            case BRONZE -> 0;
        };
    }
}
