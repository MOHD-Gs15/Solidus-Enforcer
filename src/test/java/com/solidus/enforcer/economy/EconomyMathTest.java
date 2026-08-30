package com.solidus.enforcer.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.economy.EconomyMath.FeeSplit;
import com.solidus.enforcer.economy.EconomyMath.TaxSplit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EconomyMathTest {

    // ------------------------------------------------------------------
    // Blood tax
    // ------------------------------------------------------------------

    @Test
    void bloodTaxSplitsTreasuryAndBurn() {
        TaxSplit split = EconomyMath.bloodTax(1_000.0, 0.10, 0.50, 0.50);
        assertEquals(100.0, split.totalTax(), 1e-9);
        assertEquals(50.0, split.treasuryAmount(), 1e-9);
        assertEquals(50.0, split.burnAmount(), 1e-9);
        assertEquals(900.0, split.remainingBounty(), 1e-9);
    }

    @Test
    void bloodTaxNormalisesOversizedShares() {
        // 80% + 80% shares must normalise to 50/50 of the taxed amount.
        TaxSplit split = EconomyMath.bloodTax(1_000.0, 0.10, 0.80, 0.80);
        assertEquals(100.0, split.totalTax(), 1e-9);
        assertEquals(50.0, split.treasuryAmount(), 1e-9);
        assertEquals(50.0, split.burnAmount(), 1e-9);
    }

    @Test
    void bloodTaxNeverProducesNegativeBounty() {
        // A 100% tax rate would eat the whole bounty; the split must refuse.
        TaxSplit split = EconomyMath.bloodTax(1_000.0, 1.0, 0.5, 0.5);
        assertEquals(0.0, split.totalTax(), 1e-9);
        assertEquals(1_000.0, split.remainingBounty(), 1e-9);
    }

    @Test
    void bloodTaxIgnoresGarbageInput() {
        TaxSplit nan = EconomyMath.bloodTax(Double.NaN, 0.1, 0.5, 0.5);
        assertEquals(0.0, nan.totalTax());
        TaxSplit negative = EconomyMath.bloodTax(-5.0, 0.1, 0.5, 0.5);
        assertEquals(-5.0, negative.remainingBounty());
    }

    // ------------------------------------------------------------------
    // Contract fees
    // ------------------------------------------------------------------

    @Test
    void contractFeeDecaysButRespectsFloor() {
        FeeSplit healthy = EconomyMath.contractFee(10_000.0, 0.01, 100.0);
        assertEquals(100.0, healthy.fee(), 1e-9);
        assertEquals(9_900.0, healthy.newAmount(), 1e-9);

        FeeSplit floored = EconomyMath.contractFee(101.0, 0.50, 100.0);
        assertEquals(1.0, floored.fee(), 1e-9);
        assertEquals(100.0, floored.newAmount(), 1e-9);
    }

    @Test
    void contractFeeSkipsAtOrBelowMinimum() {
        FeeSplit atFloor = EconomyMath.contractFee(100.0, 0.01, 100.0);
        assertEquals(0.0, atFloor.fee());
        assertEquals(100.0, atFloor.newAmount());
    }

    // ------------------------------------------------------------------
    // Alliance split
    // ------------------------------------------------------------------

    @Test
    void allianceSplitSumsToReward() {
        EconomyMath.PayoutSplit split = EconomyMath.allianceSplit(1_000.0, 0.70, 0.30);
        assertEquals(700.0, split.damagePool(), 1e-9);
        assertEquals(300.0, split.finishingPool(), 1e-9);
    }

    @Test
    void allianceSplitNormalisesOversizedShares() {
        EconomyMath.PayoutSplit split = EconomyMath.allianceSplit(1_000.0, 1.0, 1.0);
        assertEquals(500.0, split.damagePool(), 1e-9);
        assertEquals(500.0, split.finishingPool(), 1e-9);
    }

    @Test
    void allianceSplitFallsBackToFinishingWhenDisabled() {
        EconomyMath.PayoutSplit split = EconomyMath.allianceSplit(1_000.0, 0.0, 0.0);
        assertEquals(0.0, split.damagePool(), 1e-9);
        assertEquals(1_000.0, split.finishingPool(), 1e-9);
    }

    @Test
    void damageSharesSumToPool() {
        Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(UUID.randomUUID(), 300.0);
        damage.put(UUID.randomUUID(), 100.0);
        double pool = 700.0;

        LinkedHashMap<UUID, Double> shares = EconomyMath.damageShares(damage, pool);
        double sum = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(pool, sum, 0.02);
        assertEquals(525.0, shares.values().stream().mapToDouble(Double::doubleValue).max().orElse(0), 0.02);
    }

    @Test
    void damageSharesIgnoreInvalidDamage() {
        Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(UUID.randomUUID(), Double.NaN);
        damage.put(UUID.randomUUID(), -5.0);
        damage.put(UUID.randomUUID(), 50.0);

        LinkedHashMap<UUID, Double> shares = EconomyMath.damageShares(damage, 100.0);
        assertEquals(1, shares.size());
        assertEquals(100.0, shares.values().stream().mapToDouble(Double::doubleValue).sum(), 0.01);
    }

    // ------------------------------------------------------------------
    // Value-drop payout
    // ------------------------------------------------------------------

    @Test
    void payoutRatioFullWhenGearSufficient() {
        double ratio = EconomyMath.payoutRatio(1_000.0, 250.0, 0.20, 0.05);
        assertEquals(1.0, ratio, 1e-9);
    }

    @Test
    void payoutRatioScalesWithGear() {
        // required = 1000 * 0.20 = 200; carried 100 -> ratio 0.5
        double ratio = EconomyMath.payoutRatio(1_000.0, 100.0, 0.20, 0.05);
        assertEquals(0.5, ratio, 1e-9);
    }

    @Test
    void payoutRatioFloorsAtNakedPenalty() {
        double ratio = EconomyMath.payoutRatio(1_000.0, 1.0, 0.20, 0.05);
        assertEquals(0.05, ratio, 1e-9);
    }

    // ------------------------------------------------------------------
    // Autonomous sizing
    // ------------------------------------------------------------------

    @Test
    void autoBountyStaysInBounds() {
        double poor = EconomyMath.suggestedAutoBounty(5_000.0, 0.0, 1_000.0, 50_000.0);
        assertEquals(1_000.0, poor, 1e-9);

        double rich = EconomyMath.suggestedAutoBounty(5_000.0, 1_000_000.0, 1_000.0, 50_000.0);
        assertTrue(rich <= 50_000.0);
        assertTrue(rich >= 1_000.0);
    }

    // ------------------------------------------------------------------
    // Rounding
    // ------------------------------------------------------------------

    @Test
    void round2KillsFloatingDrift() {
        assertEquals(0.30, EconomyMath.round2(0.1 + 0.2), 1e-12);
        assertEquals(0.0, EconomyMath.round2(Double.NaN), 1e-12);
        assertEquals(0.0, EconomyMath.round2(Double.POSITIVE_INFINITY), 1e-12);
    }
}
