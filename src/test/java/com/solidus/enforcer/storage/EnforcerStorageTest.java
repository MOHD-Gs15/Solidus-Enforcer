package com.solidus.enforcer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnforcerStorageTest {
    @Test
    void recordsKillerAndVictimNamesAndReturnsGeneratedBountyId() throws Exception {
        Path dir = Files.createTempDirectory("solidus-enforcer-storage");
        EnforcerStorage storage = new EnforcerStorage(dir);
        storage.initialize().join();

        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        storage.recordKill(killer, "Killer", victim, "Victim").join();

        Path db = dir.resolve("solidus-enforcer").resolve("enforcer.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT player_uuid, player_name, kills, deaths FROM kill_stats ORDER BY player_name")) {
            assertTrue(rows.next());
            assertEquals("Killer", rows.getString("player_name"));
            assertEquals(1, rows.getInt("kills"));
            assertTrue(rows.next());
            assertEquals("Victim", rows.getString("player_name"));
            assertEquals(1, rows.getInt("deaths"));
        }

        BountyEntry bounty = new BountyEntry(
            0, victim, "Victim", 900.0, 900.0, 100.0, 0.0,
            killer, "Killer", System.currentTimeMillis(),
            System.currentTimeMillis() + 86_400_000L, BountyStatus.ACTIVE, false, null);
        int id = storage.insertBounty(bounty).join();
        assertTrue(id > 0);
        storage.shutdown();
    }
}
