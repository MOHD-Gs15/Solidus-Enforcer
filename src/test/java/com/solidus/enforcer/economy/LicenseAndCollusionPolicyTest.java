package com.solidus.enforcer.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.license.HunterLicenseManager;
import com.solidus.enforcer.security.CollusionDetector.Decision;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicenseAndCollusionPolicyTest {

    // ------------------------------------------------------------------
    // Strict tier parsing (the old build silently sold BRONZE on typos)
    // ------------------------------------------------------------------

    @Test
    void parseAcceptsCanonicalInput() {
        assertEquals(LicenseTier.BRONZE, LicenseTier.parse("bronze"));
        assertEquals(LicenseTier.SILVER, LicenseTier.parse("  SILVER "));
        assertEquals(LicenseTier.GOLD, LicenseTier.parse("Gold"));
    }

    @Test
    void parseRejectsUnknownTiersInsteadOfDefaulting() {
        assertNull(LicenseTier.parse("gd"));
        assertNull(LicenseTier.parse("diamond"));
        assertNull(LicenseTier.parse(""));
        assertNull(LicenseTier.parse(null));
    }

    @Test
    void perkLadderMatchesContract() {
        assertFalse(LicenseTier.BRONZE.canTrack());
        assertTrue(LicenseTier.SILVER.canTrack());
        assertTrue(LicenseTier.GOLD.canTrack());

        assertFalse(LicenseTier.BRONZE.canSeeKd());
        assertTrue(LicenseTier.SILVER.canSeeKd());
        assertTrue(LicenseTier.GOLD.canSeeKd());

        assertFalse(LicenseTier.SILVER.canSeeWealth());
        assertTrue(LicenseTier.GOLD.canSeeWealth());
    }

    @Test
    void accuracyBlocksFollowTier() {
        assertEquals(0, LicenseTier.BRONZE.accuracyBlocks(50, 5));
        assertEquals(50, LicenseTier.SILVER.accuracyBlocks(50, 5));
        assertEquals(5, LicenseTier.GOLD.accuracyBlocks(50, 5));
    }

    // ------------------------------------------------------------------
    // License validity
    // ------------------------------------------------------------------

    @Test
    void licenseValidityHonoursExpiryAndFlag() {
        long now = System.currentTimeMillis();
        LicenseData active = new LicenseData(UUID.randomUUID(), "Alex", LicenseTier.GOLD,
                now - 1000, now + 86_400_000L, true);
        assertTrue(active.isValid());
        assertTrue(active.remainingMillis() > 0);

        LicenseData expired = new LicenseData(UUID.randomUUID(), "Alex", LicenseTier.GOLD,
                now - 86_400_000L * 9, now - 1000, true);
        assertFalse(expired.isValid());

        LicenseData deactivated = new LicenseData(UUID.randomUUID(), "Alex", LicenseTier.GOLD,
                now, now + 86_400_000L, false);
        assertFalse(deactivated.isValid());
    }

    // ------------------------------------------------------------------
    // Collusion decision policy
    // ------------------------------------------------------------------

    @Test
    void cleanBehaviourIsNotFlagged() {
        Decision.Policy policy = new Decision(1, 0, 2).evaluate(3, 2, 5);
        assertFalse(policy.flagged());
    }

    @Test
    void repeatedPairKillsFlagFarming() {
        Decision.Policy policy = new Decision(5, 0, 0).evaluate(3, 2, 5);
        assertTrue(policy.flagged());
        assertTrue(policy.reason().contains("kill farming"));
    }

    @Test
    void mutualSwapsFlagKillTrading() {
        Decision.Policy policy = new Decision(1, 2, 0).evaluate(3, 2, 5);
        assertTrue(policy.flagged());
        assertTrue(policy.reason().contains("swapping"));
    }

    @Test
    void moneyLoopsFlagBountyBankrolling() {
        Decision.Policy policy = new Decision(1, 1, 7).evaluate(3, 2, 5);
        assertTrue(policy.flagged());
        assertTrue(policy.reason().contains("transfers"));
    }

    // ------------------------------------------------------------------
    // Renewal expiry math (a renewal extends, never restarts)
    // ------------------------------------------------------------------

    @Test
    void renewalExtendsFromTheCurrentExpiry() {
        long now = System.currentTimeMillis();
        long sevenDays = 7L * 86_400_000L;
        long twoDaysLeft = now + 2L * 86_400_000L;

        assertEquals(twoDaysLeft + sevenDays,
                HunterLicenseManager.computeExpiry(now, twoDaysLeft, sevenDays));
        assertEquals(now + sevenDays, HunterLicenseManager.computeExpiry(now, null, sevenDays));
        assertEquals(now + sevenDays,
                HunterLicenseManager.computeExpiry(now, now - 1000L, sevenDays),
                "a lapsed license restarts from now");
    }
}
