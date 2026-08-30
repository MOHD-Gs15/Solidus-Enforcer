package com.solidus.enforcer.combat;

import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks recent player-vs-player damage for the alliance payout split.
 *
 * The in-memory cache is the fast path for last-hit lookups; the database is
 * the authoritative source for contribution sums (both sides receive every
 * record). Cleanup covers BOTH layers — the old build left DB rows forever.
 */
public final class DamageTracker {
    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final Map<UUID, List<DamageRecord>> damageCache = new ConcurrentHashMap<>();

    public DamageTracker(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    /** Called from the damage mixin on every successful PvP hit. */
    public void recordDamage(UUID targetUuid, UUID attackerUuid, String attackerName, double damage) {
        if (damage <= 0.0) {
            return;
        }
        this.damageCache.computeIfAbsent(targetUuid, k -> new CopyOnWriteArrayList<>())
                .add(DamageRecord.create(targetUuid, attackerUuid, attackerName, damage));
        this.storage.recordDamage(targetUuid, attackerUuid, attackerName, damage);
    }

    /** Authoritative per-attacker damage sums inside the tracking window. */
    public CompletableFuture<Map<UUID, Double>> getDamageContributions(UUID targetUuid) {
        long windowMs = windowMs();
        return this.storage.getDamageContributions(targetUuid, windowMs).thenApply(contributions -> {
            Map<UUID, Double> sorted = new java.util.LinkedHashMap<>();
            contributions.entrySet().stream()
                    .filter(e -> e.getValue() != null && Double.isFinite(e.getValue()) && e.getValue() > 0.0)
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return sorted;
        });
    }

    /** Names of recent attackers, used for payout messages to offline players. */
    public CompletableFuture<Map<UUID, String>> getAttackerNames(UUID targetUuid) {
        return this.storage.getAttackerNames(targetUuid, windowMs());
    }

    public CompletableFuture<Optional<UUID>> getLastHitter(UUID targetUuid) {
        List<DamageRecord> cached = this.damageCache.getOrDefault(targetUuid, List.of());
        if (!cached.isEmpty()) {
            DamageRecord last = cached.getLast();
            if (last.isWithinWindow(windowMs())) {
                return CompletableFuture.completedFuture(Optional.of(last.attackerUuid()));
            }
        }
        return this.getDamageContributions(targetUuid).thenApply(contributions ->
                contributions.isEmpty() ? Optional.<UUID>empty() : Optional.of(contributions.keySet().iterator().next()));
    }

    public void clearRecords(UUID targetUuid) {
        this.damageCache.remove(targetUuid);
        this.storage.clearDamageRecords(targetUuid);
    }

    /** Cache-side cleanup; DB-side runs on the periodic scheduler. */
    public void cleanOldCacheRecords() {
        long cutoff = System.currentTimeMillis() - (windowMs() + 60_000L);
        for (Map.Entry<UUID, List<DamageRecord>> entry : this.damageCache.entrySet()) {
            entry.getValue().removeIf(record -> record.timestamp() < cutoff);
            if (entry.getValue().isEmpty()) {
                this.damageCache.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** DB-side cleanup (bounded growth), returns deleted row count. */
    public CompletableFuture<Integer> cleanOldDatabaseRecords() {
        return this.storage.cleanupOldDamageRecords(windowMs() + 60_000L);
    }

    private long windowMs() {
        return this.config.getTrackingWindowSeconds() * 1000L;
    }
}
