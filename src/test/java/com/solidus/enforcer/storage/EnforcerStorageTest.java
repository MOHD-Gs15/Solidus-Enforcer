package com.solidus.enforcer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnforcerStorageTest {

    private EnforcerStorage storage;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        this.dir = Files.createTempDirectory("solidus-enforcer-storage");
        this.storage = new EnforcerStorage(this.dir);
        this.storage.initialize().join();
    }

    @AfterEach
    void tearDown() {
        this.storage.shutdown();
    }

    @Test
    void recordsKillerAndVictimNamesAndReturnsGeneratedBountyId() throws Exception {
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        this.storage.recordKill(killer, "Killer", victim, "Victim").join();

        Path db = this.dir.resolve("solidus-enforcer").resolve("enforcer.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT player_uuid, player_name, kills, deaths FROM kill_stats ORDER BY player_name")) {
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
        int id = this.storage.insertBounty(bounty).join();
        assertTrue(id > 0);
    }

    // ------------------------------------------------------------------
    // Treasury category semantics (audit E-2)
    // ------------------------------------------------------------------

    @Test
    void burnRaisesBurnedStatWithoutMintingTreasuryBalance() {
        this.storage.adjustTreasury(TreasuryManager.Category.BURN, 100.0, "burn test").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.loadTreasury().join();
        assertEquals(0.0, snapshot.balance(), "burn must NOT become spendable balance");
        assertEquals(100.0, snapshot.totalBurned());
        assertEquals(0.0, snapshot.totalCollectedTax());
    }

    @Test
    void autoRefundRestoresBalanceAndRollsBackPaidStat() {
        this.storage.adjustTreasury(TreasuryManager.Category.FEE, 300.0, "fee test").join();
        assertNotNull(this.storage.tryFundAutonomousBounty(100.0, "fund test").join());
        assertEquals(200.0, this.storage.loadTreasury().join().balance());
        assertEquals(100.0, this.storage.loadTreasury().join().totalPaidBounties());

        this.storage.adjustTreasury(TreasuryManager.Category.AUTO_REFUND, 100.0, "rollback test").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.loadTreasury().join();
        assertEquals(300.0, snapshot.balance(), "refund restores the funding deduction");
        assertEquals(0.0, snapshot.totalPaidBounties(), "refund rolls the paid stat back");
    }

    @Test
    void taxAndConfiscationCreditBalanceAndCollectedStat() {
        this.storage.adjustTreasury(TreasuryManager.Category.TAX, 50.0, "tax test").join();
        this.storage.adjustTreasury(TreasuryManager.Category.CONFISCATION, 25.0, "conf test").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.loadTreasury().join();
        assertEquals(75.0, snapshot.balance());
        assertEquals(75.0, snapshot.totalCollectedTax());
    }

    // ------------------------------------------------------------------
    // Atomic confiscation / contract fee (audit E-4, E-6)
    // ------------------------------------------------------------------

    @Test
    void confiscationIsAtomicAndRefusesRaces() throws Exception {
        int id = this.insertActiveBounty(900.0, System.currentTimeMillis() + 86_400_000L);
        TreasuryManager.TreasurySnapshot snapshot = this.storage.confiscateBounties(
                List.of(id), EnumSet.of(BountyStatus.ACTIVE, BountyStatus.AUTONOMOUS), 900.0, "test cancel").join();
        assertNotNull(snapshot, "first cancel succeeds");
        assertEquals(900.0, snapshot.balance());

        assertEquals(BountyStatus.CANCELLED, this.readBountyStatus(id));

        // A concurrent second cancel of the same bounty must not double-credit.
        assertNull(this.storage.confiscateBounties(
                List.of(id), EnumSet.of(BountyStatus.ACTIVE, BountyStatus.AUTONOMOUS), 900.0, "test cancel2").join());
        assertEquals(900.0, this.storage.loadTreasury().join().balance(), "treasury must not be double-credited");
    }

    @Test
    void contractFeeDecaysBountyAndCreditsTreasuryTogether() throws Exception {
        int id = this.insertActiveBounty(900.0, System.currentTimeMillis() + 86_400_000L);
        TreasuryManager.TreasurySnapshot snapshot = this.storage.applyContractFee(id, 850.0, 50.0, "fee test").join();
        assertNotNull(snapshot);
        assertEquals(50.0, snapshot.balance(), "fee is credited to the treasury");

        try (Connection connection = this.openRaw();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT total_amount, contract_fees_deducted FROM bounties WHERE id = " + id)) {
            assertTrue(rows.next());
            assertEquals(850.0, rows.getDouble("total_amount"));
            assertEquals(50.0, rows.getDouble("contract_fees_deducted"));
        }

        // A claimed bounty must not be decayed by a racing fee cycle.
        try (Connection connection = this.openRaw();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE bounties SET status = 1 WHERE id = " + id);
        }
        assertNull(this.storage.applyContractFee(id, 800.0, 50.0, "fee race").join());
        assertEquals(50.0, this.storage.loadTreasury().join().balance(), "raced fee must change nothing");
    }

    // ------------------------------------------------------------------
    // Expiry refund recovery (audit E-7)
    // ------------------------------------------------------------------

    @Test
    void expiryRefundsAreClaimedExactlyOnce() {
        UUID placer = UUID.randomUUID();
        this.storage.insertBounty(new BountyEntry(0, UUID.randomUUID(), "Target", 700.0, 700.0,
                0.0, 0.0, placer, "Placer", System.currentTimeMillis() - 86_400_000L,
                System.currentTimeMillis() - 1000L, BountyStatus.ACTIVE, false, null)).join();

        List<BountyEntry> expired = this.storage.expireOldBounties().join();
        assertEquals(1, expired.size());

        BountyEntry claimed = this.storage.claimNextRefundPending().join();
        assertNotNull(claimed, "the freshly expired bounty is refund-pending");
        assertEquals(placer, claimed.placedByUuid());

        assertNull(this.storage.claimNextRefundPending().join(), "a claimed refund is never handed out twice");
    }

    // ------------------------------------------------------------------
    // License persistence reports real failures (audit E-3)
    // ------------------------------------------------------------------

    @Test
    void saveLicenseReportsSuccessAndRoundTrips() {
        UUID hunter = UUID.randomUUID();
        LicenseData license = new LicenseData(hunter, "Hunter", LicenseTier.GOLD,
                System.currentTimeMillis(), System.currentTimeMillis() + 86_400_000L, true);
        assertTrue(this.storage.saveLicense(license).join(), "successful write reports true");
        LicenseData loaded = this.storage.getLicense(hunter).join().orElseThrow();
        assertEquals(LicenseTier.GOLD, loaded.tier());
        assertEquals("Hunter", loaded.playerName());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int insertActiveBounty(double amount, long expiry) {
        return this.storage.insertBounty(new BountyEntry(0, UUID.randomUUID(), "Target", amount, amount,
                0.0, 0.0, UUID.randomUUID(), "Placer", System.currentTimeMillis(),
                expiry, BountyStatus.ACTIVE, false, null)).join();
    }

    private BountyStatus readBountyStatus(int id) throws Exception {
        try (Connection connection = this.openRaw();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT status FROM bounties WHERE id = " + id)) {
            assertTrue(rows.next());
            return BountyStatus.fromCode(rows.getInt("status"));
        }
    }

    private Connection openRaw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + this.storage.dbPath());
    }
}
