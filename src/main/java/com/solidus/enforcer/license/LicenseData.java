package com.solidus.enforcer.license;

import java.util.UUID;

/**
 * A purchased hunter license. {@link #isValid()} is the single source of truth
 * for gating — the {@code active} DB flag is only a housekeeping mirror that
 * the periodic sweep maintains.
 */
public record LicenseData(UUID playerUuid, String playerName, LicenseTier tier,
                          long purchaseTimestamp, long expireTimestamp, boolean active) {
    public LicenseData {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid is required");
        }
        playerName = playerName == null || playerName.isBlank() ? "Unknown" : playerName;
        tier = tier == null ? LicenseTier.BRONZE : tier;
    }

    public boolean isValid() {
        return this.active && this.expireTimestamp > System.currentTimeMillis();
    }

    public long remainingMillis() {
        return Math.max(0L, this.expireTimestamp - System.currentTimeMillis());
    }

    public LicenseData renewed(long newExpiry) {
        return new LicenseData(this.playerUuid, this.playerName, this.tier,
                System.currentTimeMillis(), newExpiry, true);
    }
}
