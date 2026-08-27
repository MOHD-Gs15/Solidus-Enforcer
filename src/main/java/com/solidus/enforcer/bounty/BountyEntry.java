/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.enforcer.bounty;

import com.solidus.enforcer.bounty.BountyStatus;
import java.util.UUID;

public record BountyEntry(int id, UUID targetUuid, String targetName, double totalAmount, double originalAmount, double bloodTaxDeducted, double contractFeesDeducted, UUID placedByUuid, String placedByName, long placedTimestamp, long expireTimestamp, BountyStatus status, boolean autonomous, String autonomousReason) {
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expireTimestamp;
    }

    public boolean isClaimable() {
        return this.status.isClaimable() && !this.isExpired();
    }

    public long remainingTime() {
        return Math.max(0L, this.expireTimestamp - System.currentTimeMillis());
    }

    public static BountyEntry create(UUID targetUuid, String targetName, double amount, UUID placedByUuid, String placedByName, long durationDays) {
        long now = System.currentTimeMillis();
        long expire = now + durationDays * 24L * 60L * 60L * 1000L;
        return new BountyEntry(0, targetUuid, targetName, amount, amount, 0.0, 0.0, placedByUuid, placedByName, now, expire, BountyStatus.ACTIVE, false, null);
    }

    public static BountyEntry createAutonomous(UUID targetUuid, String targetName, double amount, String reason, long durationDays) {
        long now = System.currentTimeMillis();
        long expire = now + durationDays * 24L * 60L * 60L * 1000L;
        return new BountyEntry(0, targetUuid, targetName, amount, amount, 0.0, 0.0, null, "SERVER", now, expire, BountyStatus.AUTONOMOUS, true, reason);
    }

    public BountyEntry withDeductedAmount(double newAmount, double contractFee) {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, newAmount, this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted + contractFee, this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp, this.status, this.autonomous, this.autonomousReason);
    }

    public BountyEntry asClaimed() {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, this.totalAmount, this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted, this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp, BountyStatus.CLAIMED, this.autonomous, this.autonomousReason);
    }

    public BountyEntry asCancelled() {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, this.totalAmount, this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted, this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp, BountyStatus.CANCELLED, this.autonomous, this.autonomousReason);
    }

    public BountyEntry asExpired() {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, this.totalAmount, this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted, this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp, BountyStatus.EXPIRED, this.autonomous, this.autonomousReason);
    }
}
