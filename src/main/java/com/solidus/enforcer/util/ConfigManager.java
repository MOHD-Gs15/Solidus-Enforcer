/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus-Enforcer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path configDir;
    private final Path configFile;
    private JsonObject config;

    public ConfigManager(Path serverConfigDir) {
        this.configDir = serverConfigDir.resolve("solidus-enforcer");
        this.configFile = this.configDir.resolve("enforcer-config.json");
    }

    public void load() {
        try {
            Files.createDirectories(this.configDir, new FileAttribute[0]);
            if (Files.exists(this.configFile, new LinkOption[0])) {
                String content = Files.readString(this.configFile);
                this.config = JsonParser.parseString((String)content).getAsJsonObject();
                LOGGER.info("Configuration loaded from {}", (Object)this.configFile);
            } else {
                String defaults = this.loadDefaults();
                Files.writeString(this.configFile, (CharSequence)defaults, new OpenOption[0]);
                this.config = JsonParser.parseString((String)defaults).getAsJsonObject();
                LOGGER.info("Default configuration created at {}", (Object)this.configFile);
            }
        }
        catch (Exception e) {
            LOGGER.error("Failed to load configuration, using defaults", e);
            this.config = new JsonObject();
        }
    }

    public void reload() {
        this.load();
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private String loadDefaults() {
        try (InputStream is = this.getClass().getResourceAsStream("/enforcer-config.json");){
            if (is == null) return "{}";
            String string = new String(is.readAllBytes());
            return string;
        }
        catch (IOException e) {
            LOGGER.warn("Could not load default config from resources", (Throwable)e);
        }
        return "{}";
    }

    public double getLicenseWeeklyCost(String tier) {
        try {
            double cost = this.config.getAsJsonObject("license_tiers").getAsJsonObject(tier.toLowerCase()).get("weekly_cost").getAsDouble();
            if (Double.isFinite(cost) && cost > 0.0) {
                return cost;
            }
            throw new IllegalArgumentException("Invalid license cost");
        }
        catch (Exception e) {
            return switch (tier.toUpperCase()) {
                case "BRONZE" -> 500.0;
                case "SILVER" -> 700.0;
                case "GOLD" -> 1200.0;
                default -> 500.0;
            };
        }
    }

    public boolean isBloodTaxEnabled() {
        return this.getBool("blood_tax.enabled", true);
    }

    public double getBloodTaxRate() {
        return this.getDouble("blood_tax.tax_rate", 0.1);
    }

    public double getTreasuryShare() {
        return this.getDouble("blood_tax.treasury_share", 0.5);
    }

    public double getBurnShare() {
        return this.getDouble("blood_tax.burn_share", 0.5);
    }

    public boolean isContractFeesEnabled() {
        return this.getBool("contract_fees.enabled", true);
    }

    public double getContractDailyRate() {
        return this.getDouble("contract_fees.daily_rate", 0.01);
    }

    public double getContractMinimumBounty() {
        return this.getDouble("contract_fees.minimum_bounty", 100.0);
    }

    public int getContractGracePeriodHours() {
        return this.getInt("contract_fees.grace_period_hours", 24);
    }

    public boolean isValueDropEnabled() {
        return this.getBool("value_drop_requirement.enabled", true);
    }

    public double getMinimumGearRatio() {
        return this.getDouble("value_drop_requirement.minimum_gear_ratio", 0.2);
    }

    public double getNakedPenaltyRatio() {
        return this.getDouble("value_drop_requirement.naked_penalty_ratio", 0.05);
    }

    public double getAllianceDamageShare() {
        return this.getDouble("alliance_split.damage_share", 0.7);
    }

    public double getAllianceFinishingBonus() {
        return this.getDouble("alliance_split.finishing_bonus", 0.3);
    }

    public int getTrackingWindowSeconds() {
        return this.getInt("alliance_split.tracking_window_seconds", 120);
    }

    public boolean isAutonomousBountiesEnabled() {
        return this.getBool("autonomous_bounties.enabled", true);
    }

    public double getWealthMonopolyThreshold() {
        return this.getDouble("autonomous_bounties.wealth_monopoly_threshold", 0.4);
    }

    public int getKillStreakThreshold() {
        return this.getInt("autonomous_bounties.kill_streak_threshold", 10);
    }

    public double getKdRatioThreshold() {
        return this.getDouble("autonomous_bounties.kd_ratio_threshold", 4.0);
    }

    public double getMinAutoBounty() {
        return this.getDouble("autonomous_bounties.min_auto_bounty", 1000.0);
    }

    public double getMaxAutoBounty() {
        return this.getDouble("autonomous_bounties.max_auto_bounty", 50000.0);
    }

    public int getAutonomousCheckIntervalMinutes() {
        return this.getInt("autonomous_bounties.check_interval_minutes", 30);
    }

    public double getMonopolyBountyBase() {
        return this.getDouble("autonomous_bounties.monopoly_bounty_base", 5000.0);
    }

    public double getKillStreakBountyBase() {
        return this.getDouble("autonomous_bounties.kill_streak_bounty_base", 2000.0);
    }

    public boolean isCollusionDetectionEnabled() {
        return this.getBool("collusion_detection.enabled", true);
    }

    public int getTransactionLookbackDays() {
        return this.getInt("collusion_detection.transaction_lookback_days", 30);
    }

    public int getMaxMutualTransactions() {
        return this.getInt("collusion_detection.max_mutual_transactions", 5);
    }

    public boolean isSameClaimPenalty() {
        return this.getBool("collusion_detection.same_claim_penalty", true);
    }

    public double getMinBounty() {
        return this.getDouble("bounty_limits.min_bounty", 500.0);
    }

    public double getMaxBounty() {
        return this.getDouble("bounty_limits.max_bounty", 1000000.0);
    }

    public int getMaxActivePerPlayer() {
        return this.getInt("bounty_limits.max_active_per_player", 3);
    }

    public int getMaxBountiesPerTarget() {
        return this.getInt("bounty_limits.max_bounties_per_target", 5);
    }

    public int getBountyDurationDays() {
        return this.getInt("bounty_limits.bounty_duration_days", 30);
    }

    public int getCompassUpdateIntervalTicks() {
        return this.getInt("compass.update_interval_ticks", 1200);
    }

    public int getSilverAccuracyBlocks() {
        return this.getInt("compass.silver_accuracy_blocks", 50);
    }

    public int getGoldAccuracyBlocks() {
        return this.getInt("compass.gold_accuracy_blocks", 5);
    }

    private boolean getBool(String path, boolean def) {
        try {
            return this.getNested(path).getAsBoolean();
        }
        catch (Exception e) {
            return def;
        }
    }

    private double getDouble(String path, double def) {
        try {
            double value = this.getNested(path).getAsDouble();
            return Double.isFinite(value) ? value : def;
        }
        catch (Exception e) {
            return def;
        }
    }

    private int getInt(String path, int def) {
        try {
            return this.getNested(path).getAsInt();
        }
        catch (Exception e) {
            return def;
        }
    }

    private JsonElement getNested(String path) {
        String[] parts = path.split("\\.");
        JsonObject current = this.config;
        for (int i = 0; i < parts.length - 1; ++i) {
            current = current.getAsJsonObject(parts[i]);
        }
        return current.get(parts[parts.length - 1]);
    }
}
