/*
 * Decompiled with CFR 0.152.
 */
package com.solidus.enforcer.combat;

import com.solidus.enforcer.combat.DamageRecord;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DamageTracker {
    private final EnforcerStorage storage;
    private final ConfigManager config;
    private final Map<UUID, List<DamageRecord>> damageCache = new ConcurrentHashMap<UUID, List<DamageRecord>>();

    public DamageTracker(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    public void recordDamage(UUID targetUuid, UUID attackerUuid, double damage) {
        DamageRecord record = DamageRecord.create(targetUuid, attackerUuid, damage);
        this.damageCache.computeIfAbsent(targetUuid, k -> new CopyOnWriteArrayList<DamageRecord>()).add(record);
        this.storage.recordDamage(targetUuid, attackerUuid, damage);
    }

    public CompletableFuture<Map<UUID, Double>> getDamageContributions(UUID targetUuid) {
        long windowMs = (long)this.config.getTrackingWindowSeconds() * 1000L;
        List<DamageRecord> cached = this.damageCache.getOrDefault(targetUuid, Collections.emptyList());
        LinkedHashMap<UUID, Double> contributions = new LinkedHashMap<UUID, Double>();
        for (DamageRecord record : cached) {
            if (!record.isWithinWindow(windowMs)) continue;
            contributions.merge(record.attackerUuid(), record.damage(), Double::sum);
        }
        if (!contributions.isEmpty()) {
            return this.storage.getDamageContributions(targetUuid, windowMs).thenApply(dbContributions -> {
                LinkedHashMap<UUID, Double> merged = new LinkedHashMap<UUID, Double>((Map<UUID, Double>)dbContributions);
                for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
                return this.sortByDamage(merged);
            });
        }
        return this.storage.getDamageContributions(targetUuid, windowMs).thenApply(this::sortByDamage);
    }

    public CompletableFuture<Optional<UUID>> getLastHitter(UUID targetUuid) {
        return this.getDamageContributions(targetUuid).thenApply(contributions -> {
            DamageRecord last;
            List<DamageRecord> cached = this.damageCache.getOrDefault(targetUuid, Collections.emptyList());
            if (!cached.isEmpty() && (last = cached.getLast()).isWithinWindow((long)this.config.getTrackingWindowSeconds() * 1000L)) {
                return Optional.of(last.attackerUuid());
            }
            return contributions.keySet().stream().findFirst();
        });
    }

    public void cleanOldRecords() {
        long maxAgeMs = (long)(this.config.getTrackingWindowSeconds() + 60) * 1000L;
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        for (Map.Entry<UUID, List<DamageRecord>> entry : this.damageCache.entrySet()) {
            entry.getValue().removeIf(record -> record.timestamp() < cutoff);
            if (!entry.getValue().isEmpty()) continue;
            this.damageCache.remove(entry.getKey());
        }
        this.storage.cleanOldDamageRecords(maxAgeMs * 2L);
    }

    public void clearRecords(UUID targetUuid) {
        this.damageCache.remove(targetUuid);
    }

    private Map<UUID, Double> sortByDamage(Map<UUID, Double> contributions) {
        ArrayList<Map.Entry<UUID, Double>> list = new ArrayList<Map.Entry<UUID, Double>>(contributions.entrySet());
        list.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        LinkedHashMap<UUID, Double> sorted = new LinkedHashMap<UUID, Double>();
        for (Map.Entry<UUID, Double> entry : list) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
