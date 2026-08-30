package com.solidus.enforcer.bounty;

/**
 * Lifecycle of a bounty row.
 *
 * ACTIVE     — placed and payable on kill
 * CLAIMED    — a kill was accepted; payout settled or in flight
 * CANCELLED  — admin cancel (funds confiscated to treasury) or collusion denial
 * EXPIRED    — duration elapsed; placer refunded where possible
 * AUTONOMOUS — placed by the Enforcer itself from treasury funds
 */
public enum BountyStatus {
    ACTIVE(0),
    CLAIMED(1),
    CANCELLED(2),
    EXPIRED(3),
    AUTONOMOUS(4);

    private final int code;

    BountyStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public static BountyStatus fromCode(int code) {
        for (BountyStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return ACTIVE;
    }

    /** Statuses that count as a live, payable bounty. */
    public boolean isClaimable() {
        return this == ACTIVE || this == AUTONOMOUS;
    }
}
