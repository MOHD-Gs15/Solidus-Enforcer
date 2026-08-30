package com.solidus.enforcer.api;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stable read-mostly API for other mods (zero compile dependency via the
 * same reflection pattern Core exposes). Method signatures here are contract:
 * once released, they do not change — new capability gets new methods.
 */
public final class EnforcerAPI {
    private EnforcerAPI() {
    }

    public static boolean isAvailable() {
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        return mod != null && mod.isFullyActive() && mod.getBountyManager() != null;
    }

    public static CompletableFuture<List<BountyEntry>> getActiveBounties() {
        BountyManager manager = manager();
        return manager == null
                ? CompletableFuture.completedFuture(List.of())
                : manager.getActiveBounties();
    }

    public static CompletableFuture<List<BountyEntry>> getBountiesForTarget(UUID targetUuid) {
        BountyManager manager = manager();
        return manager == null
                ? CompletableFuture.completedFuture(List.of())
                : manager.getBountiesForTarget(targetUuid);
    }

    public static CompletableFuture<Double> getTotalBountyForTarget(UUID targetUuid) {
        BountyManager manager = manager();
        return manager == null
                ? CompletableFuture.completedFuture(0.0)
                : manager.getTotalBountyForTarget(targetUuid);
    }

    public static double getTreasuryBalance() {
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        return mod != null && mod.getTreasuryManager() != null
                ? mod.getTreasuryManager().getBalance()
                : 0.0;
    }

    private static BountyManager manager() {
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        return mod != null && mod.isFullyActive() ? mod.getBountyManager() : null;
    }
}
