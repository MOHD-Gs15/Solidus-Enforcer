package com.solidus.enforcer.security;

import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real collusion analysis for bounty claims (the previous implementation was a
 * stub that could never fire). Three independent signals, each individually
 * sufficient to flag:
 *
 * <ol>
 *   <li><b>Pair farming</b> — the killer killed this same victim too many
 *       times inside the configured window (kill_events table).</li>
 *   <li><b>Mutual swaps</b> — A killed B and B killed A alternately — the
 *       signature of two accounts trading kills.</li>
 *   <li><b>Money loop</b> — the victim paid the killer (or vice versa) too
 *       many times inside the transaction lookback, i.e. bounties are being
 *       bankrolled through Core payments.</li>
 * </ol>
 *
 * Signals are combined by the pure {@link Decision} function so the policy is
 * unit-testable without a database.
 */
public final class CollusionDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private final EnforcerStorage storage;
    private final ConfigManager config;

    public CollusionDetector(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    public CompletableFuture<CollusionResult> checkForCollusion(UUID killerUuid, String killerName,
                                                                UUID victimUuid, String victimName) {
        if (!this.config.isCollusionDetectionEnabled() || killerUuid == null || victimUuid == null) {
            return CompletableFuture.completedFuture(CollusionResult.clean());
        }
        long windowMs = this.config.getCollusionKillWindowMinutes() * 60_000L;
        CompletableFuture<Integer> pairKills = this.storage.countPairKills(killerUuid, victimUuid, windowMs);
        CompletableFuture<Integer> mutualSwaps = this.storage.countMutualSwaps(killerUuid, victimUuid, windowMs);
        CompletableFuture<Integer> mutualTransfers = this.fetchMutualTransfers(killerUuid, victimUuid);

        return pairKills.thenCombine(mutualSwaps, (kills, swaps) -> new Decision(kills, swaps, 0))
                .thenCombine(mutualTransfers, (d, transfers) -> new Decision(d.pairKills, d.mutualSwaps, transfers))
                .thenApply(decision -> {
                    Decision.Policy policy = decision.evaluate(
                            this.config.getMaxSamePairKills(),
                            this.config.getMaxMutualSwaps(),
                            this.config.getMaxMutualTransactions());
                    if (!policy.flagged()) {
                        return CollusionResult.clean();
                    }
                    this.storage.flagCollusion(killerUuid, killerName, victimUuid, victimName, policy.reason());
                    LOGGER.warn("Collusion flagged: {} -> {} — {}", killerName, victimName, policy.reason());
                    return new CollusionResult(true, policy.reason());
                });
    }

    /**
     * Victim-to-killer and killer-to-victim transfers inside the lookback window.
     * Each matching transaction ROW counts as one transfer — the union-of-sets
     * formulation used before could only ever yield 0, 1 or 2 (it counted
     * directions, not transactions) and the documented money-loop signal never
     * fired. Bounded by Core's per-player transaction query limit (100 rows).
     */
    private CompletableFuture<Integer> fetchMutualTransfers(UUID killerUuid, UUID victimUuid) {
        int lookbackDays = this.config.getTransactionLookbackDays();
        long cutoff = System.currentTimeMillis() - lookbackDays * 86_400_000L;
        CompletableFuture<Integer> killerSent = SolidusBridge.getTransactions(killerUuid, 100)
                .thenApply(rows -> countMatchingTransactions(rows, victimUuid, cutoff));
        CompletableFuture<Integer> victimSent = SolidusBridge.getTransactions(victimUuid, 100)
                .thenApply(rows -> countMatchingTransactions(rows, killerUuid, cutoff));
        return killerSent.thenCombine(victimSent, Integer::sum);
    }

    /** Rows whose target is the counterparty and whose timestamp is inside the window. */
    static int countMatchingTransactions(java.util.List<SolidusBridge.TransactionEntryData> rows,
                                         UUID counterparty, long cutoff) {
        if (rows == null || counterparty == null) {
            return 0;
        }
        int count = 0;
        for (SolidusBridge.TransactionEntryData row : rows) {
            if (row.timestamp() >= cutoff && counterparty.equals(row.targetUuid())) {
                count++;
            }
        }
        return count;
    }

    public record CollusionResult(boolean flagged, String reason) {
        public static CollusionResult clean() {
            return new CollusionResult(false, "");
        }
    }

    /** Pure decision policy — tested without storage. */
    public record Decision(int pairKills, int mutualSwaps, int mutualTransfers) {
        public record Policy(boolean flagged, String reason) {
        }

        public Policy evaluate(int maxPairKills, int maxMutualSwaps, int maxMutualTransfers) {
            if (this.pairKills > maxPairKills) {
                return new Policy(true, "kill farming: " + this.pairKills
                        + " kills on the same target within the watch window");
            }
            if (this.mutualSwaps >= maxMutualSwaps) {
                return new Policy(true, "mutual kill swapping detected (" + this.mutualSwaps + " reversals)");
            }
            if (this.mutualTransfers > maxMutualTransfers) {
                return new Policy(true, this.mutualTransfers
                        + " mutual money transfers between killer and victim");
            }
            return new Policy(false, "");
        }
    }
}
