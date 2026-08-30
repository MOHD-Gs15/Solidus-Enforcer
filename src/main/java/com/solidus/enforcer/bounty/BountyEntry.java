package com.solidus.enforcer.bounty;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Immutable bounty row. All mutations go through withers so that every
 * transition is explicit and auditable.
 *
 * Money convention: {@code totalAmount} is what a successful hunter receives.
 * The placer's full payment (amount + blood tax) is captured at placement time
 * in {@code originalAmount} / {@code bloodTaxDeducted}; contract fees then
 * decay {@code totalAmount} over time and are tracked in {@code contractFeesDeducted}.
 */
public record BountyEntry(int id, UUID targetUuid, String targetName, double totalAmount,
                          double originalAmount, double bloodTaxDeducted, double contractFeesDeducted,
                          UUID placedByUuid, String placedByName, long placedTimestamp,
                          long expireTimestamp, BountyStatus status, boolean autonomous,
                          String autonomousReason) {

    public BountyEntry {
        if (targetUuid == null) {
            throw new IllegalArgumentException("targetUuid is required");
        }
        targetName = targetName == null || targetName.isBlank() ? "Unknown" : targetName;
        if (!Double.isFinite(totalAmount) || totalAmount < 0.0) {
            totalAmount = 0.0;
        }
        if (!Double.isFinite(originalAmount) || originalAmount < 0.0) {
            originalAmount = totalAmount;
        }
        bloodTaxDeducted = Double.isFinite(bloodTaxDeducted) ? Math.max(0.0, bloodTaxDeducted) : 0.0;
        contractFeesDeducted = Double.isFinite(contractFeesDeducted) ? Math.max(0.0, contractFeesDeducted) : 0.0;
        placedByName = placedByName == null || placedByName.isBlank() ? "Unknown" : placedByName;
    }

    public static long expiryFor(long placedTimestamp, long durationDays) {
        long durationMs = TimeUnit.DAYS.toMillis(Math.max(1L, durationDays));
        return placedTimestamp + durationMs;
    }

    public static BountyEntry create(UUID targetUuid, String targetName, double amount,
                                     UUID placedByUuid, String placedByName, long durationDays) {
        long now = System.currentTimeMillis();
        return new BountyEntry(0, targetUuid, targetName, amount, amount, 0.0, 0.0,
                placedByUuid, placedByName, now, expiryFor(now, durationDays),
                BountyStatus.ACTIVE, false, null);
    }

    public static BountyEntry createAutonomous(UUID targetUuid, String targetName, double amount,
                                               String reason, long durationDays) {
        long now = System.currentTimeMillis();
        return new BountyEntry(0, targetUuid, targetName, amount, amount, 0.0, 0.0,
                null, "SERVER", now, expiryFor(now, durationDays),
                BountyStatus.AUTONOMOUS, true, reason);
    }

    /** Placement-time variant carrying the tax breakdown actually charged. */
    public BountyEntry withTax(double totalAmountAfterTax, double bloodTaxDeducted) {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, totalAmountAfterTax,
                this.originalAmount, bloodTaxDeducted, this.contractFeesDeducted, this.placedByUuid,
                this.placedByName, this.placedTimestamp, this.expireTimestamp, this.status,
                this.autonomous, this.autonomousReason);
    }

    public BountyEntry withDeductedAmount(double newAmount, double contractFee) {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, newAmount,
                this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted + contractFee,
                this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp,
                this.status, this.autonomous, this.autonomousReason);
    }

    public BountyEntry withId(int id) {
        return new BountyEntry(id, this.targetUuid, this.targetName, this.totalAmount,
                this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted,
                this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp,
                this.status, this.autonomous, this.autonomousReason);
    }

    public BountyEntry asStatus(BountyStatus newStatus) {
        return new BountyEntry(this.id, this.targetUuid, this.targetName, this.totalAmount,
                this.originalAmount, this.bloodTaxDeducted, this.contractFeesDeducted,
                this.placedByUuid, this.placedByName, this.placedTimestamp, this.expireTimestamp,
                newStatus, this.autonomous, this.autonomousReason);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > this.expireTimestamp;
    }

    public boolean isClaimable() {
        return this.status.isClaimable() && !this.isExpired();
    }

    public long remainingTime() {
        return Math.max(0L, this.expireTimestamp - System.currentTimeMillis());
    }

    /** Short reason label used for autonomous-bounty de-duplication. */
    public String reasonCategory() {
        if (this.autonomousReason == null) {
            return "";
        }
        int colon = this.autonomousReason.indexOf(':');
        return (colon > 0 ? this.autonomousReason.substring(0, colon) : this.autonomousReason).trim();
    }
}
