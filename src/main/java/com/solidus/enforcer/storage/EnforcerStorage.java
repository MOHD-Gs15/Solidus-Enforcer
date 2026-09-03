package com.solidus.enforcer.storage;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.economy.TreasuryManager;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async SQLite storage (WAL) behind a single-thread worker.
 *
 * Concurrency model: because every task runs on one worker, a method that
 * SELECTs and UPDATEs inside a single task is atomic with respect to all other
 * storage operations — this is what makes {@link #claimBountiesForTarget}
 * race-free without database-level locking (same pattern as Solidus Core).
 */
public final class EnforcerStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("Solidus-Enforcer");

    private final Path dbPath;
    private final ExecutorService executor;
    private volatile Connection connection;
    private volatile boolean shutdown;

    public EnforcerStorage(Path configDir) {
        this.dbPath = configDir.resolve("solidus-enforcer").resolve("enforcer.db");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Enforcer-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Void> initialize() {
        return this.run(() -> {
            try {
                Files.createDirectories(this.dbPath.getParent());
                this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
                this.connection.setAutoCommit(true);
                try (Statement stmt = this.connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA temp_store=MEMORY");
                    stmt.execute("PRAGMA cache_size=2048");
                }
                this.createTables();
                this.tightenDbFilePermissions();
                LOGGER.info("Enforcer database initialized at {}", this.dbPath);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize Enforcer database", e);
                throw new RuntimeException(e);
            }
        });
    }

    /** Path of the SQLite file (test/diagnostic access). */
    public Path dbPath() {
        return this.dbPath;
    }

    public void shutdown() {
        this.shutdown = true;
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
                LOGGER.warn("Enforcer worker forced to stop after shutdown timeout");
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (this.connection != null && !this.connection.isClosed()) {
                this.connection.close();
            }
            LOGGER.info("Enforcer database shut down");
        } catch (SQLException e) {
            LOGGER.error("Error closing Enforcer database", e);
        }
    }

    /** The DB carries player economy intel — family policy is 0600. */
    private void tightenDbFilePermissions() {
        try {
            java.nio.file.attribute.PosixFileAttributeView view =
                    Files.getFileAttributeView(this.dbPath, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception e) {
            LOGGER.debug("Could not tighten enforcer.db permissions: {}", e.toString());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = this.connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS bounties (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        total_amount REAL NOT NULL,
                        original_amount REAL NOT NULL,
                        blood_tax_deducted REAL DEFAULT 0,
                        contract_fees_deducted REAL DEFAULT 0,
                        placed_by_uuid TEXT,
                        placed_by_name TEXT,
                        placed_timestamp INTEGER NOT NULL,
                        expire_timestamp INTEGER NOT NULL,
                        status INTEGER DEFAULT 0,
                        autonomous INTEGER DEFAULT 0,
                        autonomous_reason TEXT
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_target ON bounties (target_uuid, status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_placer ON bounties (placed_by_uuid, status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_expire ON bounties (expire_timestamp, status)");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hunter_licenses (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        tier TEXT NOT NULL DEFAULT 'BRONZE',
                        purchase_timestamp INTEGER NOT NULL,
                        expire_timestamp INTEGER NOT NULL,
                        active INTEGER DEFAULT 1
                    )""");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS damage_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_uuid TEXT NOT NULL,
                        attacker_uuid TEXT NOT NULL,
                        attacker_name TEXT NOT NULL DEFAULT 'Unknown',
                        damage REAL NOT NULL,
                        timestamp INTEGER NOT NULL
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_damage_target ON damage_records (target_uuid, timestamp DESC)");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS treasury (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        balance REAL DEFAULT 0,
                        total_collected_tax REAL DEFAULT 0,
                        total_burned REAL DEFAULT 0,
                        total_paid_bounties REAL DEFAULT 0
                    )""");
            stmt.execute("INSERT OR IGNORE INTO treasury (id, balance, total_collected_tax, total_burned, total_paid_bounties) VALUES (1, 0, 0, 0, 0)");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS treasury_ledger (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        category TEXT NOT NULL,
                        amount REAL NOT NULL,
                        note TEXT,
                        timestamp INTEGER NOT NULL
                    )""");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kill_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        killer_uuid TEXT NOT NULL,
                        victim_uuid TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_kill_pair ON kill_events (killer_uuid, victim_uuid, timestamp)");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS collusion_flags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player1_uuid TEXT NOT NULL,
                        player1_name TEXT NOT NULL,
                        player2_uuid TEXT NOT NULL,
                        player2_name TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )""");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kill_stats (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        kills INTEGER DEFAULT 0,
                        deaths INTEGER DEFAULT 0,
                        current_streak INTEGER DEFAULT 0,
                        last_kill_timestamp INTEGER DEFAULT 0,
                        last_death_timestamp INTEGER DEFAULT 0
                    )""");

            // damage_records gained attacker_name in v1.1; older databases lack it.
            this.addColumnIfMissing(stmt, "damage_records", "attacker_name", "TEXT NOT NULL DEFAULT 'Unknown'");
            // 2.1.1: expiry refund recovery — rows are claimed before their refund is
            // attempted so a crash can never strand a refund forever (E-7).
            this.addColumnIfMissing(stmt, "bounties", "refund_pending", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void addColumnIfMissing(Statement stmt, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        LOGGER.info("Migrating {}: adding column {}", table, column);
        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private <T> CompletableFuture<T> supply(java.util.function.Supplier<T> task) {
        if (this.shutdown) {
            return CompletableFuture.failedFuture(new IllegalStateException("Enforcer storage is shut down"));
        }
        try {
            return CompletableFuture.supplyAsync(task, this.executor);
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    /** Guarded async runnable — never throws RejectedExecutionException into callers. */
    private CompletableFuture<Void> run(Runnable task) {
        if (this.shutdown) {
            return CompletableFuture.failedFuture(new IllegalStateException("Enforcer storage is shut down"));
        }
        try {
            return CompletableFuture.runAsync(task, this.executor);
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    // ------------------------------------------------------------------
    // Bounties
    // ------------------------------------------------------------------

    public CompletableFuture<Integer> insertBounty(BountyEntry bounty) {
        return this.supply(() -> {
            String sql = """
                    INSERT INTO bounties (target_uuid, target_name, total_amount, original_amount,
                        blood_tax_deducted, contract_fees_deducted, placed_by_uuid, placed_by_name,
                        placed_timestamp, expire_timestamp, status, autonomous, autonomous_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = this.connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, bounty.targetUuid().toString());
                ps.setString(2, bounty.targetName());
                ps.setDouble(3, bounty.totalAmount());
                ps.setDouble(4, bounty.originalAmount());
                ps.setDouble(5, bounty.bloodTaxDeducted());
                ps.setDouble(6, bounty.contractFeesDeducted());
                ps.setString(7, bounty.placedByUuid() != null ? bounty.placedByUuid().toString() : null);
                ps.setString(8, bounty.placedByName());
                ps.setLong(9, bounty.placedTimestamp());
                ps.setLong(10, bounty.expireTimestamp());
                ps.setInt(11, bounty.status().getCode());
                ps.setInt(12, bounty.autonomous() ? 1 : 0);
                ps.setString(13, bounty.autonomousReason());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to insert bounty", e);
                return -1;
            }
        });
    }

    private static final String ACTIVE_FILTER = "status IN (0, 4)";

    public CompletableFuture<List<BountyEntry>> getActiveBounties() {
        return this.supply(() -> this.queryBounties(
                "SELECT * FROM bounties WHERE " + ACTIVE_FILTER + " ORDER BY total_amount DESC"));
    }

    public CompletableFuture<List<BountyEntry>> getBountiesForTarget(UUID targetUuid) {
        return this.supply(() -> this.queryBounties(
                "SELECT * FROM bounties WHERE target_uuid = ? AND " + ACTIVE_FILTER + " ORDER BY total_amount DESC",
                targetUuid.toString()));
    }

    public CompletableFuture<Double> getTotalBountyForTarget(UUID targetUuid) {
        return this.supply(() -> {
            String sql = "SELECT COALESCE(SUM(total_amount), 0) AS total FROM bounties WHERE target_uuid = ? AND " + ACTIVE_FILTER;
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble("total") : 0.0;
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to sum bounties for target", e);
                return 0.0;
            }
        });
    }

    public CompletableFuture<Integer> getActiveBountyCountByPlacer(UUID placerUuid) {
        return this.supply(() -> this.count(
                "SELECT COUNT(*) AS cnt FROM bounties WHERE placed_by_uuid = ? AND " + ACTIVE_FILTER,
                placerUuid.toString()));
    }

    public CompletableFuture<Integer> getActiveBountyCountForTarget(UUID targetUuid) {
        return this.supply(() -> this.count(
                "SELECT COUNT(*) AS cnt FROM bounties WHERE target_uuid = ? AND " + ACTIVE_FILTER,
                targetUuid.toString()));
    }

    public CompletableFuture<List<BountyEntry>> getActiveBountiesByPlacer(UUID placerUuid) {
        return this.supply(() -> this.queryBounties(
                "SELECT * FROM bounties WHERE placed_by_uuid = ? AND " + ACTIVE_FILTER + " ORDER BY total_amount DESC",
                placerUuid.toString()));
    }

    /**
     * Atomic claim of every payable bounty on a target.
     *
     * Runs as ONE worker task (SELECT + UPDATE back-to-back on the single
     * storage thread), so two simultaneous kills can never both observe the
     * bounties as ACTIVE — the first claim wins, the second sees an empty set.
     */
    public CompletableFuture<List<BountyEntry>> claimBountiesForTarget(UUID targetUuid) {
        return this.supply(() -> {
            List<BountyEntry> claimed;
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "SELECT * FROM bounties WHERE target_uuid = ? AND " + ACTIVE_FILTER)) {
                ps.setString(1, targetUuid.toString());
                claimed = this.mapAll(ps);
            } catch (SQLException e) {
                LOGGER.error("Failed to read bounties for claim", e);
                return List.of();
            }
            if (claimed.isEmpty()) {
                return List.of();
            }
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE bounties SET status = ? WHERE target_uuid = ? AND " + ACTIVE_FILTER)) {
                ps.setInt(1, BountyStatus.CLAIMED.getCode());
                ps.setString(2, targetUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to claim bounties", e);
                return List.of();
            }
            return claimed;
        });
    }

    /** Compensation path: puts an already-claimed set back up for grabs. */
    public CompletableFuture<Void> revertClaim(List<Integer> bountyIds) {
        return this.run(() -> {
            if (bountyIds == null || bountyIds.isEmpty()) {
                return;
            }
            StringBuilder sql = new StringBuilder("UPDATE bounties SET status = ? WHERE id IN (");
            for (int i = 0; i < bountyIds.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(") AND status = ?");
            try (PreparedStatement ps = this.connection.prepareStatement(sql.toString())) {
                ps.setInt(1, BountyStatus.ACTIVE.getCode());
                for (int i = 0; i < bountyIds.size(); i++) {
                    ps.setInt(2 + i, bountyIds.get(i));
                }
                ps.setInt(2 + bountyIds.size(), BountyStatus.CLAIMED.getCode());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to revert bounty claims {}", bountyIds, e);
            }
        });
    }

    public CompletableFuture<Void> updateBountyStatus(int bountyId, BountyStatus status) {
        return this.run(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE bounties SET status = ? WHERE id = ?")) {
                ps.setInt(1, status.getCode());
                ps.setInt(2, bountyId);
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to update bounty status", e);
            }
        });
    }

    /**
     * Atomic confiscation: transitions the given bounties to CANCELLED and
     * credits {@code amount} to the treasury in ONE database transaction. Every
     * row must still be in one of {@code expected} (compare-and-set) — a row
     * that raced with a claim or another cancel aborts the whole operation with
     * nothing changed. Returns the fresh treasury snapshot, or {@code null} on
     * failure/race (callers report and skip).
     */
    public CompletableFuture<TreasuryManager.TreasurySnapshot> confiscateBounties(
            List<Integer> bountyIds, java.util.EnumSet<BountyStatus> expected, double amount, String note) {
        return this.supply(() -> {
            if (bountyIds == null || bountyIds.isEmpty()
                    || expected == null || expected.isEmpty()
                    || !Double.isFinite(amount) || amount <= 0.0) {
                return null;
            }
            String codes = expected.stream().map(s -> String.valueOf(s.getCode()))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            try {
                this.connection.setAutoCommit(false);
                try {
                    int changed = 0;
                    try (PreparedStatement ps = this.connection.prepareStatement(
                            "UPDATE bounties SET status = ? WHERE id = ? AND status IN (" + codes + ")")) {
                        ps.setInt(1, BountyStatus.CANCELLED.getCode());
                        for (int id : bountyIds) {
                            ps.setInt(2, id);
                            changed += ps.executeUpdate();
                        }
                    }
                    if (changed != bountyIds.size()) {
                        this.connection.rollback();
                        return null;
                    }
                    this.insertLedgerRow(TreasuryManager.Category.CONFISCATION, amount, note);
                    this.applyTreasuryRow(TreasuryManager.Category.CONFISCATION, amount);
                    this.connection.commit();
                    return this.readTreasury();
                } finally {
                    this.connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed confiscation of bounties {} — rolled back", bountyIds, e);
                this.quietRollback();
                return null;
            }
        });
    }

    /**
     * CAS-style cancel used by placement rollback: only cancels the bounty while
     * it is still in a live status — a kill that claimed it in the micro-window
     * keeps its claim (the caller then logs CRITICAL and refunds the placer).
     */
    public CompletableFuture<Boolean> cancelIfActive(int bountyId) {
        return this.supply(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE bounties SET status = ? WHERE id = ? AND status IN (0, 4)")) {
                ps.setInt(1, BountyStatus.CANCELLED.getCode());
                ps.setInt(2, bountyId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                LOGGER.error("Failed to cancel bounty #{}", bountyId, e);
                return false;
            }
        });
    }

    /**
     * Atomic contract-fee decay: the bounty row, the treasury ledger row and the
     * treasury balance move in ONE database transaction, so a failure can never
     * leave the bounty decayed without the treasury being credited (or the
     * reverse). Returns the fresh treasury snapshot, or {@code null} when the
     * bounty is no longer in a live status or the transaction failed.
     */
    public CompletableFuture<TreasuryManager.TreasurySnapshot> applyContractFee(
            int bountyId, double newAmount, double fee, String note) {
        return this.supply(() -> {
            if (!Double.isFinite(newAmount) || newAmount < 0.0 || !Double.isFinite(fee) || fee <= 0.0) {
                return null;
            }
            try {
                this.connection.setAutoCommit(false);
                try {
                    int changed;
                    try (PreparedStatement ps = this.connection.prepareStatement(
                            "UPDATE bounties SET total_amount = ?, contract_fees_deducted = contract_fees_deducted + ? WHERE id = ? AND status IN (0, 4)")) {
                        ps.setDouble(1, newAmount);
                        ps.setDouble(2, fee);
                        ps.setInt(3, bountyId);
                        changed = ps.executeUpdate();
                    }
                    if (changed == 0) {
                        this.connection.rollback();
                        return null;
                    }
                    this.insertLedgerRow(TreasuryManager.Category.FEE, fee, note);
                    this.applyTreasuryRow(TreasuryManager.Category.FEE, fee);
                    this.connection.commit();
                    return this.readTreasury();
                } finally {
                    this.connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed contract fee for bounty #{} — rolled back", bountyId, e);
                this.quietRollback();
                return null;
            }
        });
    }

    /**
     * Expires overdue bounties (marking them refund-pending in the same task) and
     * returns them so the caller can refund placers. The pending flag lets
     * {@link #claimNextRefundPending()} retry refunds a crash may have stranded.
     */
    public CompletableFuture<List<BountyEntry>> expireOldBounties() {
        return this.supply(() -> {
            List<BountyEntry> toExpire = new ArrayList<>();
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "SELECT * FROM bounties WHERE " + ACTIVE_FILTER + " AND expire_timestamp < ? AND autonomous = 0")) {
                ps.setLong(1, System.currentTimeMillis());
                toExpire = this.mapAll(ps);
            } catch (SQLException e) {
                LOGGER.error("Failed to read expiring bounties", e);
            }
            for (BountyEntry bounty : toExpire) {
                try (PreparedStatement ps = this.connection.prepareStatement(
                        "UPDATE bounties SET status = ?, refund_pending = 1 WHERE id = ? AND " + ACTIVE_FILTER)) {
                    ps.setInt(1, BountyStatus.EXPIRED.getCode());
                    ps.setInt(2, bounty.id());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    LOGGER.error("Failed to expire bounty #{}", bounty.id(), e);
                }
            }
            return toExpire;
        });
    }

    /**
     * Claims ONE refund-pending expired bounty for a refund attempt. The claim
     * clears the pending flag in the same task — money-out operations follow the
     * prefer-loss-over-double-pay rule, so the flag is cleared BEFORE the payout
     * is attempted. Returns {@code null} when nothing is pending.
     */
    public CompletableFuture<BountyEntry> claimNextRefundPending() {
        return this.supply(() -> {
            BountyEntry bounty = null;
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "SELECT * FROM bounties WHERE status = ? AND refund_pending = 1 ORDER BY expire_timestamp LIMIT 1")) {
                ps.setInt(1, BountyStatus.EXPIRED.getCode());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bounty = this.mapBounty(rs);
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read refund-pending bounties", e);
                return null;
            }
            if (bounty == null) {
                return null;
            }
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE bounties SET refund_pending = 0 WHERE id = ?")) {
                ps.setInt(1, bounty.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to claim refund for bounty #{}", bounty.id(), e);
                return null;
            }
            return bounty;
        });
    }

    private List<BountyEntry> queryBounties(String sql, String... params) {
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            return this.mapAll(ps);
        } catch (SQLException e) {
            LOGGER.error("Failed bounty query", e);
            return List.of();
        }
    }

    private List<BountyEntry> mapAll(PreparedStatement ps) throws SQLException {
        List<BountyEntry> entries = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                entries.add(this.mapBounty(rs));
            }
        }
        return entries;
    }

    private BountyEntry mapBounty(ResultSet rs) throws SQLException {
        return new BountyEntry(
                rs.getInt("id"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getDouble("total_amount"),
                rs.getDouble("original_amount"),
                rs.getDouble("blood_tax_deducted"),
                rs.getDouble("contract_fees_deducted"),
                rs.getString("placed_by_uuid") != null ? UUID.fromString(rs.getString("placed_by_uuid")) : null,
                rs.getString("placed_by_name"),
                rs.getLong("placed_timestamp"),
                rs.getLong("expire_timestamp"),
                BountyStatus.fromCode(rs.getInt("status")),
                rs.getInt("autonomous") == 1,
                rs.getString("autonomous_reason"));
    }

    private int count(String sql, String param) {
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("cnt") : 0;
            }
        } catch (SQLException e) {
            LOGGER.error("Failed count query", e);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Treasury (single source of truth; every change is ledgered)
    // ------------------------------------------------------------------

    public CompletableFuture<TreasuryManager.TreasurySnapshot> loadTreasury() {
        return this.supply(() -> this.readTreasury());
    }

    private TreasuryManager.TreasurySnapshot readTreasury() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT balance, total_collected_tax, total_burned, total_paid_bounties FROM treasury WHERE id = 1")) {
            if (rs.next()) {
                return new TreasuryManager.TreasurySnapshot(
                        rs.getDouble("balance"),
                        rs.getDouble("total_collected_tax"),
                        rs.getDouble("total_burned"),
                        rs.getDouble("total_paid_bounties"));
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to read treasury", e);
        }
        return new TreasuryManager.TreasurySnapshot(0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Applies a treasury movement inside ONE database transaction (ledger row +
     * treasury row together, so they can never drift apart on a crash):
     *
     * <ul>
     *   <li>TAX / FEE / CONFISCATION — balance UP, collected-tax stat up;</li>
     *   <li>BURN — {@code total_burned} stat only, balance UNCHANGED: the burn
     *       share left the economy at placement time and must never become
     *       spendable by autonomous funding;</li>
     *   <li>PAYOUT / AUTO_FUND — balance DOWN (money leaves the pot);</li>
     *   <li>AUTO_REFUND — balance UP and the paid-bounty stat rolled back: this is
     *       the compensation leg when an autonomous placement fails after
     *       funding.</li>
     * </ul>
     *
     * Failures complete the future exceptionally (fail-loud) — callers must
     * compensate; nothing is half-applied.
     */
    public CompletableFuture<TreasuryManager.TreasurySnapshot> adjustTreasury(
            TreasuryManager.Category category, double amount, String note) {
        return this.supply(() -> {
            if (!Double.isFinite(amount) || amount == 0.0) {
                return this.readTreasury();
            }
            try {
                this.connection.setAutoCommit(false);
                try {
                    this.insertLedgerRow(category, amount, note);
                    this.applyTreasuryRow(category, amount);
                    this.connection.commit();
                } finally {
                    this.connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed treasury adjustment ({} {}) — rolled back", category, amount, e);
                this.quietRollback();
                throw new IllegalStateException("Treasury adjustment failed: " + category, e);
            }
            return this.readTreasury();
        });
    }

    /** Ledger row for a movement; the sign carries the direction. */
    private void insertLedgerRow(TreasuryManager.Category category, double amount, String note) throws SQLException {
        double signed = category == TreasuryManager.Category.PAYOUT || category == TreasuryManager.Category.AUTO_FUND
                ? -Math.abs(amount)
                : Math.abs(amount);
        try (PreparedStatement ledger = this.connection.prepareStatement(
                "INSERT INTO treasury_ledger (category, amount, note, timestamp) VALUES (?, ?, ?, ?)")) {
            ledger.setString(1, category.name());
            ledger.setDouble(2, signed);
            ledger.setString(3, note);
            ledger.setLong(4, System.currentTimeMillis());
            ledger.executeUpdate();
        }
    }

    /** Treasury row movement per category semantics (see adjustTreasury docs). */
    private void applyTreasuryRow(TreasuryManager.Category category, double amount) throws SQLException {
        switch (category) {
            case BURN -> this.updateTreasuryRow(
                    "UPDATE treasury SET total_burned = total_burned + ? WHERE id = 1", amount);
            case PAYOUT, AUTO_FUND -> this.updateTreasuryRow(
                    "UPDATE treasury SET balance = balance - ?, total_paid_bounties = total_paid_bounties + ? WHERE id = 1",
                    amount, amount);
            case AUTO_REFUND -> this.updateTreasuryRow(
                    "UPDATE treasury SET balance = balance + ?, total_paid_bounties = MAX(total_paid_bounties - ?, 0) WHERE id = 1",
                    amount, amount);
            default -> this.updateTreasuryRow(
                    "UPDATE treasury SET balance = balance + ?, total_collected_tax = total_collected_tax + ? WHERE id = 1",
                    amount, amount);
        }
    }

    private void updateTreasuryRow(String sql, double... params) throws SQLException {
        try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setDouble(i + 1, Math.abs(params[i]));
            }
            ps.executeUpdate();
        }
    }

    private void quietRollback() {
        try {
            this.connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    public CompletableFuture<List<String>> readTreasuryLedger(int limit) {
        return this.supply(() -> {
            List<String> lines = new ArrayList<>();
            String sql = "SELECT category, amount, note, timestamp FROM treasury_ledger ORDER BY id DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(String.format("#%d %s %s%s",
                                rs.getLong("timestamp"),
                                rs.getString("category"),
                                rs.getDouble("amount"),
                                rs.getString("note") == null ? "" : " (" + rs.getString("note") + ")"));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read treasury ledger", e);
            }
            return lines;
        });
    }

    /**
     * Atomic autonomous-bounty funding: reads the balance and deducts inside a
     * single worker task (so two cycles can never overdraw the treasury), with
     * the ledger row and the balance row applied in ONE database transaction.
     * Returns the fresh snapshot on success, or {@code null} when funds are
     * insufficient or the transaction failed (caller skips placement).
     */
    public CompletableFuture<TreasuryManager.TreasurySnapshot> tryFundAutonomousBounty(double amount, String note) {
        return this.supply(() -> {
            if (!Double.isFinite(amount) || amount <= 0.0) {
                return null;
            }
            TreasuryManager.TreasurySnapshot current = this.readTreasury();
            if (current.balance() < amount) {
                return null;
            }
            try {
                this.connection.setAutoCommit(false);
                try {
                    this.insertLedgerRow(TreasuryManager.Category.AUTO_FUND, amount, note);
                    this.applyTreasuryRow(TreasuryManager.Category.AUTO_FUND, amount);
                    this.connection.commit();
                } finally {
                    this.connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to fund autonomous bounty — rolled back", e);
                this.quietRollback();
                return null;
            }
            return this.readTreasury();
        });
    }

    // ------------------------------------------------------------------
    // Hunter licenses
    // ------------------------------------------------------------------

    /**
     * Persists a license. Returns {@code true} only when the row was actually
     * written — a failed write completes with {@code false} so the purchase
     * path can refund the player (fail-closed money rule).
     */
    public CompletableFuture<Boolean> saveLicense(LicenseData license) {
        return this.supply(() -> {
            String sql = """
                    INSERT INTO hunter_licenses (player_uuid, player_name, tier, purchase_timestamp, expire_timestamp, active)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        player_name = excluded.player_name,
                        tier = excluded.tier,
                        purchase_timestamp = excluded.purchase_timestamp,
                        expire_timestamp = excluded.expire_timestamp,
                        active = excluded.active""";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, license.playerUuid().toString());
                ps.setString(2, license.playerName());
                ps.setString(3, license.tier().name());
                ps.setLong(4, license.purchaseTimestamp());
                ps.setLong(5, license.expireTimestamp());
                ps.setInt(6, license.active() ? 1 : 0);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                LOGGER.error("Failed to save license", e);
                return false;
            }
        });
    }

    public CompletableFuture<Optional<LicenseData>> getLicense(UUID playerUuid) {
        return this.supply(() -> {
            String sql = "SELECT * FROM hunter_licenses WHERE player_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new LicenseData(
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("player_name"),
                                LicenseTier.parse(rs.getString("tier")),
                                rs.getLong("purchase_timestamp"),
                                rs.getLong("expire_timestamp"),
                                rs.getInt("active") == 1));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read license", e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> deactivateLicense(UUID playerUuid) {
        return this.run(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE hunter_licenses SET active = 0 WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to deactivate license", e);
            }
        });
    }

    /** Flags licenses whose expiry has passed as inactive; returns count. */
    public CompletableFuture<Integer> sweepExpiredLicenses() {
        return this.supply(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE hunter_licenses SET active = 0 WHERE active = 1 AND expire_timestamp < ?")) {
                ps.setLong(1, System.currentTimeMillis());
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    LOGGER.info("Deactivated {} expired hunter licenses", updated);
                }
                return updated;
            } catch (SQLException e) {
                LOGGER.error("Failed to sweep licenses", e);
                return 0;
            }
        });
    }

    // ------------------------------------------------------------------
    // Damage records
    // ------------------------------------------------------------------

    public CompletableFuture<Void> recordDamage(UUID targetUuid, UUID attackerUuid, String attackerName, double damage) {
        return this.run(() -> {
            String sql = "INSERT INTO damage_records (target_uuid, attacker_uuid, attacker_name, damage, timestamp) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, attackerUuid.toString());
                ps.setString(3, attackerName == null ? "Unknown" : attackerName);
                ps.setDouble(4, damage);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to record damage", e);
            }
        });
    }

    public CompletableFuture<java.util.Map<UUID, Double>> getDamageContributions(UUID targetUuid, long windowMs) {
        return this.supply(() -> {
            java.util.LinkedHashMap<UUID, Double> contributions = new java.util.LinkedHashMap<>();
            String sql = """
                    SELECT attacker_uuid, SUM(damage) AS total_damage
                    FROM damage_records
                    WHERE target_uuid = ? AND timestamp > ?
                    GROUP BY attacker_uuid
                    ORDER BY total_damage DESC""";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                ps.setLong(2, System.currentTimeMillis() - windowMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        contributions.put(UUID.fromString(rs.getString("attacker_uuid")), rs.getDouble("total_damage"));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read damage contributions", e);
            }
            return contributions;
        });
    }

    /** Latest attacker name per uuid (for payout messages on offline players). */
    public CompletableFuture<java.util.Map<UUID, String>> getAttackerNames(UUID targetUuid, long windowMs) {
        return this.supply(() -> {
            java.util.LinkedHashMap<UUID, String> names = new java.util.LinkedHashMap<>();
            String sql = "SELECT attacker_uuid, attacker_name FROM damage_records WHERE target_uuid = ? AND timestamp > ? ORDER BY timestamp DESC";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, targetUuid.toString());
                ps.setLong(2, System.currentTimeMillis() - windowMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.putIfAbsent(UUID.fromString(rs.getString("attacker_uuid")), rs.getString("attacker_name"));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read attacker names", e);
            }
            return names;
        });
    }

    public CompletableFuture<Void> clearDamageRecords(UUID targetUuid) {
        return this.run(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "DELETE FROM damage_records WHERE target_uuid = ?")) {
                ps.setString(1, targetUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to clear damage records", e);
            }
        });
    }

    public CompletableFuture<Integer> cleanupOldDamageRecords(long maxAgeMs) {
        return this.supply(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "DELETE FROM damage_records WHERE timestamp < ?")) {
                ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
                return ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to clean damage records", e);
                return 0;
            }
        });
    }

    // ------------------------------------------------------------------
    // Kill stats + kill events (collusion analysis)
    // ------------------------------------------------------------------

    public CompletableFuture<Void> recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName) {
        return this.run(() -> {
            try {
                try (PreparedStatement event = this.connection.prepareStatement(
                        "INSERT INTO kill_events (killer_uuid, victim_uuid, timestamp) VALUES (?, ?, ?)")) {
                    event.setString(1, killerUuid.toString());
                    event.setString(2, victimUuid.toString());
                    event.setLong(3, System.currentTimeMillis());
                    event.executeUpdate();
                }
                String killerSql = """
                        INSERT INTO kill_stats (player_uuid, player_name, kills, deaths, current_streak, last_kill_timestamp)
                        VALUES (?, ?, 1, 0, 1, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            kills = kills + 1,
                            current_streak = current_streak + 1,
                            last_kill_timestamp = excluded.last_kill_timestamp,
                            player_name = excluded.player_name""";
                try (PreparedStatement ps = this.connection.prepareStatement(killerSql)) {
                    ps.setString(1, killerUuid.toString());
                    ps.setString(2, killerName);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                String victimSql = """
                        INSERT INTO kill_stats (player_uuid, player_name, kills, deaths, current_streak, last_kill_timestamp, last_death_timestamp)
                        VALUES (?, ?, 0, 1, 0, 0, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            deaths = deaths + 1,
                            current_streak = 0,
                            last_death_timestamp = excluded.last_death_timestamp,
                            player_name = excluded.player_name""";
                try (PreparedStatement ps = this.connection.prepareStatement(victimSql)) {
                    ps.setString(1, victimUuid.toString());
                    ps.setString(2, victimName);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to record kill", e);
            }
        });
    }

    public CompletableFuture<Optional<KillStats>> getKillStats(UUID playerUuid) {
        return this.supply(() -> {
            String sql = "SELECT * FROM kill_stats WHERE player_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new KillStats(
                                rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("current_streak"),
                                rs.getLong("last_kill_timestamp")));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read kill stats", e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<List<KillStatsEntry>> getTopKillers(int limit) {
        return this.supply(() -> {
            List<KillStatsEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM kill_stats ORDER BY current_streak DESC, kills DESC LIMIT ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new KillStatsEntry(
                                rs.getString("player_uuid"), rs.getString("player_name"),
                                rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("current_streak")));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read top killers", e);
            }
            return entries;
        });
    }

    public CompletableFuture<Integer> countPairKills(UUID killer, UUID victim, long windowMs) {
        return this.supply(() -> {
            String sql = "SELECT COUNT(*) AS cnt FROM kill_events WHERE killer_uuid = ? AND victim_uuid = ? AND timestamp > ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, killer.toString());
                ps.setString(2, victim.toString());
                ps.setLong(3, System.currentTimeMillis() - windowMs);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("cnt") : 0;
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to count kill pairs", e);
                return 0;
            }
        });
    }

    public CompletableFuture<Integer> countMutualSwaps(UUID a, UUID b, long windowMs) {
        return this.supply(() -> {
            // Count direction reversals (A->B followed by B->A or vice versa) inside the window.
            String sql = """
                    SELECT killer_uuid, victim_uuid, timestamp FROM kill_events
                    WHERE (killer_uuid = ? AND victim_uuid = ?) OR (killer_uuid = ? AND victim_uuid = ?)
                    ORDER BY timestamp ASC""";
            List<long[]> directions = new ArrayList<>();
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, a.toString());
                ps.setString(2, b.toString());
                ps.setString(3, b.toString());
                ps.setString(4, a.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        directions.add(new long[]{
                                rs.getString("killer_uuid").equals(a.toString()) ? 0 : 1,
                                rs.getLong("timestamp")});
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to read kill events for swaps", e);
                return 0;
            }
            long cutoff = System.currentTimeMillis() - windowMs;
            int swaps = 0;
            Integer lastDirection = null;
            for (long[] row : directions) {
                if (row[1] < cutoff) {
                    continue;
                }
                int direction = (int) row[0];
                if (lastDirection != null && lastDirection != direction) {
                    swaps++;
                }
                lastDirection = direction;
            }
            return swaps;
        });
    }

    public CompletableFuture<Void> flagCollusion(UUID player1, String name1, UUID player2, String name2, String reason) {
        return this.run(() -> {
            String sql = "INSERT INTO collusion_flags (player1_uuid, player1_name, player2_uuid, player2_name, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql)) {
                ps.setString(1, player1.toString());
                ps.setString(2, name1);
                ps.setString(3, player2.toString());
                ps.setString(4, name2);
                ps.setString(5, reason);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                LOGGER.warn("Collusion flagged: {} <-> {} — {}", name1, name2, reason);
            } catch (SQLException e) {
                LOGGER.error("Failed to flag collusion", e);
            }
        });
    }

    public CompletableFuture<Integer> cleanupOldKillEvents(long maxAgeMs) {
        return this.supply(() -> {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "DELETE FROM kill_events WHERE timestamp < ?")) {
                ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
                return ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to clean kill events", e);
                return 0;
            }
        });
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    public record KillStatsEntry(String uuid, String name, int kills, int deaths, int currentStreak) {
    }

    public record KillStats(int kills, int deaths, int currentStreak, long lastKillTimestamp) {
        public double kdRatio() {
            return this.deaths == 0 ? this.kills : (double) this.kills / this.deaths;
        }
    }
}
