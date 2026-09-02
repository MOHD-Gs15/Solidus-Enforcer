package com.solidus.enforcer.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflection bridge to Solidus Core — zero compile dependency, automatic
 * activation when Core is present, permanent fail-closed degradation when not.
 *
 * Design notes:
 * <ul>
 *   <li>All Core results arrive as {@link CompletableFuture}s and are returned
 *       to callers unchanged; this bridge never blocks.</li>
 *   <li>{@code SolidusAPI.getInstance()} is re-resolved on every call: Core may
 *       initialise after Enforcer. Method handles are cached once; instance
 *       lookup is cheap reflection.</li>
 *   <li>{@code BalanceEntryData} carries the resolved player UUID when the
 *       caller supplies one — Core's BalanceEntry record itself has no UUID
 *       field, so callers must resolve names (see AutonomousBountyEngine).</li>
 * </ul>
 */
public final class SolidusBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private static Method hasSufficientBalanceMethod;
    private static Method addBalanceMethod;
    private static Method subtractBalanceMethod;
    private static Method getBalanceMethod;
    private static Method addBalanceOfflineMethod;
    private static Method getTopBalancesMethod;
    private static Method getTransactionLogMethod;
    private static Method getSectionsMethod;
    private static Method shopSectionItemsMethod;
    private static Method shopItemMaterialMethod;
    private static Method shopItemSellPriceMethod;
    private static Method getTransactionsMethod;
    private static Method txEntryTimestampMethod;
    private static Method txEntryTargetUuidMethod;
    private static Method balanceEntryPlayerNameMethod;
    private static Method balanceEntryBalanceMethod;

    private static volatile boolean initialized;
    private static volatile boolean initFailed;

    /** Cached shop price table (material -> sell price); refreshed lazily. */
    private static volatile Map<String, Double> shopPriceCache = Map.of();
    private static volatile long shopCacheLoadedAtMs;
    private static final long SHOP_CACHE_TTL_MS = 30L * 60L * 1000L;

    private SolidusBridge() {
    }

    private static synchronized void ensureInitialized() {
        if (initialized || initFailed) {
            return;
        }
        try {
            if (!FabricLoader.getInstance().isModLoaded("solidus")) {
                LOGGER.info("Solidus Core not installed — Enforcer economy features run disabled (fail-closed)");
                initFailed = true;
                return;
            }
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            hasSufficientBalanceMethod = apiClass.getMethod("hasSufficientBalance", ServerPlayer.class, Double.TYPE);
            addBalanceMethod = apiClass.getMethod("addBalance", ServerPlayer.class, Double.TYPE);
            subtractBalanceMethod = apiClass.getMethod("subtractBalance", ServerPlayer.class, Double.TYPE);
            getBalanceMethod = apiClass.getMethod("getBalance", ServerPlayer.class);
            addBalanceOfflineMethod = apiClass.getMethod("addBalanceOffline", UUID.class, String.class, Double.TYPE);
            getTopBalancesMethod = apiClass.getMethod("getTopBalances", Integer.TYPE);
            getTransactionLogMethod = apiClass.getMethod("getTransactionLog");

            Class<?> shopManagerClass = Class.forName("com.solidus.shop.ShopManager");
            getSectionsMethod = shopManagerClass.getMethod("getSections");
            Class<?> shopSectionClass = Class.forName("com.solidus.shop.ShopManager$ShopSection");
            shopSectionItemsMethod = shopSectionClass.getMethod("items");
            Class<?> shopItemClass = Class.forName("com.solidus.shop.ShopManager$ShopItem");
            shopItemMaterialMethod = shopItemClass.getMethod("material");
            shopItemSellPriceMethod = shopItemClass.getMethod("sellPrice");

            Class<?> transactionLogClass = Class.forName("com.solidus.economy.TransactionLog");
            getTransactionsMethod = transactionLogClass.getMethod("getTransactions", UUID.class, Integer.TYPE);
            Class<?> txEntryClass = Class.forName("com.solidus.economy.TransactionLog$TransactionEntry");
            txEntryTimestampMethod = txEntryClass.getMethod("timestamp");
            txEntryTargetUuidMethod = txEntryClass.getMethod("targetUuid");

            Class<?> balanceEntryClass = Class.forName("com.solidus.economy.SQLiteStorage$BalanceEntry");
            balanceEntryPlayerNameMethod = balanceEntryClass.getMethod("playerName");
            balanceEntryBalanceMethod = balanceEntryClass.getMethod("balance");

            initialized = true;
            LOGGER.info("SolidusBridge initialized — all Core API methods cached");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Solidus Core class not found ({}). Economy features disabled.", e.getMessage());
            initFailed = true;
        } catch (NoSuchMethodException e) {
            LOGGER.error("Solidus Core API method not found — Core version mismatch? Economy features disabled. ({})", e.getMessage());
            initFailed = true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SolidusBridge", e);
            initFailed = true;
        }
    }

    private static Object getAPI() {
        ensureInitialized();
        if (initFailed) {
            return null;
        }
        try {
            Object api = Class.forName("com.solidus.api.SolidusAPI")
                    .getMethod("getInstance").invoke(null);
            return api;
        } catch (Exception e) {
            LOGGER.debug("SolidusAPI.getInstance() unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Object getShopManagerInstance() {
        ensureInitialized();
        if (initFailed) {
            return null;
        }
        try {
            return Class.forName("com.solidus.SolidusMod")
                    .getMethod("getShopManager").invoke(null);
        } catch (Exception e) {
            LOGGER.debug("Could not get ShopManager: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Balance operations (Core performs the atomicity; we never pre-check)
    // ------------------------------------------------------------------

    public static CompletableFuture<Boolean> hasSufficientBalance(ServerPlayer player, double amount) {
        Object api = getAPI();
        if (api == null || hasSufficientBalanceMethod == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Object result = hasSufficientBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture<?> cf ? asBoolean(cf) : CompletableFuture.completedFuture(false);
        } catch (Exception e) {
            LOGGER.error("Reflection error: hasSufficientBalance", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Atomic deduction — Core checks and subtracts inside its executor and
     * completes with a negative value when funds are insufficient. Callers
     * must treat {@code result < 0} as failure; no separate balance check is
     * ever needed (and using one would reintroduce a TOCTOU race).
     */
    public static CompletableFuture<Double> subtractBalance(ServerPlayer player, double amount) {
        Object api = getAPI();
        if (api == null || subtractBalanceMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            Object result = subtractBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture<?> cf ? asDouble(cf, -1.0) : CompletableFuture.completedFuture(-1.0);
        } catch (Exception e) {
            LOGGER.error("Reflection error: subtractBalance", e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> addBalance(ServerPlayer player, double amount) {
        Object api = getAPI();
        if (api == null || addBalanceMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            Object result = addBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture<?> cf ? asDouble(cf, -1.0) : CompletableFuture.completedFuture(-1.0);
        } catch (Exception e) {
            LOGGER.error("Reflection error: addBalance", e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> getBalance(ServerPlayer player) {
        Object api = getAPI();
        if (api == null || getBalanceMethod == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        try {
            Object result = getBalanceMethod.invoke(api, player);
            return result instanceof CompletableFuture<?> cf ? asDouble(cf, 0.0) : CompletableFuture.completedFuture(0.0);
        } catch (Exception e) {
            LOGGER.error("Reflection error: getBalance", e);
            return CompletableFuture.completedFuture(0.0);
        }
    }

    public static CompletableFuture<Double> addBalanceOffline(UUID uuid, String name, double amount) {
        Object api = getAPI();
        if (api == null || addBalanceOfflineMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            Object result = addBalanceOfflineMethod.invoke(api, uuid, name, amount);
            return result instanceof CompletableFuture<?> cf ? asDouble(cf, -1.0) : CompletableFuture.completedFuture(-1.0);
        } catch (Exception e) {
            LOGGER.error("Reflection error: addBalanceOffline", e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    // ------------------------------------------------------------------
    // Read-only queries
    // ------------------------------------------------------------------

    public static CompletableFuture<List<BalanceEntryData>> getTopBalances(int limit) {
        Object api = getAPI();
        if (api == null || getTopBalancesMethod == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            Object result = getTopBalancesMethod.invoke(api, limit);
            if (result instanceof CompletableFuture<?> cf) {
                return cf.thenApply(rawList -> {
                    if (!(rawList instanceof List<?> list)) {
                        return Collections.<BalanceEntryData>emptyList();
                    }
                    List<BalanceEntryData> entries = new ArrayList<>();
                    for (Object entry : list) {
                        try {
                            String playerName = balanceEntryPlayerNameMethod != null
                                    ? (String) balanceEntryPlayerNameMethod.invoke(entry) : "Unknown";
                            double balance = balanceEntryBalanceMethod != null
                                    ? (Double) balanceEntryBalanceMethod.invoke(entry) : 0.0;
                            // Core's BalanceEntry has no uuid; caller resolves by name.
                            entries.add(new BalanceEntryData(playerName, balance));
                        } catch (Exception ignored) {
                        }
                    }
                    return entries;
                });
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        } catch (Exception e) {
            LOGGER.error("Reflection error: getTopBalances", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    public static CompletableFuture<List<TransactionEntryData>> getTransactions(UUID playerUuid, int limit) {
        Object api = getAPI();
        if (api == null || getTransactionLogMethod == null || getTransactionsMethod == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            Object txLog = getTransactionLogMethod.invoke(api);
            if (txLog == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            Object result = getTransactionsMethod.invoke(txLog, playerUuid, limit);
            if (result instanceof CompletableFuture<?> cf) {
                return cf.thenApply(rawList -> {
                    if (!(rawList instanceof List<?> list)) {
                        return Collections.<TransactionEntryData>emptyList();
                    }
                    List<TransactionEntryData> entries = new ArrayList<>();
                    for (Object entry : list) {
                        try {
                            long timestamp = txEntryTimestampMethod != null
                                    ? (Long) txEntryTimestampMethod.invoke(entry) : 0L;
                            UUID targetUuid = txEntryTargetUuidMethod != null
                                    && txEntryTargetUuidMethod.invoke(entry) instanceof UUID u ? u : null;
                            entries.add(new TransactionEntryData(timestamp, targetUuid));
                        } catch (Exception ignored) {
                        }
                    }
                    return entries;
                });
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        } catch (Exception e) {
            LOGGER.error("Reflection error: getTransactions", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * Cached material -> sell-price table built from the live shop config.
     * Returns an empty map when Core is absent. Refreshed at most once per
     * TTL so the kill pipeline never hammers reflection per item.
     */
    public static Map<String, Double> getShopSellPrices() {
        long now = System.currentTimeMillis();
        Map<String, Double> cached = shopPriceCache;
        if (!cached.isEmpty() && now - shopCacheLoadedAtMs < SHOP_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (SolidusBridge.class) {
            cached = shopPriceCache;
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
    }

    private static Map<String, Double> loadShopPrices() {
        Object shopManager = getShopManagerInstance();
        if (shopManager == null || getSectionsMethod == null) {
            return Map.of();
        }
        Map<String, Double> prices = new LinkedHashMap<>();
        try {
            Object rawSections = getSectionsMethod.invoke(shopManager);
            if (rawSections instanceof Map<?, ?> sections) {
                for (Object sectionObj : sections.values()) {
                    if (shopSectionItemsMethod == null) {
                        continue;
                    }
                    Object rawItems = shopSectionItemsMethod.invoke(sectionObj);
                    if (!(rawItems instanceof List<?> items)) {
                        continue;
                    }
                    for (Object item : items) {
                        try {
                            String material = shopItemMaterialMethod != null
                                    ? (String) shopItemMaterialMethod.invoke(item) : null;
                            Double sellPrice = shopItemSellPriceMethod != null
                                    ? (Double) shopItemSellPriceMethod.invoke(item) : null;
                            if (material != null && sellPrice != null && sellPrice > 0.0) {
                                prices.putIfAbsent(material.toUpperCase(Locale.ROOT), sellPrice);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Reflection error: loading shop prices", e);
        }
        return prices;
    }

    public static boolean isAvailable() {
        return getAPI() != null;
    }

    private static CompletableFuture<Boolean> asBoolean(CompletableFuture<?> cf) {
        return cf.thenApply(result -> result instanceof Boolean b && b);
    }

    private static CompletableFuture<Double> asDouble(CompletableFuture<?> cf, double fallback) {
        return cf.thenApply(result -> result instanceof Double d && Double.isFinite(d) ? d : fallback);
    }

    /** Top-balance row. Core's record has no UUID — resolve by name upstream. */
    public record BalanceEntryData(String playerName, double balance) {
    }

    public record TransactionEntryData(long timestamp, UUID targetUuid) {
    }
}
