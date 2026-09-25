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
    // Atomic tax legs (ENF-01)
    // ------------------------------------------------------------------

    @Test
    void recordTaxLegsAppliesBothLegsInOneOperation() {
        TreasuryManager.TreasurySnapshot snapshot = this.storage.recordTaxLegs(50.0, 50.0, "tax test").join();

        assertEquals(50.0, snapshot.balance(), "treasury leg becomes spendable balance");
        assertEquals(50.0, snapshot.totalCollectedTax());
        assertEquals(50.0, snapshot.totalBurned(), "burn leg raises only the burned stat");
        assertEquals(0.0, snapshot.totalPaidBounties());
    }

    @Test
    void recordTaxLegsWithZeroAmountsChangesNothing() {
        this.storage.adjustTreasury(TreasuryManager.Category.FEE, 100.0, "seed").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.recordTaxLegs(0.0, 0.0, "no-op").join();

        assertEquals(100.0, snapshot.balance(), "a no-op tax split must not move anything");
        assertEquals(0.0, snapshot.totalBurned());
    }

    @Test
    void recordTaxLegsWithOnlyOneLegStillLandsWhole() {
        this.storage.recordTaxLegs(0.0, 25.0, "burn-only").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.loadTreasury().join();

        assertEquals(0.0, snapshot.balance(), "burn never becomes spendable balance");
        assertEquals(25.0, snapshot.totalBurned());
    }

    // ------------------------------------------------------------------
    // PENALTY category semantics (ENF-03)
    // ------------------------------------------------------------------

    @Test
    void penaltyCreditsBalanceWithoutTouchingTheTaxStat() {
        this.storage.adjustTreasury(TreasuryManager.Category.PENALTY, 85.5, "value-drop penalty test").join();
        TreasuryManager.TreasurySnapshot snapshot = this.storage.loadTreasury().join();

        assertEquals(85.5, snapshot.balance(), "the confiscated remainder becomes spendable");
        assertEquals(0.0, snapshot.totalCollectedTax(), "a penalty is not a tax");
        assertEquals(0.0, snapshot.totalBurned());
    }

    // ------------------------------------------------------------------
    // Settlement-pending lifecycle (ENF-05)
    // ------------------------------------------------------------------

    @Test
    void claimMarksSettlementPendingAndRevertClearsIt() throws Exception {
        UUID victim = UUID.randomUUID();
        int id = this.insertBountyFor(victim, 600.0, System.currentTimeMillis() + 86_400_000L);

        List<BountyEntry> claimed = this.storage.claimBountiesForTarget(victim).join();
        assertEquals(1, claimed.size());
        assertEquals(1, this.storage.findStuckSettlements().join().size(),
                "a claimed-but-unsettled bounty is a stuck settlement");

        this.storage.revertClaim(claimed.stream().map(BountyEntry::id).toList()).join();
        assertTrue(this.storage.findStuckSettlements().join().isEmpty(),
                "a reverted claim is back up for grabs — nothing stuck");
        assertEquals(BountyStatus.ACTIVE, this.readBountyStatus(id));
    }

    @Test
    void settledBountiesKeepClaimedStatusWithoutThePendingFlag() throws Exception {
        UUID victim = UUID.randomUUID();
        int id = this.insertBountyFor(victim, 600.0, System.currentTimeMillis() + 86_400_000L);

        List<BountyEntry> claimed = this.storage.claimBountiesForTarget(victim).join();
        this.storage.clearSettlementPending(claimed.stream().map(BountyEntry::id).toList()).join();

        assertEquals(BountyStatus.CLAIMED, this.readBountyStatus(id), "paid bounties stay CLAIMED");
        assertTrue(this.storage.findStuckSettlements().join().isEmpty(),
                "a fully settled bounty is not a stuck settlement");
    }

    @Test
    void confiscationClearsTheSettlementFlag() {
        UUID victim = UUID.randomUUID();
        this.insertBountyFor(victim, 400.0, System.currentTimeMillis() + 86_400_000L);

        List<BountyEntry> claimed = this.storage.claimBountiesForTarget(victim).join();
        assertNotNull(this.storage.confiscateBounties(
                claimed.stream().map(BountyEntry::id).toList(),
                EnumSet.of(BountyStatus.CLAIMED), 400.0, "collusion test").join());
        assertTrue(this.storage.findStuckSettlements().join().isEmpty(),
                "a confiscated claim reached a terminal state");
    }

    // ------------------------------------------------------------------
    // Autonomous bounty expiry (ENF-04)
    // ------------------------------------------------------------------

    @Test
    void autonomousBountiesExpireRefundPendingLikePlayerBounties() {
        UUID target = UUID.randomUUID();
        this.storage.insertBounty(BountyEntry.createAutonomous(target, "Target", 1_000.0,
                "Rampage: 12 kills", 1)).join();
        // Force the expiry into the past.
        try (Connection connection = this.openRaw();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE bounties SET expire_timestamp = "
                    + (System.currentTimeMillis() - 1_000L) + " WHERE target_name = 'Target'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<BountyEntry> expired = this.storage.expireOldBounties().join();
        assertEquals(1, expired.size(), "autonomous bounties must expire, not sit in escrow forever");
        assertTrue(expired.get(0).autonomous());

        BountyEntry pending = this.storage.claimNextRefundPending().join();
        assertNotNull(pending, "the expired autonomous bounty is refund-pending");
        assertNull(pending.placedByUuid(), "autonomous rows have no player placer");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int insertActiveBounty(double amount, long expiry) {
        return this.storage.insertBounty(new BountyEntry(0, UUID.randomUUID(), "Target", amount, amount,
                0.0, 0.0, UUID.randomUUID(), "Placer", System.currentTimeMillis(),
                expiry, BountyStatus.ACTIVE, false, null)).join();
    }

    private int insertBountyFor(UUID targetUuid, double amount, long expiry) {
        return this.storage.insertBounty(new BountyEntry(0, targetUuid, "Target", amount, amount,
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
