package com.solidus.enforcer.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reverting a claim is only safe while NO money has left — reverting a
 * partially paid claim re-arms the bounties and re-pays the successful
 * recipients on the next kill (audit E-1).
 */
class PayoutVerdictTest {

    @Test
    void revertsOnlyWhenNothingWasPaid() {
        assertTrue(KillProcessor.PayoutVerdict.of(0, 2).revertAll(), "all payments failed -> clean revert");
        assertFalse(KillProcessor.PayoutVerdict.of(1, 1).revertAll(),
                "partial payment -> keep CLAIMED, manual reconciliation");
        assertFalse(KillProcessor.PayoutVerdict.of(3, 1).revertAll(),
                "partial payment -> keep CLAIMED, manual reconciliation");
        assertFalse(KillProcessor.PayoutVerdict.of(3, 0).revertAll(), "everything paid -> settle normally");
    }
}
