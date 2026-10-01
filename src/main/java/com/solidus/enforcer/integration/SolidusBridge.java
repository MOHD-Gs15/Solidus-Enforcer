package com.solidus.enforcer.integration;

import com.solidus.api.BalanceEntry;
import com.solidus.api.SolidusApi;
import com.solidus.api.SolidusApiAccess;
import com.solidus.api.TransactionRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct bridge to Solidus Core through the <b>solidus-api</b> contract
 * (family 2.3.2, audit W-5 closure).
 *
 * <p><b>What changed from 2.3.0:</b> this class used to hold fifteen cached
 * {@link java.lang.reflect.Method} handles and re-resolve
 * {@code SolidusAPI.getInstance()} on every call — including reflective
 * walks over Core <i>internals</i> ({@code ShopManager$ShopItem},
 * {@code TransactionLog$TransactionEntry},
 * {@code SQLiteStorage$BalanceEntry}) that the contract never guaranteed.
 * A Core refactor renaming any of those would have broken Enforcer's
 * economy silently at runtime (fail-closed to zero payouts).</p>
 *
 * <p>Now every call is a plain, compile-checked invocation against the
 * versioned contract jar ({@code libs/solidus-api-2.3.2.jar}), and
 * {@code fabric.mod.json} declares {@code "solidus": ">=2.3.2 <3.0.0"} so
 * Fabric's loader — not a log line — rejects incompatible combinations.
 * If the contract changes shape, Enforcer fails to COMPILE, not to
 * pay bounties.</p>
 *
 * <p>The public surface of this class (method names, signatures, and the
 * {@link BalanceEntryData}/{@link TransactionEntryData} records) is
 * unchanged from the reflective build, so the rest of the mod needs no
 * changes. Fail-closed defaults are preserved exactly: {@code false},
 * {@code -1.0}, empty lists, empty price table.</p>
 *
 * <p>Threading: all Core results arrive as {@link CompletableFuture}s and
 * are returned to callers unchanged; this bridge never blocks (the one
 * exception is the shop-price refresh, which joins its bounded future on
 * background threads only — never the server thread).</p>
 */
public final class SolidusBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    /** Bounded wait for the shop-price snapshot during a background refresh. */
    private static final int SHOP_PRICE_TIMEOUT_SECONDS = 5;

    /** Cached shop price table (material -> sell price); refreshed lazily. */
    private static volatile Map<String, Double> shopPriceCache = Map.of();
    private static volatile long shopCacheLoadedAtMs;
    private static final long SHOP_CACHE_TTL_MS = 30L * 60L * 1000L;

    /** Logged once when Core is missing so the fail-closed state is visible. */
    private static volatile boolean loggedCoreMissing;

    private SolidusBridge() {
    }

    /**
     * The contract instance, or null when Core is absent or still
     * initializing. With a correct fabric.mod.json the loader already
     * guarantees Core >= 2.3.2 is present; the null path exists for
     * initialization-order windows, misbuilt jars and unit tests, and every
     * consumer degrades fail-closed (the same policy as the reflective
     * bridge it replaces).
     */
    private static SolidusApi api() {
        SolidusApi api = SolidusApiAccess.get();
        if (api == null && !loggedCoreMissing) {
            loggedCoreMissing = true;
            LOGGER.warn("Solidus Core contract not installed — Enforcer economy features run disabled (fail-closed). "
                + "This is expected only when testing without Solidus Core.");
        }
        return api;
    }

    /** Player identity pair the offline-safe contract calls are keyed on. */
    private static String nameOf(ServerPlayer player) {
        return player.getName().getString();
    }

    // ------------------------------------------------------------------
    // Balance operations (Core performs the atomicity; we never pre-check)
    // ------------------------------------------------------------------

    public static CompletableFuture<Boolean> hasSufficientBalance(ServerPlayer player, double amount) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(false);
        }
        return api.hasSufficientBalance(player.getUUID(), nameOf(player), amount);
    }

    /**
     * Atomic deduction — Core checks and subtracts inside its executor and
     * completes with a negative value when funds are insufficient. Callers
     * must treat {@code result < 0} as failure; no separate balance check is
     * ever needed (and using one would reintroduce a TOCTOU race).
     */
    public static CompletableFuture<Double> subtractBalance(ServerPlayer player, double amount) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        return api.subtractBalance(player.getUUID(), nameOf(player), amount);
    }

    public static CompletableFuture<Double> addBalance(ServerPlayer player, double amount) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        return api.addBalance(player.getUUID(), nameOf(player), amount);
    }

    public static CompletableFuture<Double> getBalance(ServerPlayer player) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        return api.getBalance(player.getUUID(), nameOf(player));
    }

    public static CompletableFuture<Double> addBalanceOffline(UUID uuid, String name, double amount) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        // The contract's addBalance is offline-safe by design (durable row
        // created on first touch) — exactly the legacy addBalanceOffline.
        return api.addBalance(uuid, name, amount);
    }

    // ------------------------------------------------------------------
    // Read-only queries
    // ------------------------------------------------------------------

    public static CompletableFuture<List<BalanceEntryData>> getTopBalances(int limit) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return api.getTopBalances(limit)
            .thenApply(entries -> {
                List<BalanceEntryData> result = new ArrayList<>(entries.size());
                for (BalanceEntry entry : entries) {
                    // Core's contract record carries the UUID too — callers
                    // that resolve by name keep working as before.
                    result.add(new BalanceEntryData(entry.playerName(), entry.balance()));
                }
                return result;
            })
            .exceptionally(t -> {
                LOGGER.error("getTopBalances failed through the contract: {}", t.toString());
                return Collections.emptyList();
            });
    }

    public static CompletableFuture<List<TransactionEntryData>> getTransactions(UUID playerUuid, int limit) {
        SolidusApi api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return api.getTransactions(playerUuid, limit)
            .thenApply(records -> {
                List<TransactionEntryData> entries = new ArrayList<>(records.size());
                for (TransactionRecord record : records) {
                    entries.add(new TransactionEntryData(record.timestamp(), record.targetUuid()));
                }
                return entries;
            })
            .exceptionally(t -> {
                LOGGER.error("getTransactions failed through the contract: {}", t.toString());
                return Collections.emptyList();
            });
    }

    /** Cached material -> sell-price table built from Core's live shop
     * snapshot. Returns an empty map when Core is absent. Refreshed at most
     * once per TTL so the kill pipeline never hammers the contract per item.
     *
     * <p>ENF-10: stale-while-revalidate — an expired table is refreshed
     * asynchronously and the stale copy is served meanwhile, so a death-time
     * valuation never blocks on the refresh. Only a truly cold cache (empty,
     * before the startup warm-up lands) falls back to a synchronous load,
     * bounded by the small shop config size. A cold table would otherwise
     * value every inventory at zero and floor the first kill's payout at the
     * naked-penalty minimum.
     */
    public static Map<String, Double> getShopSellPrices() {
        long now = System.currentTimeMillis();
        Map<String, Double> cached = shopPriceCache;
        if (cached.isEmpty()) {
            return refreshShopPricesSynchronously();
        }
        if (now - shopCacheLoadedAtMs >= SHOP_CACHE_TTL_MS) {
            refreshShopPricesAsync();
        }
        return cached;
    }

    /** Asynchronous warm-up — called at server start (ENF-10). */
    public static void warmShopPriceCacheAsync() {
        refreshShopPricesAsync();
    }

    /** At most one background refresh is ever in flight. */
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);

    private static void refreshShopPricesAsync() {
        if (REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    refreshShopPricesSynchronously();
                } finally {
                    REFRESH_IN_FLIGHT.set(false);
                }
            });
        }
    }

    private static synchronized Map<String, Double> refreshShopPricesSynchronously() {
        long now = System.currentTimeMillis();
        Map<String, Double> cached = shopPriceCache;
        if (!cached.isEmpty() && now - shopCacheLoadedAtMs < SHOP_CACHE_TTL_MS) {
            return cached;
        }
        Map<String, Double> fresh = loadShopPrices();
        if (!fresh.isEmpty()) {
            shopPriceCache = fresh;
            shopCacheLoadedAtMs = now;
            LOGGER.info("Refreshed Enforcer shop price cache ({} items)", fresh.size());
            return fresh;
        }
        return cached;
    }

    private static Map<String, Double> loadShopPrices() {
        SolidusApi api = api();
        if (api == null) {
            return Map.of();
        }
        Map<String, Double> prices = new LinkedHashMap<>();
        try {
            // Core already normalizes materials to uppercase and keeps only
            // sellable items (sellPrice > 0); first-writer-wins across
            // sections mirrors the old reflective walk's putIfAbsent.
            Map<String, Double> table = api.getShopSellPrices()
                .get(SHOP_PRICE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (table != null) {
                for (Map.Entry<String, Double> e : table.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && e.getValue() > 0.0) {
                        prices.putIfAbsent(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while refreshing the shop price table");
        } catch (Exception e) {
            LOGGER.error("Failed to load shop prices through the contract: {}", e.toString());
        }
        return prices;
    }

    public static boolean isAvailable() {
        return api() != null;
    }

    /** Top-balance row (contract record fields mapped; resolve by name upstream). */
    public record BalanceEntryData(String playerName, double balance) {
    }

    public record TransactionEntryData(long timestamp, UUID targetUuid) {
    }
}
