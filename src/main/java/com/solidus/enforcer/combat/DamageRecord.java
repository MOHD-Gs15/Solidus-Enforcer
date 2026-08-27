package com.solidus.enforcer.combat;

import java.util.UUID;

public record DamageRecord(UUID targetUuid, UUID attackerUuid, double damage, long timestamp) {
    public DamageRecord {
        if (targetUuid == null || attackerUuid == null || !Double.isFinite(damage) || damage <= 0.0) {
            throw new IllegalArgumentException("Invalid damage record");
        }
    }

    public static DamageRecord create(UUID targetUuid, UUID attackerUuid, double damage) {
        return new DamageRecord(targetUuid, attackerUuid, damage, System.currentTimeMillis());
    }

    public boolean isWithinWindow(long windowMs) {
        return windowMs >= 0L && timestamp >= System.currentTimeMillis() - windowMs;
    }
}
