/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.fabricmc.loader.api.FabricLoader
 *  net.minecraft.server.level.ServerPlayer
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SolidusBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus-Enforcer");
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
    private static boolean initialized;
    private static boolean initFailed;

    private SolidusBridge() {
    }

    private static synchronized void ensureInitialized() {
        if (initialized || initFailed) {
            return;
        }
        try {
            if (!FabricLoader.getInstance().isModLoaded("solidus")) {
                LOGGER.warn("Solidus Core not loaded - Enforcer economy features disabled");
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
            getTransactionLogMethod = apiClass.getMethod("getTransactionLog", new Class[0]);
            Class<?> shopManagerClass = Class.forName("com.solidus.shop.ShopManager");
            getSectionsMethod = shopManagerClass.getMethod("getSections", new Class[0]);
            Class<?> shopSectionClass = Class.forName("com.solidus.shop.ShopManager$ShopSection");
            shopSectionItemsMethod = shopSectionClass.getMethod("items", new Class[0]);
            Class<?> shopItemClass = Class.forName("com.solidus.shop.ShopManager$ShopItem");
            shopItemMaterialMethod = shopItemClass.getMethod("material", new Class[0]);
            shopItemSellPriceMethod = shopItemClass.getMethod("sellPrice", new Class[0]);
            Class<?> transactionLogClass = Class.forName("com.solidus.economy.TransactionLog");
            getTransactionsMethod = transactionLogClass.getMethod("getTransactions", UUID.class, Integer.TYPE);
            Class<?> txEntryClass = Class.forName("com.solidus.economy.TransactionLog$TransactionEntry");
            txEntryTimestampMethod = txEntryClass.getMethod("timestamp", new Class[0]);
            txEntryTargetUuidMethod = txEntryClass.getMethod("targetUuid", new Class[0]);
            Class<?> balanceEntryClass = Class.forName("com.solidus.economy.SQLiteStorage$BalanceEntry");
            balanceEntryPlayerNameMethod = balanceEntryClass.getMethod("playerName", new Class[0]);
            balanceEntryBalanceMethod = balanceEntryClass.getMethod("balance", new Class[0]);
            initialized = true;
            LOGGER.info("SolidusBridge initialized - all Core API methods cached");
        }
        catch (ClassNotFoundException e) {
            LOGGER.warn("Solidus Core class not found ({}). Economy features disabled.", (Object)e.getMessage());
            initFailed = true;
        }
        catch (NoSuchMethodException e) {
            LOGGER.error("Solidus Core API method not found. Version mismatch? {}", (Object)e.getMessage());
            initFailed = true;
        }
        catch (Exception e) {
            LOGGER.error("Failed to initialize SolidusBridge", (Throwable)e);
            initFailed = true;
        }
    }

    private static Object getAPI() {
        SolidusBridge.ensureInitialized();
        if (initFailed) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            Method getInstance = apiClass.getMethod("getInstance", new Class[0]);
            Object api = getInstance.invoke(null, new Object[0]);
            if (api == null) {
                LOGGER.debug("SolidusAPI.getInstance() returned null - Core not yet initialized");
            }
            return api;
        }
        catch (Exception e) {
            LOGGER.debug("Could not get SolidusAPI instance: {}", (Object)e.getMessage());
            return null;
        }
    }

    private static Object getShopManagerInstance() {
        SolidusBridge.ensureInitialized();
        if (initFailed) {
            return null;
        }
        try {
            Class<?> modClass = Class.forName("com.solidus.SolidusMod");
            Method getShopManager = modClass.getMethod("getShopManager", new Class[0]);
            return getShopManager.invoke(null, new Object[0]);
        }
        catch (Exception e) {
            LOGGER.debug("Could not get ShopManager: {}", (Object)e.getMessage());
            return null;
        }
    }

    public static CompletableFuture<Boolean> hasSufficientBalance(ServerPlayer player, double amount) {
        Object api = SolidusBridge.getAPI();
        if (api == null || hasSufficientBalanceMethod == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            CompletableFuture cf;
            Object result = hasSufficientBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture ? (cf = (CompletableFuture)result) : CompletableFuture.completedFuture(false);
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: hasSufficientBalance", (Throwable)e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public static CompletableFuture<Double> addBalance(ServerPlayer player, double amount) {
        Object api = SolidusBridge.getAPI();
        if (api == null || addBalanceMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            CompletableFuture cf;
            Object result = addBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture ? (cf = (CompletableFuture)result) : CompletableFuture.completedFuture(-1.0);
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: addBalance", (Throwable)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> subtractBalance(ServerPlayer player, double amount) {
        Object api = SolidusBridge.getAPI();
        if (api == null || subtractBalanceMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            CompletableFuture cf;
            Object result = subtractBalanceMethod.invoke(api, player, amount);
            return result instanceof CompletableFuture ? (cf = (CompletableFuture)result) : CompletableFuture.completedFuture(-1.0);
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: subtractBalance", (Throwable)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<Double> getBalance(ServerPlayer player) {
        Object api = SolidusBridge.getAPI();
        if (api == null || getBalanceMethod == null) {
            return CompletableFuture.completedFuture(0.0);
        }
        try {
            CompletableFuture cf;
            Object result = getBalanceMethod.invoke(api, player);
            return result instanceof CompletableFuture ? (cf = (CompletableFuture)result) : CompletableFuture.completedFuture(0.0);
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: getBalance", (Throwable)e);
            return CompletableFuture.completedFuture(0.0);
        }
    }

    public static CompletableFuture<Double> addBalanceOffline(UUID uuid, String name, double amount) {
        Object api = SolidusBridge.getAPI();
        if (api == null || addBalanceOfflineMethod == null) {
            return CompletableFuture.completedFuture(-1.0);
        }
        try {
            CompletableFuture cf;
            Object result = addBalanceOfflineMethod.invoke(api, uuid, name, amount);
            return result instanceof CompletableFuture ? (cf = (CompletableFuture)result) : CompletableFuture.completedFuture(-1.0);
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: addBalanceOffline", (Throwable)e);
            return CompletableFuture.completedFuture(-1.0);
        }
    }

    public static CompletableFuture<List<BalanceEntryData>> getTopBalances(int limit) {
        Object api = SolidusBridge.getAPI();
        if (api == null || getTopBalancesMethod == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            Object result = getTopBalancesMethod.invoke(api, limit);
            if (result instanceof CompletableFuture) {
                CompletableFuture cf = (CompletableFuture)result;
                return cf.thenApply(rawList -> {
                    if (!(rawList instanceof List)) {
                        return Collections.emptyList();
                    }
                    List list = (List)rawList;
                    ArrayList<BalanceEntryData> entries = new ArrayList<BalanceEntryData>();
                    for (Object entry : list) {
                        try {
                            String uuidStr = null;
                            String playerName = balanceEntryPlayerNameMethod != null ? (String)balanceEntryPlayerNameMethod.invoke(entry, new Object[0]) : "Unknown";
                            double balance = balanceEntryBalanceMethod != null ? (Double)balanceEntryBalanceMethod.invoke(entry, new Object[0]) : 0.0;
                            entries.add(new BalanceEntryData(uuidStr, playerName, balance));
                        }
                        catch (Exception exception) {}
                    }
                    return entries;
                });
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: getTopBalances", (Throwable)e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    public static CompletableFuture<List<TransactionEntryData>> getTransactions(UUID playerUuid, int limit) {
        Object api = SolidusBridge.getAPI();
        if (api == null || getTransactionLogMethod == null || getTransactionsMethod == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            Object txLog = getTransactionLogMethod.invoke(api, new Object[0]);
            if (txLog == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            Object result = getTransactionsMethod.invoke(txLog, playerUuid, limit);
            if (result instanceof CompletableFuture) {
                CompletableFuture cf = (CompletableFuture)result;
                return cf.thenApply(rawList -> {
                    if (!(rawList instanceof List)) {
                        return Collections.emptyList();
                    }
                    List list = (List)rawList;
                    ArrayList<TransactionEntryData> entries = new ArrayList<TransactionEntryData>();
                    for (Object entry : list) {
                        try {
                            Object uuidObj;
                            long timestamp = txEntryTimestampMethod != null ? (Long)txEntryTimestampMethod.invoke(entry, new Object[0]) : 0L;
                            UUID targetUuid = null;
                            if (txEntryTargetUuidMethod != null && (uuidObj = txEntryTargetUuidMethod.invoke(entry, new Object[0])) instanceof UUID) {
                                UUID u;
                                targetUuid = u = (UUID)uuidObj;
                            }
                            entries.add(new TransactionEntryData(timestamp, targetUuid));
                        }
                        catch (Exception exception) {}
                    }
                    return entries;
                });
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: getTransactions", (Throwable)e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    public static Map<String, List<ShopItemData>> getShopSections() {
        Object shopManager = SolidusBridge.getShopManagerInstance();
        if (shopManager == null || getSectionsMethod == null) {
            return Collections.emptyMap();
        }
        try {
            Object rawSections = getSectionsMethod.invoke(shopManager, new Object[0]);
            if (!(rawSections instanceof Map)) {
                return Collections.emptyMap();
            }
            Map<?, ?> sections = (Map<?, ?>)rawSections;
            LinkedHashMap<String, List<ShopItemData>> result = new LinkedHashMap<String, List<ShopItemData>>();
            for (Map.Entry<?, ?> entry : sections.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString() : "";
                Object sectionObj = entry.getValue();
                ArrayList<ShopItemData> items = new ArrayList<ShopItemData>();
                if (shopSectionItemsMethod != null) {
                    try {
                        Object rawItems = shopSectionItemsMethod.invoke(sectionObj, new Object[0]);
                        if (rawItems instanceof List) {
                            List itemList = (List)rawItems;
                            for (Object item : itemList) {
                                try {
                                    double sellPrice;
                                    String material = shopItemMaterialMethod != null ? (String)shopItemMaterialMethod.invoke(item, new Object[0]) : null;
                                    double d = sellPrice = shopItemSellPriceMethod != null ? (Double)shopItemSellPriceMethod.invoke(item, new Object[0]) : 0.0;
                                    if (material == null) continue;
                                    items.add(new ShopItemData(material, sellPrice));
                                }
                                catch (Exception exception) {}
                            }
                        }
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                result.put(key, items);
            }
            return result;
        }
        catch (Exception e) {
            LOGGER.error("Reflection error: getShopSections", (Throwable)e);
            return Collections.emptyMap();
        }
    }

    public static boolean isAvailable() {
        return SolidusBridge.getAPI() != null;
    }

    static {
        initialized = false;
        initFailed = false;
    }

    public record ShopItemData(String material, double sellPrice) {
    }

    public record TransactionEntryData(long timestamp, UUID targetUuid) {
    }

    public record BalanceEntryData(String uuid, String playerName, double balance) {
    }
}
