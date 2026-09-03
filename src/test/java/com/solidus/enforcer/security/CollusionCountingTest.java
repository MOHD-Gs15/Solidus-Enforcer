package com.solidus.enforcer.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solidus.enforcer.integration.SolidusBridge;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The money-loop collusion signal counts transaction ROWS — the union-of-sets
 * formulation that shipped before could only ever produce 0, 1 or 2 and the
 * documented signal never fired.
 */
class CollusionCountingTest {

    @Test
    void countsEachMatchingRowAsOneTransfer() {
        UUID counterparty = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long cutoff = now - 86_400_000L;

        List<SolidusBridge.TransactionEntryData> rows = List.of(
                new SolidusBridge.TransactionEntryData(now - 1000L, counterparty),
                new SolidusBridge.TransactionEntryData(now - 2000L, counterparty),
                new SolidusBridge.TransactionEntryData(now - 3000L, counterparty),
                new SolidusBridge.TransactionEntryData(now - 5000L, counterparty),
                new SolidusBridge.TransactionEntryData(now - 6000L, counterparty),
                new SolidusBridge.TransactionEntryData(now - 1000L, UUID.randomUUID()),
                new SolidusBridge.TransactionEntryData(now - 2000L, null));

        assertEquals(5, CollusionDetector.countMatchingTransactions(rows, counterparty, cutoff));
    }

    @Test
    void ignoresRowsOutsideTheLookbackWindow() {
        UUID counterparty = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long cutoff = now - 86_400_000L;

        List<SolidusBridge.TransactionEntryData> rows = List.of(
                new SolidusBridge.TransactionEntryData(now - 1000L, counterparty),
                new SolidusBridge.TransactionEntryData(cutoff - 1000L, counterparty),
                new SolidusBridge.TransactionEntryData(0L, counterparty));

        assertEquals(1, CollusionDetector.countMatchingTransactions(rows, counterparty, cutoff));
    }

    @Test
    void handlesDegenerateInputs() {
        UUID counterparty = UUID.randomUUID();
        assertEquals(0, CollusionDetector.countMatchingTransactions(null, counterparty, 0L));
        assertEquals(0, CollusionDetector.countMatchingTransactions(
                List.of(new SolidusBridge.TransactionEntryData(1L, counterparty)), null, 0L));
        assertEquals(0, CollusionDetector.countMatchingTransactions(List.of(), counterparty, 0L));
    }
}
