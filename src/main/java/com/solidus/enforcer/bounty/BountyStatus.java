/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.enforcer.bounty;

public enum BountyStatus {
    ACTIVE(0),
    CLAIMED(1),
    CANCELLED(2),
    EXPIRED(3),
    AUTONOMOUS(4);

    private final int code;

    private BountyStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public static BountyStatus fromCode(int code) {
        for (BountyStatus status : BountyStatus.values()) {
            if (status.code != code) continue;
            return status;
        }
        return ACTIVE;
    }

    public boolean isClaimable() {
        return this == ACTIVE || this == AUTONOMOUS;
    }
}
