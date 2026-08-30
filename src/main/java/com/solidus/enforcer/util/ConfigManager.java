package com.solidus.enforcer.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and validates config/solidus-enforcer/enforcer-config.json.
 *
 * Every getter validates its value and falls back to the documented default
 * when the file is missing, malformed, or contains out-of-range numbers, so a
 * bad edit can never crash the server or poison the economy with NaN rates.
 */
public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configDir;
    private final Path configFile;
    private volatile JsonObject config = new JsonObject();

    public ConfigManager(Path serverConfigDir) {
        this.configDir = serverConfigDir.resolve("solidus-enforcer");
        this.configFile = this.configDir.resolve("enforcer-config.json");
    }

    public void load() {
        try {
            Files.createDirectories(this.configDir);
            if (Files.exists(this.configFile)) {
                String content = Files.readString(this.configFile);
                this.config = JsonParser.parseString(content).getAsJsonObject();
                LOGGER.info("Configuration loaded from {}", this.configFile);
            } else {
                String defaults = this.loadDefaults();
                Files.writeString(this.configFile, defaults);
                this.config = JsonParser.parseString(defaults).getAsJsonObject();
                LOGGER.info("Default configuration written to {}", this.configFile);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration, using built-in defaults", e);
            this.config = new JsonObject();
        }
    }

    public void reload() {
        this.load();
    }

    private String loadDefaults() {
        try (InputStream is = this.getClass().getResourceAsStream("/enforcer-config.json")) {
            return is == null ? "{}" : new String(is.readAllBytes());
        } catch (IOException e) {
            LOGGER.error("Bundled default configuration is unreadable", e);
            return "{}";
        }
    }

    // ------------------------------------------------------------------
    // Primitive readers
    // ------------------------------------------------------------------

    private double getDouble(String path, double fallback) {
        JsonObject node = this.config;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (node == null || !node.has(parts[i]) || !node.get(parts[i]).isJsonObject()) {
                return fallback;
            }
            node = node.getAsJsonObject(parts[i]);
        }
        if (node == null || !node.has(parts[parts.length - 1])) {
            return fallback;
        }
        try {
            double value = node.get(parts[parts.length - 1]).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (Exception e) {
            LOGGER.warn("Config value '{}' is not a number; using default {}", path, fallback);
            return fallback;
        }
    }

    private int getInt(String path, int fallback) {
        return (int) Math.round(this.getDouble(path, fallback));
    }

    private boolean getBool(String path, boolean fallback) {
        JsonObject node = this.config;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (node == null || !node.has(parts[i]) || !node.get(parts[i]).isJsonObject()) {
                return fallback;
            }
            node = node.getAsJsonObject(parts[i]);
        }
        if (node == null || !node.has(parts[parts.length - 1])) {
            return fallback;
        }
        try {
            return node.get(parts[parts.length - 1]).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double clampRatio(double value, double fallback) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            return fallback;
        }
        return value;
    }

    private static double clampPositive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static int clampPositiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    // ------------------------------------------------------------------
    // Bounty limits
    // ------------------------------------------------------------------

    public double getMinBounty() {
        return clampPositive(this.getDouble("bounty_limits.min_bounty", 500.0), 500.0);
    }

    public double getMaxBounty() {
        return Math.max(this.getMinBounty(), clampPositive(this.getDouble("bounty_limits.max_bounty", 1_000_000.0), 1_000_000.0));
    }

    public int getMaxActivePerPlayer() {
        return clampPositiveInt(this.getInt("bounty_limits.max_active_per_player", 3), 1);
    }

    public int getMaxBountiesPerTarget() {
        return clampPositiveInt(this.getInt("bounty_limits.max_bounties_per_target", 5), 1);
    }

    public long getBountyDurationDays() {
        return clampPositiveInt(this.getInt("bounty_limits.bounty_duration_days", 30), 1);
    }

    // ------------------------------------------------------------------
    // Blood tax
    // ------------------------------------------------------------------

    public boolean isBloodTaxEnabled() {
        return this.getBool("blood_tax.enabled", true);
    }

    public double getBloodTaxRate() {
        return clampRatio(this.getDouble("blood_tax.tax_rate", 0.10), 0.10);
    }

    public double getTreasuryShare() {
        return clampRatio(this.getDouble("blood_tax.treasury_share", 0.50), 0.50);
    }

    public double getBurnShare() {
        return clampRatio(this.getDouble("blood_tax.burn_share", 0.50), 0.50);
    }

    // ------------------------------------------------------------------
    // Contract fees (decay on standing bounties)
    // ------------------------------------------------------------------

    public boolean isContractFeesEnabled() {
        return this.getBool("contract_fees.enabled", true);
    }

    public double getContractDailyRate() {
        return clampRatio(this.getDouble("contract_fees.daily_rate", 0.01), 0.01);
    }

    public double getContractMinimumBounty() {
        return clampPositive(this.getDouble("contract_fees.minimum_bounty", 100.0), 100.0);
    }

    /** Grace period in milliseconds; newly placed bounties are exempt. */
    public long getContractGracePeriodMs() {
        int hours = clampPositiveInt(this.getInt("contract_fees.grace_period_hours", 24), 0);
        return hours * 3_600_000L;
    }

    // ------------------------------------------------------------------
    // Value-drop requirement (anti naked-farming)
    // ------------------------------------------------------------------

    public boolean isValueDropEnabled() {
        return this.getBool("value_drop_requirement.enabled", true);
    }

    public double getMinimumGearRatio() {
        return clampRatio(this.getDouble("value_drop_requirement.minimum_gear_ratio", 0.20), 0.20);
    }

    public double getNakedPenaltyRatio() {
        return clampRatio(this.getDouble("value_drop_requirement.naked_penalty_ratio", 0.05), 0.05);
    }

    // ------------------------------------------------------------------
    // Alliance / damage split
    // ------------------------------------------------------------------

    public double getAllianceDamageShare() {
        return clampRatio(this.getDouble("alliance_split.damage_share", 0.70), 0.70);
    }

    public double getAllianceFinishingBonus() {
        return clampRatio(this.getDouble("alliance_split.finishing_bonus", 0.30), 0.30);
    }

    public int getTrackingWindowSeconds() {
        return clampPositiveInt(this.getInt("alliance_split.tracking_window_seconds", 120), 10);
    }

    // ------------------------------------------------------------------
    // Autonomous bounties
    // ------------------------------------------------------------------

    public boolean isAutonomousBountiesEnabled() {
        return this.getBool("autonomous_bounties.enabled", true);
    }

    public double getWealthMonopolyThreshold() {
        return clampRatio(this.getDouble("autonomous_bounties.wealth_monopoly_threshold", 0.40), 0.40);
    }

    public int getKillStreakThreshold() {
        return clampPositiveInt(this.getInt("autonomous_bounties.kill_streak_threshold", 10), 2);
    }

    public double getKdRatioThreshold() {
        return clampPositive(this.getDouble("autonomous_bounties.kd_ratio_threshold", 4.0), 4.0);
    }

    public int getMinKillsForKdCheck() {
        return clampPositiveInt(this.getInt("autonomous_bounties.min_kills_for_kd_check", 10), 5);
    }

    public double getMinAutoBounty() {
        return clampPositive(this.getDouble("autonomous_bounties.min_auto_bounty", 1000.0), 1000.0);
    }

    public double getMaxAutoBounty() {
        return Math.max(this.getMinAutoBounty(), clampPositive(this.getDouble("autonomous_bounties.max_auto_bounty", 50_000.0), 50_000.0));
    }

    public int getAutonomousCheckIntervalMinutes() {
        return clampPositiveInt(this.getInt("autonomous_bounties.check_interval_minutes", 30), 5);
    }

    public double getMonopolyBountyBase() {
        return clampPositive(this.getDouble("autonomous_bounties.monopoly_bounty_base", 5000.0), 5000.0);
    }

    public double getKillStreakBountyBase() {
        return clampPositive(this.getDouble("autonomous_bounties.kill_streak_bounty_base", 2000.0), 2000.0);
    }

    // ------------------------------------------------------------------
    // Collusion detection
    // ------------------------------------------------------------------

    public boolean isCollusionDetectionEnabled() {
        return this.getBool("collusion_detection.enabled", true);
    }

    /** Window for repeated killer->victim kill pairs, in minutes. */
    public int getCollusionKillWindowMinutes() {
        return clampPositiveInt(this.getInt("collusion_detection.kill_pair_window_minutes", 60), 15);
    }

    /** Same killer->victim kills inside the window before it is flagged. */
    public int getMaxSamePairKills() {
        return clampPositiveInt(this.getInt("collusion_detection.max_same_pair_kills", 3), 2);
    }

    /** Alternating A-kills-B / B-kills-A swaps inside the window before flagged. */
    public int getMaxMutualSwaps() {
        return clampPositiveInt(this.getInt("collusion_detection.max_mutual_swaps", 2), 1);
    }

    public int getTransactionLookbackDays() {
        return clampPositiveInt(this.getInt("collusion_detection.transaction_lookback_days", 30), 7);
    }

    public int getMaxMutualTransactions() {
        return clampPositiveInt(this.getInt("collusion_detection.max_mutual_transactions", 5), 1);
    }

    public boolean isSameClaimPenaltyEnabled() {
        return this.getBool("collusion_detection.same_claim_penalty", true);
    }

    // ------------------------------------------------------------------
    // Hunter licenses
    // ------------------------------------------------------------------

    public double getLicenseWeeklyCost(String tier) {
        try {
            double cost = this.config.getAsJsonObject("license_tiers")
                    .getAsJsonObject(tier.toLowerCase(Locale.ROOT))
                    .get("weekly_cost").getAsDouble();
            return clampPositive(cost, defaultLicenseCost(tier));
        } catch (Exception e) {
            return defaultLicenseCost(tier);
        }
    }

    private static double defaultLicenseCost(String tier) {
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "SILVER" -> 700.0;
            case "GOLD" -> 1200.0;
            default -> 500.0;
        };
    }

    public int getLicenseDurationDays() {
        return clampPositiveInt(this.getInt("license.duration_days", 7), 7);
    }

    // ------------------------------------------------------------------
    // Compass tracking
    // ------------------------------------------------------------------

    public int getCompassUpdateIntervalTicks() {
        return clampPositiveInt(this.getInt("compass.update_interval_ticks", 1200), 200);
    }

    public int getSilverAccuracyBlocks() {
        return clampPositiveInt(this.getInt("compass.silver_accuracy_blocks", 50), 50);
    }

    public int getGoldAccuracyBlocks() {
        return clampPositiveInt(this.getInt("compass.gold_accuracy_blocks", 5), 1);
    }

    public int getTrackCooldownMinutes() {
        return clampPositiveInt(this.getInt("compass.track_cooldown_minutes", 5), 1);
    }
}
