package com.solidus.enforcer.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.EconomyMath.FeeSplit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BountyLifecycleTest {

    @Test
    void createProducesSaneExpiry() {
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();
        BountyEntry bounty = BountyEntry.create(target, "Steve", 500.0, placer, "Alex", 30);

        assertEquals(target, bounty.targetUuid());
        assertEquals(500.0, bounty.totalAmount());
        assertEquals(BountyStatus.ACTIVE, bounty.status());
        assertFalse(bounty.autonomous());
        // 30 days ± a scheduling second
        long expected = bounty.placedTimestamp() + 30L * 24 * 60 * 60 * 1000;
        assertEquals(expected, bounty.expireTimestamp());
        assertFalse(bounty.isExpired());
        assertTrue(bounty.remainingTime() > 29L * 24 * 60 * 60 * 1000);
    }

    @Test
    void withTaxKeepsOriginalIntact() {
        BountyEntry bounty = BountyEntry.create(UUID.randomUUID(), "Steve", 900.0,
                UUID.randomUUID(), "Alex", 7).withTax(810.0, 90.0);

        assertEquals(900.0, bounty.originalAmount());
        assertEquals(810.0, bounty.totalAmount());
        assertEquals(90.0, bounty.bloodTaxDeducted());
    }

    @Test
    void contractFeeWitherAccumulates() {
        BountyEntry bounty = BountyEntry.create(UUID.randomUUID(), "Steve", 900.0,
                UUID.randomUUID(), "Alex", 7);
        BountyEntry decayed = bounty.withDeductedAmount(891.0, 9.0).withDeductedAmount(882.09, 8.91);

        // The wither chain must stay non-destructive: the placement-time amount survives.
        assertEquals(900.0, decayed.originalAmount());
        assertEquals(17.91, decayed.contractFeesDeducted(), 1e-9);
        assertEquals(882.09, decayed.totalAmount(), 1e-9);
    }

    @Test
    void withIdReplacesPlaceholder() {
        BountyEntry bounty = BountyEntry.create(UUID.randomUUID(), "Steve", 500.0,
                UUID.randomUUID(), "Alex", 7);
        assertEquals(0, bounty.id());
        assertEquals(42, bounty.withId(42).id());
    }

    @Test
    void reasonCategoryExtractsPrefix() {
        BountyEntry auto = BountyEntry.createAutonomous(UUID.randomUUID(), "Steve", 500.0,
                "Rampage: 12 kills", 7);
        assertEquals("Rampage", auto.reasonCategory());
        assertTrue(auto.autonomous());
        assertEquals(BountyStatus.AUTONOMOUS, auto.status());
        assertEquals("SERVER", auto.placedByName());

        BountyEntry noColon = BountyEntry.createAutonomous(UUID.randomUUID(), "Steve", 500.0,
                "Dangerous", 7);
        assertEquals("Dangerous", noColon.reasonCategory());
    }

    @Test
    void constructorSanitisesGarbage() {
        BountyEntry bounty = new BountyEntry(0, UUID.randomUUID(), null, Double.NaN,
                -3.0, Double.NaN, -1.0, null, null, 0L, 0L, BountyStatus.ACTIVE, true, null);
        assertEquals(0.0, bounty.totalAmount());
        assertEquals(0.0, bounty.originalAmount());
        assertEquals(0.0, bounty.bloodTaxDeducted());
        assertEquals(0.0, bounty.contractFeesDeducted());
        assertEquals("Unknown", bounty.targetName());
        assertEquals("Unknown", bounty.placedByName());
        assertNull(bounty.placedByUuid());
    }

    @Test
    void expiryForUsesAtLeastOneDay() {
        long now = System.currentTimeMillis();
        long expiry = BountyEntry.expiryFor(now, 0);
        assertTrue(expiry > now + 12L * 60 * 60 * 1000, "zero-day duration must clamp to 1 day");
    }

    @Test
    void contractFeeMathMatchesWither() {
        FeeSplit fee = EconomyMath.contractFee(900.0, 0.01, 100.0);
        BountyEntry bounty = BountyEntry.create(UUID.randomUUID(), "Steve", 900.0,
                UUID.randomUUID(), "Alex", 7).withDeductedAmount(fee.newAmount(), fee.fee());
        assertEquals(891.0, bounty.totalAmount(), 1e-9);
    }
}
