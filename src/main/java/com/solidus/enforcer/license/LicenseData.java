package com.solidus.enforcer.license;

import java.util.UUID;

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
        return active && expireTimestamp > System.currentTimeMillis();
    }
}
