package com.solidus.enforcer.economy;

/**
 * Pure tax/payout arithmetic — no Minecraft, no I/O. Kept side-effect free so
 * it can be unit-tested in isolation; {@link TreasuryManager} owns state.
 */
public final class EconomyMath {
    private EconomyMath() {
    }

    public record TaxSplit(double totalTax, double treasuryAmount, double burnAmount, double remainingBounty) {
    }

    /**
     * Splits a bounty payment into the blood tax (treasury + burn shares) and
     * the amount the hunter will actually fight over.
     *
     * <p>Garbage amounts (NaN / non-positive) pass through untouched with a zero
     * tax — this function never launders a bad amount into a valid-looking zero;
     * callers validate and reject.
     *
     * Shares are normalised when treasuryShare + burnShare exceeds 1.0.
     */
    public static TaxSplit bloodTax(double bountyAmount, double taxRate,
                                    double treasuryShareRate, double burnShareRate) {
        if (!Double.isFinite(bountyAmount) || bountyAmount <= 0.0
                || !Double.isFinite(taxRate) || taxRate <= 0.0) {
            return new TaxSplit(0.0, 0.0, 0.0, bountyAmount);
        }
        taxRate = clamp(taxRate, 0.0, 1.0);
        treasuryShareRate = clamp(treasuryShareRate, 0.0, 1.0);
        burnShareRate = clamp(burnShareRate, 0.0, 1.0);
        double shareTotal = treasuryShareRate + burnShareRate;
        if (shareTotal > 1.0 && shareTotal > 0.0) {
            treasuryShareRate /= shareTotal;
            burnShareRate /= shareTotal;
        }
        double totalTax = round2(bountyAmount * taxRate);
        double treasuryAmount = round2(totalTax * treasuryShareRate);
        double burnAmount = round2(totalTax - treasuryAmount);
        double remaining = round2(bountyAmount - totalTax);
        if (remaining <= 0.0) {
            return new TaxSplit(0.0, 0.0, 0.0, sanitize(bountyAmount));
        }
        return new TaxSplit(totalTax, treasuryAmount, burnAmount, remaining);
    }

    public record FeeSplit(double fee, double newAmount) {
    }

    /**
     * Daily decay of a standing bounty. The fee never pushes the bounty below
     * the configured minimum — the last S$minimum are permanent.
     */
    public static FeeSplit contractFee(double currentAmount, double dailyRate, double minimumBounty) {
        if (!Double.isFinite(currentAmount) || currentAmount <= 0.0
                || !Double.isFinite(dailyRate) || dailyRate <= 0.0
                || currentAmount <= minimumBounty) {
            return new FeeSplit(0.0, currentAmount);
        }
        double fee = round2(currentAmount * clamp(dailyRate, 0.0, 1.0));
        double newAmount = round2(currentAmount - fee);
        if (newAmount < minimumBounty) {
            newAmount = minimumBounty;
            fee = round2(currentAmount - newAmount);
        }
        return new FeeSplit(fee, newAmount);
    }

    public record PayoutSplit(double damagePool, double finishingPool) {
    }

    /**
     * Divides a bounty between the damage pool (all contributors, weighted)
     * and the finishing pool (the killer). Shares are normalised when their
     * sum is not exactly 1.0.
     */
    public static PayoutSplit allianceSplit(double totalReward, double damageShare, double finishingShare) {
        damageShare = clamp(damageShare, 0.0, 1.0);
        finishingShare = clamp(finishingShare, 0.0, 1.0);
        double total = damageShare + finishingShare;
        if (total <= 0.0) {
            damageShare = 0.0;
            finishingShare = 1.0;
        } else if (total != 1.0) {
            damageShare /= total;
            finishingShare /= total;
        }
        return new PayoutSplit(round2(totalReward * damageShare), round2(totalReward * finishingShare));
    }

    /**
     * Per-contributor damage shares. Weights are the caller's damage numbers;
     * the result always sums to {@code damagePool} (last-cent rounding drift
     * is absorbed by the largest contributor).
     */
    public static java.util.LinkedHashMap<java.util.UUID, Double> damageShares(
            java.util.Map<java.util.UUID, Double> damageByAttacker, double damagePool) {
        java.util.LinkedHashMap<java.util.UUID, Double> shares = new java.util.LinkedHashMap<>();
        double totalDamage = damageByAttacker.values().stream()
                .filter(v -> v != null && Double.isFinite(v) && v > 0.0)
                .mapToDouble(Double::doubleValue).sum();
        if (totalDamage <= 0.0 || damagePool <= 0.0) {
            return shares;
        }
        double allocated = 0.0;
        java.util.UUID largest = null;
        double largestWeight = -1.0;
        for (java.util.Map.Entry<java.util.UUID, Double> entry : damageByAttacker.entrySet()) {
            double weight = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!Double.isFinite(weight) || weight <= 0.0) {
                continue;
            }
            double share = round2(damagePool * weight / totalDamage);
            shares.put(entry.getKey(), share);
            allocated += share;
            if (weight > largestWeight) {
                largestWeight = weight;
                largest = entry.getKey();
            }
        }
        if (largest != null) {
            double drift = round2(damagePool - allocated);
            if (Math.abs(drift) > 0.009) {
                shares.merge(largest, drift, Double::sum);
            }
        }
        return shares;
    }

    /**
     * Value-drop payout: full pay when carried gear meets the required value,
     * otherwise linearly reduced down to the naked penalty floor.
     *
     * requiredValue = bounty * minimumGearRatio (documented contract).
     */
    public static double payoutRatio(double bountyAmount, double inventoryValue,
                                     double minimumGearRatio, double nakedPenaltyRatio) {
        if (!Double.isFinite(bountyAmount) || bountyAmount <= 0.0
                || !Double.isFinite(minimumGearRatio) || minimumGearRatio <= 0.0) {
            return 1.0;
        }
        double required = round2(bountyAmount * clamp(minimumGearRatio, 0.0, 1.0));
        if (required <= 0.0 || inventoryValue >= required) {
            return 1.0;
        }
        double ratio = inventoryValue / required;
        double floor = clamp(nakedPenaltyRatio, 0.0, 1.0);
        return clamp(ratio, floor, 1.0);
    }

    /** Autonomous bounty sizing: scales with treasury depth, always in bounds. */
    public static double suggestedAutoBounty(double baseAmount, double treasuryBalance,
                                             double minAuto, double maxAuto) {
        if (!Double.isFinite(baseAmount) || baseAmount <= 0.0) {
            baseAmount = minAuto;
        }
        double multiplier = treasuryBalance <= 0.0
                ? 0.0
                : Math.min(2.0, treasuryBalance / (baseAmount * 10.0));
        double scaled = baseAmount * multiplier;
        if (scaled < minAuto) {
            scaled = minAuto;
        }
        return Math.min(round2(scaled), maxAuto);
    }

    public static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static double sanitize(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    /** Two-decimal rounding keeps REAL-cent drift out of long-lived rows. */
    public static double round2(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
