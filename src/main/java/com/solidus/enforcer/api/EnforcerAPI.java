package com.solidus.enforcer.api;

import com.solidus.enforcer.SolidusEnforcerMod;
import com.solidus.enforcer.bounty.BountyManager;
import com.solidus.enforcer.bounty.BountyEntry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EnforcerAPI {
    private EnforcerAPI() {
    }

    public static boolean isAvailable() {
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        return mod != null && mod.getBountyManager() != null;
    }

    public static CompletableFuture<List<BountyEntry>> getBountiesForTarget(UUID targetUuid) {
        SolidusEnforcerMod mod = SolidusEnforcerMod.getInstance();
        BountyManager manager = mod == null ? null : mod.getBountyManager();
        return manager == null
            ? CompletableFuture.completedFuture(List.of())
            : manager.getBountiesForTarget(targetUuid);
    }
}
