package com.solidus.enforcer.license;

import com.solidus.enforcer.integration.SolidusBridge;
import com.solidus.enforcer.storage.EnforcerStorage;
import com.solidus.enforcer.util.ConfigManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;

public final class HunterLicenseManager {
    private static final long WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private final EnforcerStorage storage;
    private final ConfigManager config;

    public HunterLicenseManager(EnforcerStorage storage, ConfigManager config) {
        this.storage = storage;
        this.config = config;
    }

    public CompletableFuture<Optional<LicenseData>> getLicense(UUID playerUuid) {
        return storage.getLicense(playerUuid);
    }

    public CompletableFuture<Boolean> hasAnyLicense(UUID playerUuid) {
        return getLicense(playerUuid).thenApply(optional -> optional.filter(LicenseData::isValid).isPresent());
    }

    public CompletableFuture<PurchaseResult> purchaseLicense(ServerPlayer player, LicenseTier tier) {
        if (player == null || tier == null) {
            return CompletableFuture.completedFuture(new PurchaseResult(false, "Invalid license request", null));
        }
        double cost = config.getLicenseWeeklyCost(tier.name());
        if (!Double.isFinite(cost) || cost <= 0.0) {
            return CompletableFuture.completedFuture(new PurchaseResult(false, "License price is invalid", null));
        }
        LicenseData license = new LicenseData(player.getUUID(), player.getName().getString(), tier,
            System.currentTimeMillis(), System.currentTimeMillis() + WEEK_MILLIS, true);
        return SolidusBridge.hasSufficientBalance(player, cost).thenCompose(hasFunds -> {
            if (!Boolean.TRUE.equals(hasFunds)) {
                return CompletableFuture.completedFuture(new PurchaseResult(false, "Insufficient balance", null));
            }
            return SolidusBridge.subtractBalance(player, cost).thenCompose(newBalance -> {
                if (newBalance == null || !Double.isFinite(newBalance) || newBalance < 0.0) {
                    return CompletableFuture.completedFuture(new PurchaseResult(false, "Payment failed", null));
                }
                return storage.saveLicense(license)
                    .thenApply(ignored -> new PurchaseResult(true, tier.name() + " Hunter License purchased for one week", license));
            });
        });
    }

    public CompletableFuture<Void> deactivate(UUID playerUuid) {
        return storage.deactivateLicense(playerUuid);
    }

    public record PurchaseResult(boolean success, String message, LicenseData license) {
    }
}
