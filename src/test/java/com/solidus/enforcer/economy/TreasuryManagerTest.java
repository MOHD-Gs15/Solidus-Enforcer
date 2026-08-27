package com.solidus.enforcer.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.util.ConfigManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TreasuryManagerTest {
    @Test
    void taxCalculationDoesNotMutateBeforeCommit() throws Exception {
        Path dir = Files.createTempDirectory("solidus-enforcer-config");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        TreasuryManager treasury = new TreasuryManager(config);

        TreasuryManager.TaxResult result = treasury.processBloodTax(1_000.0);
        assertEquals(0.0, treasury.getBalance());
        assertEquals(0.0, treasury.getTotalCollectedTax());
        assertEquals(0.0, treasury.getTotalBurned());

        treasury.applyTax(result);
        assertEquals(50.0, treasury.getBalance(), 0.0001);
        assertEquals(100.0, treasury.getTotalCollectedTax(), 0.0001);
        assertEquals(50.0, treasury.getTotalBurned(), 0.0001);
    }

    @Test
    void rejectsNonFiniteAutonomousFunding() throws Exception {
        Path dir = Files.createTempDirectory("solidus-enforcer-config");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        TreasuryManager treasury = new TreasuryManager(config);
        treasury.loadFromStorage(10_000.0, 0.0, 0.0, 0.0);

        assertFalse(treasury.fundAutonomousBounty(Double.NaN));
        assertFalse(treasury.fundAutonomousBounty(Double.POSITIVE_INFINITY));
        assertTrue(treasury.fundAutonomousBounty(100.0));
        assertEquals(9_900.0, treasury.getBalance(), 0.0001);
    }
}
