package com.solidus.enforcer.combat;

import java.util.UUID;

/** One recorded hit within the alliance tracking window. */
public record DamageRecord(UUID targetUuid, UUID attackerUuid, String attackerName,
                           double damage, long timestamp) {

    public DamageRecord {
        if (targetUuid == null || attackerUuid == null) {
            throw new IllegalArgumentException("target and attacker UUIDs are required");
        }
        attackerName = attackerName == null || attackerName.isBlank() ? "Unknown" : attackerName;
        damage = Double.isFinite(damage) && damage > 0.0 ? damage : 0.0;
    }

    public static DamageRecord create(UUID targetUuid, UUID attackerUuid, String attackerName, double damage) {
        return new DamageRecord(targetUuid, attackerUuid, attackerName, damage, System.currentTimeMillis());
    }

    public boolean isWithinWindow(long windowMs) {
        return System.currentTimeMillis() - this.timestamp <= windowMs;
    }
}
