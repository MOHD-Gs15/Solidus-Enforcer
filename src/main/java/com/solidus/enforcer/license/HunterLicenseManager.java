package com.solidus.enforcer.license;

import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;

/**
 * Purchases and queries weekly hunter licenses.
 *
 * Payment safety: {@code subtractBalance} is the atomic check-and-deduct on
 * the Core side; there is no separate balance pre-check (the old TOCTOU), and
 * a failed DB write always triggers a refund attempt before reporting failure.
 */
public final class HunterLicenseManager {
    private final EnforcerStorage storage;
    private final ConfigManager config;

    public HunterLicenseManager(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    public CompletableFuture<Optional<LicenseData>> getLicense(UUID playerUuid) {
        return this.storage.getLicense(playerUuid);
    }

    public CompletableFuture<Boolean> hasActiveLicense(UUID playerUuid) {
        return this.getLicense(playerUuid).thenApply(optional -> optional.filter(LicenseData::isValid).isPresent());
    }

    public CompletableFuture<PurchaseResult> purchaseLicense(ServerPlayer player, LicenseTier tier) {
        if (player == null || tier == null) {
            return CompletableFuture.completedFuture(new PurchaseResult(false, "Invalid license request", null));
        }
        double cost = this.config.getLicenseWeeklyCost(tier.name());
        if (!Double.isFinite(cost) || cost <= 0.0) {
            return CompletableFuture.completedFuture(new PurchaseResult(false, "License price is misconfigured", null));
        }
        UUID uuid = player.getUUID();
        String name = player.getName().getString();
        long now = System.currentTimeMillis();
        LicenseData license = new LicenseData(uuid, name, tier, now,
                now + java.util.concurrent.TimeUnit.DAYS.toMillis(this.config.getLicenseDurationDays()), true);
        return SolidusBridge.subtractBalance(player, cost).thenCompose(newBalance -> {
            if (newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                return CompletableFuture.completedFuture(new PurchaseResult(false,
                        "Payment failed — you need " + String.format("%.2f S$", cost), null));
            }
            return this.storage.saveLicense(license).thenCompose(ignored -> {
                PurchaseResult success = new PurchaseResult(true,
                        tier.displayName() + "\u00A7r Hunter License active for "
                                + this.config.getLicenseDurationDays() + " days (" + String.format("%.2f S$", cost) + ")",
                        license);
                return CompletableFuture.completedFuture(success);
            }).exceptionally(error -> {
                LOGGER.error("License save failed after payment — attempting refund for {}", name, error);
                SolidusBridge.addBalance(player, cost);
                return new PurchaseResult(false, "License activation failed; payment refunded", null);
            });
        });
    }

    public CompletableFuture<Void> deactivate(UUID playerUuid) {
        return this.storage.deactivateLicense(playerUuid);
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Solidus-Enforcer");

    public record PurchaseResult(boolean success, String message, LicenseData license) {
    }
}
