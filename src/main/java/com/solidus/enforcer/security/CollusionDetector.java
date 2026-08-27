package com.solidus.enforcer.security;

import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CollusionDetector {
    private final EnforcerStorage storage;
    private final ConfigManager config;

    public CollusionDetector(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    public CompletableFuture<CollusionResult> checkForCollusion(UUID player1, String name1,
                                                                  UUID player2, String name2) {
        if (!config.isCollusionDetectionEnabled() || player1 == null || player2 == null || !player1.equals(player2)) {
            return CompletableFuture.completedFuture(new CollusionResult(false, ""));
        }
        String reason = "killer and victim UUID are identical";
        return storage.flagCollusion(player1, name1, player2, name2, reason)
            .thenApply(ignored -> new CollusionResult(true, reason));
    }

    public record CollusionResult(boolean flagged, String reason) {
    }
}
