/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package com.solidus.enforcer.storage;

import com.solidus.enforcer.bounty.BountyEntry;
import com.solidus.enforcer.bounty.BountyStatus;
import com.solidus.enforcer.license.LicenseData;
import com.solidus.enforcer.license.LicenseTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EnforcerStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Solidus-Enforcer");
    private final Path dbPath;
    private final ExecutorService executor;
    private volatile Connection connection;

    public EnforcerStorage(Path configDir) {
        this.dbPath = configDir.resolve("solidus-enforcer").resolve("enforcer.db");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Enforcer-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(this.dbPath.getParent(), new FileAttribute[0]);
                this.connection = DriverManager.getConnection("jdbc:sqlite:" + String.valueOf(this.dbPath));
                this.connection.setAutoCommit(true);
                try (Statement stmt = this.connection.createStatement();){
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA temp_store=MEMORY");
                    stmt.execute("PRAGMA mmap_size=67108864");
                    stmt.execute("PRAGMA cache_size=2048");
                }
                this.createTables();
                LOGGER.info("Enforcer database initialized at {}", (Object)this.dbPath);
            }
            catch (Exception e) {
                LOGGER.error("Failed to initialize Enforcer database", (Throwable)e);
                throw new RuntimeException(e);
            }
        }, this.executor);
    }

    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
                LOGGER.warn("Enforcer worker forced to stop after shutdown timeout");
            }
        }
        catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while stopping Enforcer worker", e);
        }
        try {
            if (this.connection != null && !this.connection.isClosed()) {
                this.connection.close();
            }
            LOGGER.info("Enforcer database shut down");
        }
        catch (SQLException e) {
            LOGGER.error("Error closing Enforcer database", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = this.connection.createStatement();){
            stmt.execute("    CREATE TABLE IF NOT EXISTS bounties (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        target_uuid TEXT NOT NULL,\n        target_name TEXT NOT NULL,\n        total_amount REAL NOT NULL,\n        original_amount REAL NOT NULL,\n        blood_tax_deducted REAL DEFAULT 0,\n        contract_fees_deducted REAL DEFAULT 0,\n        placed_by_uuid TEXT,\n        placed_by_name TEXT,\n        placed_timestamp INTEGER NOT NULL,\n        expire_timestamp INTEGER NOT NULL,\n        status INTEGER DEFAULT 0,\n        autonomous INTEGER DEFAULT 0,\n        autonomous_reason TEXT\n    )\n");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_target ON bounties (target_uuid, status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_placer ON bounties (placed_by_uuid, status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bounty_expire ON bounties (expire_timestamp, status)");
            stmt.execute("    CREATE TABLE IF NOT EXISTS hunter_licenses (\n        player_uuid TEXT PRIMARY KEY,\n        player_name TEXT NOT NULL,\n        tier TEXT NOT NULL DEFAULT 'BRONZE',\n        purchase_timestamp INTEGER NOT NULL,\n        expire_timestamp INTEGER NOT NULL,\n        active INTEGER DEFAULT 1\n    )\n");
            stmt.execute("    CREATE TABLE IF NOT EXISTS damage_records (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        target_uuid TEXT NOT NULL,\n        attacker_uuid TEXT NOT NULL,\n        damage REAL NOT NULL,\n        timestamp INTEGER NOT NULL\n    )\n");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_damage_target ON damage_records (target_uuid, timestamp DESC)");
            stmt.execute("    CREATE TABLE IF NOT EXISTS treasury (\n        id INTEGER PRIMARY KEY CHECK (id = 1),\n        balance REAL DEFAULT 0,\n        total_collected_tax REAL DEFAULT 0,\n        total_burned REAL DEFAULT 0,\n        total_paid_bounties REAL DEFAULT 0\n    )\n");
            stmt.execute("INSERT OR IGNORE INTO treasury (id, balance, total_collected_tax, total_burned, total_paid_bounties) VALUES (1, 0, 0, 0, 0)");
            stmt.execute("    CREATE TABLE IF NOT EXISTS collusion_flags (\n        id INTEGER PRIMARY KEY AUTOINCREMENT,\n        player1_uuid TEXT NOT NULL,\n        player1_name TEXT NOT NULL,\n        player2_uuid TEXT NOT NULL,\n        player2_name TEXT NOT NULL,\n        reason TEXT NOT NULL,\n        timestamp INTEGER NOT NULL\n    )\n");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_collusion_players ON collusion_flags (player1_uuid, player2_uuid)");
            stmt.execute("    CREATE TABLE IF NOT EXISTS kill_stats (\n        player_uuid TEXT PRIMARY KEY,\n        player_name TEXT NOT NULL,\n        kills INTEGER DEFAULT 0,\n        deaths INTEGER DEFAULT 0,\n        current_streak INTEGER DEFAULT 0,\n        last_kill_timestamp INTEGER DEFAULT 0,\n        last_death_timestamp INTEGER DEFAULT 0\n    )\n");
        }
    }

    public CompletableFuture<Integer> insertBounty(BountyEntry bounty) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "    INSERT INTO bounties (target_uuid, target_name, total_amount, original_amount,\n        blood_tax_deducted, contract_fees_deducted, placed_by_uuid, placed_by_name,\n        placed_timestamp, expire_timestamp, status, autonomous, autonomous_reason)\n    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n";
            try (PreparedStatement ps = this.connection.prepareStatement(sql, 1);){
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
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
                return -1;
            }
            catch (SQLException e) {
                LOGGER.error("Failed to insert bounty", (Throwable)e);
                return -1;
            }
        }, this.executor);
    }

    public CompletableFuture<List<BountyEntry>> getActiveBounties() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM bounties WHERE status IN (0, 4) ORDER BY total_amount DESC";
            ArrayList<BountyEntry> bounties = new ArrayList<BountyEntry>();
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                while (rs.next()) {
                    bounties.add(this.mapBounty(rs));
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get active bounties", (Throwable)e);
            }
            return bounties;
        }, this.executor);
    }

    public CompletableFuture<List<BountyEntry>> getBountiesForTarget(UUID targetUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM bounties WHERE target_uuid = ? AND status IN (0, 4) ORDER BY total_amount DESC";
            ArrayList<BountyEntry> bounties = new ArrayList<BountyEntry>();
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        bounties.add(this.mapBounty(rs));
                    }
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get bounties for target", (Throwable)e);
            }
            return bounties;
        }, this.executor);
    }

    public CompletableFuture<Double> getTotalBountyForTarget(UUID targetUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT SUM(total_amount) as total FROM bounties WHERE target_uuid = ? AND status IN (0, 4)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return 0.0;
                    Double d = rs.getDouble("total");
                    return d;
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get total bounty for target", (Throwable)e);
            }
            return 0.0;
        }, this.executor);
    }

    public CompletableFuture<Integer> getActiveBountyCountByPlacer(UUID placerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) as cnt FROM bounties WHERE placed_by_uuid = ? AND status IN (0, 4)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, placerUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return 0;
                    Integer n = rs.getInt("cnt");
                    return n;
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to count active bounties by placer", (Throwable)e);
            }
            return 0;
        }, this.executor);
    }

    public CompletableFuture<Integer> getActiveBountyCountForTarget(UUID targetUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) as cnt FROM bounties WHERE target_uuid = ? AND status IN (0, 4)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return 0;
                    Integer n = rs.getInt("cnt");
                    return n;
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to count bounties for target", (Throwable)e);
            }
            return 0;
        }, this.executor);
    }

    public CompletableFuture<Void> updateBountyStatus(int bountyId, BountyStatus status) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE bounties SET status = ? WHERE id = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setInt(1, status.getCode());
                ps.setInt(2, bountyId);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to update bounty status", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Void> updateBountyAmount(int bountyId, double newAmount, double contractFee) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE bounties SET total_amount = ?, contract_fees_deducted = contract_fees_deducted + ? WHERE id = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setDouble(1, newAmount);
                ps.setDouble(2, contractFee);
                ps.setInt(3, bountyId);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to update bounty amount", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Void> expireOldBounties() {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE bounties SET status = 3 WHERE status IN (0, 4) AND expire_timestamp < ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setLong(1, System.currentTimeMillis());
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    LOGGER.info("Expired {} old bounties", (Object)updated);
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to expire old bounties", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Void> saveLicense(LicenseData license) {
        return CompletableFuture.runAsync(() -> {
            String sql = "    INSERT INTO hunter_licenses (player_uuid, player_name, tier, purchase_timestamp, expire_timestamp, active)\n    VALUES (?, ?, ?, ?, ?, ?)\n    ON CONFLICT(player_uuid) DO UPDATE SET\n        player_name = excluded.player_name,\n        tier = excluded.tier,\n        purchase_timestamp = excluded.purchase_timestamp,\n        expire_timestamp = excluded.expire_timestamp,\n        active = excluded.active\n";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, license.playerUuid().toString());
                ps.setString(2, license.playerName());
                ps.setString(3, license.tier().name());
                ps.setLong(4, license.purchaseTimestamp());
                ps.setLong(5, license.expireTimestamp());
                ps.setInt(6, license.active() ? 1 : 0);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to save license", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Optional<LicenseData>> getLicense(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM hunter_licenses WHERE player_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return Optional.empty();
                    Optional<LicenseData> optional = Optional.of(this.mapLicense(rs));
                    return optional;
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get license", (Throwable)e);
            }
            return Optional.empty();
        }, this.executor);
    }

    public CompletableFuture<Void> deactivateLicense(UUID playerUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE hunter_licenses SET active = 0 WHERE player_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to deactivate license", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Void> recordDamage(UUID targetUuid, UUID attackerUuid, double damage) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO damage_records (target_uuid, attacker_uuid, damage, timestamp) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                ps.setString(2, attackerUuid.toString());
                ps.setDouble(3, damage);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to record damage", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Map<UUID, Double>> getDamageContributions(UUID targetUuid, long windowMs) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "    SELECT attacker_uuid, SUM(damage) as total_damage\n    FROM damage_records\n    WHERE target_uuid = ? AND timestamp > ?\n    GROUP BY attacker_uuid\n    ORDER BY total_damage DESC\n";
            LinkedHashMap<UUID, Double> contributions = new LinkedHashMap<UUID, Double>();
            long cutoff = System.currentTimeMillis() - windowMs;
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, targetUuid.toString());
                ps.setLong(2, cutoff);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        UUID attacker = UUID.fromString(rs.getString("attacker_uuid"));
                        contributions.put(attacker, rs.getDouble("total_damage"));
                    }
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get damage contributions", (Throwable)e);
            }
            return contributions;
        }, this.executor);
    }

    public CompletableFuture<Void> cleanOldDamageRecords(long maxAgeMs) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM damage_records WHERE timestamp < ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setLong(1, System.currentTimeMillis() - maxAgeMs);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to clean old damage records", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Double> getTreasuryBalance() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT balance FROM treasury WHERE id = 1";
            try (Statement stmt = this.connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);){
                if (!rs.next()) return 0.0;
                Double d = rs.getDouble("balance");
                return d;
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get treasury balance", (Throwable)e);
            }
            return 0.0;
        }, this.executor);
    }

    public CompletableFuture<Void> addToTreasury(double amount, String category) {
        return CompletableFuture.runAsync(() -> {
            String balanceSql = "UPDATE treasury SET balance = balance + ?, total_collected_tax = total_collected_tax + ? WHERE id = 1";
            String burnSql = "UPDATE treasury SET total_burned = total_burned + ? WHERE id = 1";
            String paidSql = "UPDATE treasury SET balance = balance - ?, total_paid_bounties = total_paid_bounties + ? WHERE id = 1";
            try {
                switch (category) {
                    case "tax": {
                        try (PreparedStatement ps = this.connection.prepareStatement(balanceSql);){
                            ps.setDouble(1, amount);
                            ps.setDouble(2, amount);
                            ps.executeUpdate();
                            break;
                        }
                    }
                    case "burn": {
                        try (PreparedStatement ps = this.connection.prepareStatement(burnSql);){
                            ps.setDouble(1, amount);
                            ps.executeUpdate();
                            break;
                        }
                    }
                    case "paid": {
                        PreparedStatement ps = this.connection.prepareStatement(paidSql);
                        try {
                            ps.setDouble(1, amount);
                            ps.setDouble(2, amount);
                            ps.executeUpdate();
                            if (ps == null) break;
                        }
                        catch (Throwable t$) {
                            if (ps != null) {
                                try {
                                    ps.close();
                                }
                                catch (Throwable x2) {
                                    t$.addSuppressed(x2);
                                }
                            }
                            throw t$;
                        }
                        ps.close();
                        break;
                    }
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to update treasury", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Void> recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName) {
        return CompletableFuture.runAsync(() -> {
            String killerSql = "    INSERT INTO kill_stats (player_uuid, player_name, kills, deaths, current_streak, last_kill_timestamp)\n    VALUES (?, ?, 1, 0, 1, ?)\n    ON CONFLICT(player_uuid) DO UPDATE SET\n        kills = kills + 1,\n        current_streak = current_streak + 1,\n        last_kill_timestamp = excluded.last_kill_timestamp,\n        player_name = excluded.player_name\n";
            try (PreparedStatement ps = this.connection.prepareStatement(killerSql);){
                ps.setString(1, killerUuid.toString());
                ps.setString(2, killerName);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to record kill", (Throwable)e);
            }
            String victimSql = "    INSERT INTO kill_stats (player_uuid, player_name, kills, deaths, current_streak, last_death_timestamp)\n    VALUES (?, ?, 0, 1, 0, ?)\n    ON CONFLICT(player_uuid) DO UPDATE SET\n        deaths = deaths + 1,\n        current_streak = 0,\n        last_death_timestamp = excluded.last_death_timestamp,\n        player_name = excluded.player_name\n";
            try (PreparedStatement ps = this.connection.prepareStatement(victimSql);){
                ps.setString(1, victimUuid.toString());
                ps.setString(2, victimName);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to record death", (Throwable)e);
            }
        }, this.executor);
    }

    public CompletableFuture<Optional<KillStats>> getKillStats(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM kill_stats WHERE player_uuid = ?";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery();){
                    if (!rs.next()) return Optional.empty();
                    Optional<KillStats> optional = Optional.of(new KillStats(rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("current_streak"), rs.getLong("last_kill_timestamp")));
                    return optional;
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get kill stats", (Throwable)e);
            }
            return Optional.empty();
        }, this.executor);
    }

    public CompletableFuture<List<KillStatsEntry>> getTopKillers(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM kill_stats ORDER BY current_streak DESC, kills DESC LIMIT ?";
            ArrayList<KillStatsEntry> entries = new ArrayList<KillStatsEntry>();
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery();){
                    while (rs.next()) {
                        entries.add(new KillStatsEntry(rs.getString("player_uuid"), rs.getString("player_name"), rs.getInt("kills"), rs.getInt("deaths"), rs.getInt("current_streak")));
                    }
                }
            }
            catch (SQLException e) {
                LOGGER.error("Failed to get top killers", (Throwable)e);
            }
            return entries;
        }, this.executor);
    }

    public CompletableFuture<Void> flagCollusion(UUID player1, String name1, UUID player2, String name2, String reason) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO collusion_flags (player1_uuid, player1_name, player2_uuid, player2_name, reason, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                ps.setString(1, player1.toString());
                ps.setString(2, name1);
                ps.setString(3, player2.toString());
                ps.setString(4, name2);
                ps.setString(5, reason);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            catch (SQLException e) {
                LOGGER.error("Failed to flag collusion", (Throwable)e);
            }
        }, this.executor);
    }

    private BountyEntry mapBounty(ResultSet rs) throws SQLException {
        return new BountyEntry(rs.getInt("id"), UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"), rs.getDouble("total_amount"), rs.getDouble("original_amount"), rs.getDouble("blood_tax_deducted"), rs.getDouble("contract_fees_deducted"), rs.getString("placed_by_uuid") != null ? UUID.fromString(rs.getString("placed_by_uuid")) : null, rs.getString("placed_by_name"), rs.getLong("placed_timestamp"), rs.getLong("expire_timestamp"), BountyStatus.fromCode(rs.getInt("status")), rs.getInt("autonomous") == 1, rs.getString("autonomous_reason"));
    }

    private LicenseData mapLicense(ResultSet rs) throws SQLException {
        return new LicenseData(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"), LicenseTier.fromString(rs.getString("tier")), rs.getLong("purchase_timestamp"), rs.getLong("expire_timestamp"), rs.getInt("active") == 1);
    }

    public record KillStatsEntry(String uuid, String name, int kills, int deaths, int currentStreak) {
    }

    public record KillStats(int kills, int deaths, int currentStreak, long lastKillTimestamp) {
        public double kdRatio() {
            return this.deaths == 0 ? (double)this.kills : (double)this.kills / (double)this.deaths;
        }
    }
}
