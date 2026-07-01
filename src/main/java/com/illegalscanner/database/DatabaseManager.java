package com.illegalscanner.database;

import com.illegalscanner.IllegalScanner;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Manages SQLite database for scan_records, monitor_records, item_index, and whitelists.
 * WAL mode, single background thread for writes, synchronous reads.
 */
public class DatabaseManager {

    private final IllegalScanner plugin;
    private final ExecutorService dbExecutor;
    private Connection connection;

    public DatabaseManager(IllegalScanner plugin) {
        this.plugin = plugin;
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "illegal-scanner-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() throws Exception {
        File dbFile = new File(plugin.getDataFolder(),
                plugin.getConfigManager().getConfig().getString("database.file", "items.db"));

        File parentDir = dbFile.getAbsoluteFile().getParentFile();
        if (!parentDir.exists()) parentDir.mkdirs();

        boolean isNew = !dbFile.exists();
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("PRAGMA busy_timeout=5000");
        }

        createTables();
        plugin.getLogger().info("Database " + (isNew ? "created" : "opened") + ": " + dbFile.getAbsolutePath());
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // --- Item index (NBT hash → item info) ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS item_index (
                    item_hash   TEXT PRIMARY KEY,
                    item_type   TEXT NOT NULL,
                    item_snapshot TEXT NOT NULL
                )
            """);

            // --- Scan records (manual /is scan) ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS scan_records (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id      INTEGER,
                    item_hash       TEXT NOT NULL,
                    scan_type       TEXT NOT NULL,
                    world           TEXT NOT NULL,
                    chunk_x         INTEGER NOT NULL,
                    chunk_z         INTEGER NOT NULL,
                    player_uuid     TEXT,
                    player_name     TEXT,
                    item_slot       INTEGER,
                    container       TEXT,
                    container_loc   TEXT,
                    violations      TEXT NOT NULL,
                    severity        TEXT NOT NULL DEFAULT 'ILLEGAL',
                    scan_time       INTEGER NOT NULL,
                    FOREIGN KEY (item_hash) REFERENCES item_index(item_hash)
                )
            """);

            // --- Monitor records (real-time event detection) ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS monitor_records (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_hash       TEXT NOT NULL,
                    event_type      TEXT NOT NULL,
                    world           TEXT NOT NULL,
                    chunk_x         INTEGER NOT NULL,
                    chunk_z         INTEGER NOT NULL,
                    player_uuid     TEXT,
                    player_name     TEXT,
                    item_slot       INTEGER,
                    container       TEXT,
                    container_loc   TEXT,
                    violations      TEXT NOT NULL,
                    severity        TEXT NOT NULL DEFAULT 'ILLEGAL',
                    scan_time       INTEGER NOT NULL,
                    FOREIGN KEY (item_hash) REFERENCES item_index(item_hash)
                )
            """);

            // --- Scan sessions ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS scan_sessions (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_type       TEXT NOT NULL,
                    target          TEXT NOT NULL,
                    started_at      INTEGER NOT NULL,
                    completed_at    INTEGER,
                    items_scanned   INTEGER DEFAULT 0,
                    total_items     INTEGER DEFAULT 0,
                    items_flagged   INTEGER DEFAULT 0,
                    status          TEXT NOT NULL DEFAULT 'RUNNING'
                )
            """);

            // --- Plugin settings (persistent config) ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS plugin_settings (
                    key     TEXT PRIMARY KEY,
                    value   TEXT NOT NULL
                )
            """);

            // --- Whitelist tables (retained from old schema) ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS item_whitelist (
                    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                    material                TEXT NOT NULL,
                    item_hash               TEXT,
                    custom_name_pattern     TEXT,
                    lore_pattern            TEXT,
                    enchantments_json       TEXT,
                    attribute_modifiers_json TEXT,
                    created_at              INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_item_whitelist_material ON item_whitelist(material)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS area_whitelist (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    world       TEXT NOT NULL,
                    area_type   TEXT NOT NULL DEFAULT 'CHUNK',
                    min_x       INTEGER NOT NULL,
                    min_z       INTEGER NOT NULL,
                    max_x       INTEGER NOT NULL,
                    max_z       INTEGER NOT NULL,
                    created_at  INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_area_whitelist_world ON area_whitelist(world)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS region_whitelist (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    plugin_name  TEXT NOT NULL,
                    region_name  TEXT NOT NULL,
                    world_name   TEXT,
                    created_at   INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_region_whitelist_plugin ON region_whitelist(plugin_name)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_whitelist (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL UNIQUE,
                    player_name TEXT NOT NULL,
                    hidden      INTEGER NOT NULL DEFAULT 0,
                    created_at  INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_whitelist_uuid ON player_whitelist(player_uuid)");

            // --- World whitelist ---
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS world_whitelist (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    world_name  TEXT NOT NULL UNIQUE,
                    created_at  INTEGER NOT NULL
                )
            """);

            // --- Indexes for scan_records ---
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_scan_chunk ON scan_records(world, chunk_x, chunk_z, scan_time DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_scan_player ON scan_records(player_uuid, scan_time DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_scan_session ON scan_records(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_scan_item ON scan_records(item_hash)");

            // --- Indexes for monitor_records ---
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_monitor_chunk ON monitor_records(world, chunk_x, chunk_z, scan_time DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_monitor_player ON monitor_records(player_uuid, scan_time DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_monitor_item ON monitor_records(item_hash)");
        }
    }

    // ==================== Item Index ====================

    public record ItemIndexEntry(String itemHash, String itemType, String itemSnapshot) {}

    public ItemIndexEntry getItemByHash(String itemHash) {
        String sql = "SELECT item_hash, item_type, item_snapshot FROM item_index WHERE item_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ItemIndexEntry(
                            rs.getString("item_hash"),
                            rs.getString("item_type"),
                            rs.getString("item_snapshot"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get item by hash", e);
        }
        return null;
    }

    public CompletableFuture<Void> ensureItemIndexed(String itemHash, String itemType, String itemSnapshot) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO item_index (item_hash, item_type, item_snapshot) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, itemHash);
                ps.setString(2, itemType);
                ps.setString(3, itemSnapshot);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to index item", e);
            }
        }, dbExecutor);
    }

    /** Get all distinct item hashes that have active records (for /is view item listing). */
    public List<ItemIndexEntry> getDistinctViolationItems() {
        List<ItemIndexEntry> list = new ArrayList<>();
        // Union of distinct item_hashes from both tables
        String sql = """
            SELECT DISTINCT item_hash FROM (
                SELECT item_hash FROM scan_records
                UNION
                SELECT item_hash FROM monitor_records
            ) ORDER BY item_hash
        """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ItemIndexEntry entry = getItemByHash(rs.getString("item_hash"));
                if (entry != null) list.add(entry);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get distinct violation items", e);
        }
        return list;
    }

    /** Get count of records for a specific item_hash across both tables. */
    public int getItemRecordCount(String itemHash) {
        String sql = """
            SELECT (SELECT COUNT(*) FROM scan_records WHERE item_hash = ?)
                 + (SELECT COUNT(*) FROM monitor_records WHERE item_hash = ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemHash);
            ps.setString(2, itemHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ==================== Scan Records ====================

    public record ScanRecord(
            long id, Integer sessionId, String itemHash, String scanType,
            String world, int chunkX, int chunkZ,
            String playerUuid, String playerName, Integer itemSlot,
            String container, String containerLoc,
            String violations, String severity, long scanTime
    ) {}

    public CompletableFuture<Long> insertScanRecord(ScanRecord record) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO scan_records (session_id, item_hash, scan_type, world, chunk_x, chunk_z,
                    player_uuid, player_name, item_slot, container, container_loc,
                    violations, severity, scan_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                setIntOrNull(ps, 1, record.sessionId());
                ps.setString(2, record.itemHash());
                ps.setString(3, record.scanType());
                ps.setString(4, record.world());
                ps.setInt(5, record.chunkX());
                ps.setInt(6, record.chunkZ());
                setStringOrNull(ps, 7, record.playerUuid());
                setStringOrNull(ps, 8, record.playerName());
                setIntOrNull(ps, 9, record.itemSlot());
                setStringOrNull(ps, 10, record.container());
                setStringOrNull(ps, 11, record.containerLoc());
                ps.setString(12, record.violations());
                ps.setString(13, record.severity());
                ps.setLong(14, record.scanTime());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert scan record", e);
            }
            return -1L;
        }, dbExecutor);
    }

    /**
     * Get the most recent scan time for a specific chunk from scan_records.
     * Only considers manual scans (not monitor events). Returns 0 if never scanned.
     * Called from async DB thread only — never from main thread.
     */
    public long getLastChunkScanTime(String world, int chunkX, int chunkZ) {
        String sql = "SELECT MAX(scan_time) FROM scan_records WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.FINE, "Failed to get last chunk scan time", e);
        }
        return 0L;
    }

    public CompletableFuture<Integer> deleteScanRecordsBySession(int sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM scan_records WHERE session_id = ?")) {
                ps.setInt(1, sessionId);
                return ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete scan records by session", e);
                return 0;
            }
        }, dbExecutor);
    }

    // ==================== Monitor Records ====================

    public record MonitorRecord(
            long id, String itemHash, String eventType,
            String world, int chunkX, int chunkZ,
            String playerUuid, String playerName, Integer itemSlot,
            String container, String containerLoc,
            String violations, String severity, long scanTime
    ) {}

    public CompletableFuture<Long> insertMonitorRecord(MonitorRecord record) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO monitor_records (item_hash, event_type, world, chunk_x, chunk_z,
                    player_uuid, player_name, item_slot, container, container_loc,
                    violations, severity, scan_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, record.itemHash());
                ps.setString(2, record.eventType());
                ps.setString(3, record.world());
                ps.setInt(4, record.chunkX());
                ps.setInt(5, record.chunkZ());
                setStringOrNull(ps, 6, record.playerUuid());
                setStringOrNull(ps, 7, record.playerName());
                setIntOrNull(ps, 8, record.itemSlot());
                setStringOrNull(ps, 9, record.container());
                setStringOrNull(ps, 10, record.containerLoc());
                ps.setString(11, record.violations());
                ps.setString(12, record.severity());
                ps.setLong(13, record.scanTime());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert monitor record", e);
            }
            return -1L;
        }, dbExecutor);
    }

    /**
     * Check if a duplicate monitor record exists within the given window.
     * Dedup key: (item_hash, player_uuid, container_loc) within flush_interval ms.
     */
    public boolean hasRecentMonitorRecord(String itemHash, String playerUuid, String containerLoc, long flushIntervalMs) {
        long cutoff = System.currentTimeMillis() - flushIntervalMs;
        String sql = """
            SELECT 1 FROM monitor_records
            WHERE item_hash = ? AND player_uuid = ?
              AND (container_loc = ? OR (container_loc IS NULL AND ? IS NULL))
              AND scan_time > ?
            LIMIT 1
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemHash);
            setStringOrNull(ps, 2, playerUuid);
            setStringOrNull(ps, 3, containerLoc);
            setStringOrNull(ps, 4, containerLoc);
            ps.setLong(5, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Delete ALL monitor records for a specific container location.
     * Called by CONTAINER_CLOSE handler before writing new snapshot —
     * the re-scan replaces all old monitor state for this container (scan覆盖monitor).
     */
    public void deleteMonitorRecordsByContainerLoc(String containerLoc) {
        String sql = "DELETE FROM monitor_records WHERE container_loc = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, containerLoc);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().fine("Cleaned " + deleted + " old monitor records for container: " + containerLoc);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete monitor records for container: " + containerLoc, e);
        }
    }

    /**
     * Get distinct item_hashes from scan_records for a specific container location.
     * Used by CONTAINER_CLOSE to determine which old scan items have been removed
     * and need a CONTAINER_CLEAN marker.
     */
    public java.util.Set<String> getScanItemHashesByContainerLoc(String containerLoc) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        String sql = "SELECT DISTINCT item_hash FROM scan_records WHERE container_loc = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, containerLoc);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) hashes.add(rs.getString("item_hash"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get scan item hashes for container: " + containerLoc, e);
        }
        return hashes;
    }

    /**
     * Get distinct item_hashes from scan_records for an entire chunk.
     * Used before re-scan to snapshot which items were previously flagged.
     * After scan, any old hash not found in current violations gets a CLEAN marker.
     */
    public java.util.Set<String> getScanItemHashesByChunk(String world, int chunkX, int chunkZ) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        String sql = "SELECT DISTINCT item_hash FROM scan_records WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) hashes.add(rs.getString("item_hash"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get scan item hashes for chunk: "
                    + world + "(" + chunkX + "," + chunkZ + ")", e);
        }
        return hashes;
    }

    /**
     * Delete ALL monitor records for a chunk. Called before scan to make scan results
     * authoritative — the scan replaces all old monitor state for this chunk.
     */
    public void deleteMonitorRecordsByChunk(String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM monitor_records WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().fine("Cleaned " + deleted + " old monitor records for chunk: "
                        + world + "(" + chunkX + "," + chunkZ + ")");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete monitor records for chunk: "
                    + world + "(" + chunkX + "," + chunkZ + ")", e);
        }
    }

    /**
     * Delete ALL scan records for a chunk. Called before re-scan to make scan results
     * authoritative — the new scan replaces all old scan state for this chunk.
     * No CLEAN markers are needed; empty chunk simply has no records after scan.
     */
    public void deleteScanRecordsByChunk(String world, int chunkX, int chunkZ) {
        String sql = "DELETE FROM scan_records WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().fine("Cleaned " + deleted + " old scan records for chunk: "
                        + world + "(" + chunkX + "," + chunkZ + ")");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete scan records for chunk: "
                    + world + "(" + chunkX + "," + chunkZ + ")", e);
        }
    }

    /**
     * Delete ALL monitor records for a player. Called before player scan to make scan
     * results authoritative — the scan replaces all old monitor state for this player.
     */
    public void deleteMonitorRecordsByPlayer(String playerUuid) {
        String sql = "DELETE FROM monitor_records WHERE player_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().fine("Cleaned " + deleted + " old monitor records for player: " + playerUuid);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete monitor records for player: " + playerUuid, e);
        }
    }

    /** Purge monitor records older than retention days. */
    public CompletableFuture<Integer> purgeOldMonitorRecords(int retentionDays) {
        return CompletableFuture.supplyAsync(() -> {
            long cutoff = System.currentTimeMillis() - (retentionDays * 86400_000L);
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM monitor_records WHERE scan_time < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to purge monitor records", e);
                return 0;
            }
        }, dbExecutor);
    }

    // ==================== Scan Sessions ====================

    public record ScanSession(
            int id, String scanType, String target,
            long startedAt, Long completedAt,
            int itemsScanned, int totalItems, int itemsFlagged, String status
    ) {}

    public CompletableFuture<Integer> createScanSession(String scanType, String target, int totalItems) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO scan_sessions (scan_type, target, started_at, total_items, status) VALUES (?, ?, ?, ?, 'RUNNING')";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, scanType);
                ps.setString(2, target);
                ps.setLong(3, System.currentTimeMillis());
                ps.setInt(4, totalItems);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create scan session", e);
            }
            return -1;
        }, dbExecutor);
    }

    public CompletableFuture<Void> completeScanSession(int sessionId, int itemsScanned, int itemsFlagged) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE scan_sessions SET completed_at=?, items_scanned=?, items_flagged=?, status='COMPLETED' WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, itemsScanned);
                ps.setInt(3, itemsFlagged);
                ps.setInt(4, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to complete scan session", e);
            }
        }, dbExecutor);
    }

    /**
     * Update scan session progress incrementally (does NOT change status).
     * Called periodically during long-running scans so GUI shows real progress.
     */
    public CompletableFuture<Void> updateScanSessionProgress(int sessionId, int itemsScanned, int itemsFlagged) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE scan_sessions SET items_scanned=?, items_flagged=? WHERE id=? AND status='RUNNING'";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, itemsScanned);
                ps.setInt(2, itemsFlagged);
                ps.setInt(3, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update scan session progress", e);
            }
        }, dbExecutor);
    }

    /**
     * Update scan session status (RUNNING, PAUSED, STOPPED, COMPLETED).
     */
    public CompletableFuture<Void> setSessionStatus(int sessionId, String status) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE scan_sessions SET status=? WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setInt(2, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update scan session status", e);
            }
        }, dbExecutor);
    }

    /**
     * Stop a scan session — sets status to STOPPED and records completed_at.
     */
    public CompletableFuture<Void> stopScanSession(int sessionId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE scan_sessions SET status='STOPPED', completed_at=? WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to stop scan session", e);
            }
        }, dbExecutor);
    }

    /**
     * Reset sessions that cannot survive a server restart.
     * - RUNNING sessions → STOPPED (the BukkitTask is gone, can't recover)
     * - PAUSED sessions are left as-is (resumeScan will fall back to restart)
     * Call this once on plugin enable, after DB is ready.
     *
     * @return future with count of sessions that were reset
     */
    public CompletableFuture<Integer> resetStaleSessionsOnStartup() {
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE scan_sessions SET status='STOPPED', completed_at=? WHERE status='RUNNING'")) {
                ps.setLong(1, System.currentTimeMillis());
                count = ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset stale scan sessions", e);
            }
            return count;
        }, dbExecutor);
    }

    public ScanSession getScanSession(int id) {
        String sql = "SELECT * FROM scan_sessions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapScanSession(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get scan session", e);
        }
        return null;
    }

    public int countScanSessions() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM scan_sessions")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    public List<ScanSession> getScanSessions(int page, int pageSize) {
        List<ScanSession> list = new ArrayList<>();
        String sql = "SELECT * FROM scan_sessions ORDER BY started_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapScanSession(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get scan sessions", e);
        }
        return list;
    }

    private ScanSession mapScanSession(ResultSet rs) throws SQLException {
        long completed = rs.getLong("completed_at");
        boolean completedNull = rs.wasNull(); // must check immediately after reading the column
        return new ScanSession(
                rs.getInt("id"), rs.getString("scan_type"), rs.getString("target"),
                rs.getLong("started_at"), completedNull ? null : completed,
                rs.getInt("items_scanned"), rs.getInt("total_items"), rs.getInt("items_flagged"),
                rs.getString("status"));
    }

    // ==================== Unified Queries ====================

    /**
     * Unified record combining scan + monitor data.
     */
    public record UnifiedRecord(
            long id, String source, String itemHash, String scanType,
            String world, int chunkX, int chunkZ,
            String playerUuid, String playerName, Integer itemSlot,
            String container, String containerLoc,
            String violations, String severity, long scanTime
    ) {}

    /** Query records by chunk (union of scan + monitor). Excludes player-inventory records. */
    public List<UnifiedRecord> getRecordsByChunk(String world, int chunkX, int chunkZ, int page, int pageSize) {
        List<UnifiedRecord> list = new ArrayList<>();
        String sql = """
            SELECT id, 'SCAN' AS source, item_hash, scan_type AS scan_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM scan_records
            WHERE world = ? AND chunk_x = ? AND chunk_z = ?
              AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
            UNION ALL
            SELECT id, 'MONITOR' AS source, item_hash, event_type AS scan_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM monitor_records
            WHERE world = ? AND chunk_x = ? AND chunk_z = ?
              AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
            ORDER BY scan_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world); ps.setInt(2, chunkX); ps.setInt(3, chunkZ);
            ps.setString(4, world); ps.setInt(5, chunkX); ps.setInt(6, chunkZ);
            ps.setInt(7, pageSize);
            ps.setInt(8, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapUnifiedRecord(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get records by chunk", e);
        }
        return list;
    }

    /** Count total records for a chunk (union). Excludes player-inventory records. */
    public int countRecordsByChunk(String world, int chunkX, int chunkZ) {
        String sql = """
            SELECT (SELECT COUNT(*) FROM scan_records
                    WHERE world=? AND chunk_x=? AND chunk_z=?
                      AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest'))
                 + (SELECT COUNT(*) FROM monitor_records
                    WHERE world=? AND chunk_x=? AND chunk_z=?
                      AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest'))
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world); ps.setInt(2, chunkX); ps.setInt(3, chunkZ);
            ps.setString(4, world); ps.setInt(5, chunkX); ps.setInt(6, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) { return 0; }
    }

    /**
     * Count actual (non-CLEAN, deduplicated) violations for a chunk.
     * Deduplicates by item_hash (keeps latest record), then excludes CLEAN markers.
     * This gives the true violation count visible in chunk detail views.
     */
    public int countViolationsByChunk(String world, int chunkX, int chunkZ) {
        List<UnifiedRecord> all = getRecordsByChunk(world, chunkX, chunkZ, 1, Integer.MAX_VALUE);
        Map<String, UnifiedRecord> deduped = new LinkedHashMap<>();
        for (UnifiedRecord r : all) {
            String k = r.itemHash();
            UnifiedRecord existing = deduped.get(k);
            if (existing == null || r.scanTime() > existing.scanTime()) {
                deduped.put(k, r);
            }
        }
        int count = 0;
        for (UnifiedRecord r : deduped.values()) {
            if (!"CLEAN".equals(r.severity())) count++;
        }
        return count;
    }

    /** Summary of a chunk with violations (for world/full views). */
    public record ChunkViolationSummary(
            String world, int chunkX, int chunkZ, int recordCount, long latestScanTime
    ) {}

    /**
     * Get distinct chunks that have non-CLEAN violation records in a specific world,
     * ordered by latest scan time descending. Paginated at the DB level.
     */
    public List<ChunkViolationSummary> getDistinctViolationChunks(String worldName, int page, int pageSize) {
        List<ChunkViolationSummary> list = new ArrayList<>();
        String sql = """
            SELECT world, chunk_x, chunk_z, COUNT(*) AS record_count, MAX(scan_time) AS latest_time
            FROM scan_records
            WHERE world = ?
              AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
              AND severity != 'CLEAN'
            GROUP BY world, chunk_x, chunk_z
            ORDER BY latest_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worldName);
            ps.setInt(2, pageSize);
            ps.setInt(3, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ChunkViolationSummary(
                        rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                        rs.getInt("record_count"), rs.getLong("latest_time")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get distinct violation chunks for world: " + worldName, e);
        }
        return list;
    }

    /** Count distinct chunks with non-CLEAN violations in a world. */
    public int countDistinctViolationChunks(String worldName) {
        String sql = """
            SELECT COUNT(DISTINCT chunk_x || ',' || chunk_z)
            FROM scan_records
            WHERE world = ?
              AND container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
              AND severity != 'CLEAN'
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worldName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) { return 0; }
    }

    /**
     * Get distinct chunks with non-CLEAN violations across ALL worlds,
     * ordered by latest scan time descending. Paginated at the DB level.
     */
    public List<ChunkViolationSummary> getDistinctViolationChunksAllWorlds(int page, int pageSize) {
        List<ChunkViolationSummary> list = new ArrayList<>();
        String sql = """
            SELECT world, chunk_x, chunk_z, COUNT(*) AS record_count, MAX(scan_time) AS latest_time
            FROM scan_records
            WHERE container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
              AND severity != 'CLEAN'
            GROUP BY world, chunk_x, chunk_z
            ORDER BY latest_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ChunkViolationSummary(
                        rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                        rs.getInt("record_count"), rs.getLong("latest_time")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get distinct violation chunks across all worlds", e);
        }
        return list;
    }

    /** Count distinct chunks with non-CLEAN violations across all worlds. */
    public int countDistinctViolationChunksAllWorlds() {
        String sql = """
            SELECT COUNT(DISTINCT world || ',' || chunk_x || ',' || chunk_z)
            FROM scan_records
            WHERE container NOT IN ('inventory', 'armor', 'offhand', 'enderchest')
              AND severity != 'CLEAN'
        """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    /** Query records by player (union of scan + monitor). */
    public List<UnifiedRecord> getRecordsByPlayer(String playerUuid, int page, int pageSize) {
        List<UnifiedRecord> list = new ArrayList<>();
        String sql = """
            SELECT id, 'SCAN' AS source, item_hash, scan_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM scan_records WHERE player_uuid = ?
            UNION ALL
            SELECT id, 'MONITOR' AS source, item_hash, event_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM monitor_records WHERE player_uuid = ?
            ORDER BY scan_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setString(2, playerUuid);
            ps.setInt(3, pageSize);
            ps.setInt(4, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapUnifiedRecord(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get records by player", e);
        }
        return list;
    }

    public int countRecordsByPlayer(String playerUuid) {
        String sql = """
            SELECT (SELECT COUNT(*) FROM scan_records WHERE player_uuid=?)
                 + (SELECT COUNT(*) FROM monitor_records WHERE player_uuid=?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setString(2, playerUuid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { return 0; }
    }

    /** Query records by item_hash (union). */
    public List<UnifiedRecord> getRecordsByItem(String itemHash, int page, int pageSize) {
        List<UnifiedRecord> list = new ArrayList<>();
        String sql = """
            SELECT id, 'SCAN' AS source, item_hash, scan_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM scan_records WHERE item_hash = ?
            UNION ALL
            SELECT id, 'MONITOR' AS source, item_hash, event_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM monitor_records WHERE item_hash = ?
            ORDER BY scan_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemHash);
            ps.setString(2, itemHash);
            ps.setInt(3, pageSize);
            ps.setInt(4, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapUnifiedRecord(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get records by item", e);
        }
        return list;
    }

    /** Query records by scan session. */
    public List<UnifiedRecord> getRecordsBySession(int sessionId, int page, int pageSize) {
        List<UnifiedRecord> list = new ArrayList<>();
        String sql = """
            SELECT id, 'SCAN' AS source, item_hash, scan_type,
                   world, chunk_x, chunk_z, player_uuid, player_name,
                   item_slot, container, container_loc, violations, severity, scan_time
            FROM scan_records WHERE session_id = ?
            ORDER BY scan_time DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.setInt(2, pageSize);
            ps.setInt(3, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapUnifiedRecord(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get records by session", e);
        }
        return list;
    }

    public int countRecordsBySession(int sessionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM scan_records WHERE session_id = ?")) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { return 0; }
    }

    /** Record for a player appearing in violation results (for player list view). */
    public record PlayerViolationSummary(String playerUuid, String playerName, int scanCount, int monitorCount, long lastDetected) {}

    /**
     * Get distinct players who have violation records, with counts from both tables.
     * Results sorted by lastDetected descending.
     */
    public List<PlayerViolationSummary> getDistinctViolationPlayers() {
        List<PlayerViolationSummary> list = new ArrayList<>();
        String sql = """
            SELECT player_uuid, player_name,
                   SUM(scan_count) AS scan_total,
                   SUM(monitor_count) AS monitor_total,
                   MAX(last_time) AS last_time
            FROM (
                SELECT player_uuid, MAX(player_name) AS player_name,
                       COUNT(*) AS scan_count, 0 AS monitor_count,
                       MAX(scan_time) AS last_time
                FROM scan_records
                WHERE player_uuid IS NOT NULL
                GROUP BY player_uuid
                UNION ALL
                SELECT player_uuid, MAX(player_name) AS player_name,
                       0 AS scan_count, COUNT(*) AS monitor_count,
                       MAX(scan_time) AS last_time
                FROM monitor_records
                WHERE player_uuid IS NOT NULL
                GROUP BY player_uuid
            ) GROUP BY player_uuid
            ORDER BY last_time DESC
        """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new PlayerViolationSummary(
                        rs.getString("player_uuid"),
                        rs.getString("player_name"),
                        rs.getInt("scan_total"),
                        rs.getInt("monitor_total"),
                        rs.getLong("last_time")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get distinct violation players", e);
        }
        return list;
    }

    /** Get a single unified record by its source + id. */
    public UnifiedRecord getRecordById(String source, long id) {
        String table = source.equalsIgnoreCase("MONITOR") ? "monitor_records" : "scan_records";
        String srcCol = source.equalsIgnoreCase("MONITOR") ? "event_type" : "scan_type";
        String sql = "SELECT id, ? AS source, item_hash, " + srcCol + " AS scan_type, "
                + "world, chunk_x, chunk_z, player_uuid, player_name, "
                + "item_slot, container, container_loc, violations, severity, scan_time "
                + "FROM " + table + " WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, source.toUpperCase());
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapUnifiedRecord(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to get record by id", e);
        }
        return null;
    }

    private UnifiedRecord mapUnifiedRecord(ResultSet rs) throws SQLException {
        int itemSlot = rs.getInt("item_slot");
        return new UnifiedRecord(
                rs.getLong("id"), rs.getString("source"), rs.getString("item_hash"),
                rs.getString("scan_type"), rs.getString("world"),
                rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                rs.getString("player_uuid"), rs.getString("player_name"),
                rs.wasNull() ? null : itemSlot,
                rs.getString("container"), rs.getString("container_loc"),
                rs.getString("violations"), rs.getString("severity"),
                rs.getLong("scan_time"));
    }

    // ==================== Plugin Settings ====================

    public String getSetting(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM plugin_settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) { return null; }
    }

    public CompletableFuture<Void> setSetting(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO plugin_settings (key, value) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to set setting: " + key, e);
            }
        }, dbExecutor);
    }

    // ==================== Whitelist CRUD (unchanged from old) ====================

    public record ItemWhitelistEntry(
            int id, String material, String itemHash, String customNamePattern, String lorePattern,
            String enchantmentsJson, String attributeModifiersJson, long createdAt) {}

    public record AreaWhitelistEntry(
            int id, String world, String areaType,
            int minX, int minZ, int maxX, int maxZ, long createdAt) {}

    public record RegionWhitelistEntry(
            int id, String pluginName, String regionName, String worldName, long createdAt) {}

    public record PlayerWhitelistEntry(
            int id, String playerUuid, String playerName, boolean hidden, long createdAt) {}

    public record WorldWhitelistEntry(
            int id, String worldName, long createdAt) {}

    // --- Item Whitelist ---

    public List<ItemWhitelistEntry> loadItemWhitelist() {
        List<ItemWhitelistEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM item_whitelist ORDER BY id";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(new ItemWhitelistEntry(
                    rs.getInt("id"), rs.getString("material"),
                    rs.getString("item_hash"),
                    rs.getString("custom_name_pattern"), rs.getString("lore_pattern"),
                    rs.getString("enchantments_json"), rs.getString("attribute_modifiers_json"),
                    rs.getLong("created_at")));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load item whitelist", e);
        }
        return list;
    }

    public CompletableFuture<Integer> addItemWhitelistEntry(ItemWhitelistEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO item_whitelist (material, item_hash, custom_name_pattern, lore_pattern, enchantments_json, attribute_modifiers_json, created_at) VALUES (?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, entry.material());
                setStringOrNull(ps, 2, entry.itemHash());
                setStringOrNull(ps, 3, entry.customNamePattern());
                setStringOrNull(ps, 4, entry.lorePattern());
                setStringOrNull(ps, 5, entry.enchantmentsJson());
                setStringOrNull(ps, 6, entry.attributeModifiersJson());
                ps.setLong(7, entry.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) return keys.getInt(1); }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add item whitelist entry", e);
            }
            return -1;
        }, dbExecutor);
    }

    public CompletableFuture<Void> removeItemWhitelistEntry(int id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM item_whitelist WHERE id=?")) {
                ps.setInt(1, id); ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove item whitelist entry", e);
            }
        }, dbExecutor);
    }

    // --- Area Whitelist ---

    public List<AreaWhitelistEntry> loadAreaWhitelist() {
        List<AreaWhitelistEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM area_whitelist ORDER BY id";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(new AreaWhitelistEntry(
                    rs.getInt("id"), rs.getString("world"), rs.getString("area_type"),
                    rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_z"),
                    rs.getLong("created_at")));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load area whitelist", e);
        }
        return list;
    }

    public CompletableFuture<Integer> addAreaWhitelistEntry(AreaWhitelistEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO area_whitelist (world, area_type, min_x, min_z, max_x, max_z, created_at) VALUES (?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, entry.world()); ps.setString(2, entry.areaType());
                ps.setInt(3, entry.minX()); ps.setInt(4, entry.minZ());
                ps.setInt(5, entry.maxX()); ps.setInt(6, entry.maxZ());
                ps.setLong(7, entry.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) return keys.getInt(1); }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add area whitelist entry", e);
            }
            return -1;
        }, dbExecutor);
    }

    public CompletableFuture<Void> removeAreaWhitelistEntry(int id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM area_whitelist WHERE id=?")) {
                ps.setInt(1, id); ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove area whitelist entry", e);
            }
        }, dbExecutor);
    }

    // --- Region Whitelist ---

    public List<RegionWhitelistEntry> loadRegionWhitelist() {
        List<RegionWhitelistEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM region_whitelist ORDER BY id";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(new RegionWhitelistEntry(
                    rs.getInt("id"), rs.getString("plugin_name"), rs.getString("region_name"),
                    rs.getString("world_name"), rs.getLong("created_at")));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load region whitelist", e);
        }
        return list;
    }

    public CompletableFuture<Integer> addRegionWhitelistEntry(RegionWhitelistEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO region_whitelist (plugin_name, region_name, world_name, created_at) VALUES (?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, entry.pluginName()); ps.setString(2, entry.regionName());
                setStringOrNull(ps, 3, entry.worldName()); ps.setLong(4, entry.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) return keys.getInt(1); }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add region whitelist entry", e);
            }
            return -1;
        }, dbExecutor);
    }

    public CompletableFuture<Void> removeRegionWhitelistEntry(int id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM region_whitelist WHERE id=?")) {
                ps.setInt(1, id); ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove region whitelist entry", e);
            }
        }, dbExecutor);
    }

    // --- Player Whitelist ---

    public List<PlayerWhitelistEntry> loadPlayerWhitelist() {
        List<PlayerWhitelistEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM player_whitelist ORDER BY id";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(new PlayerWhitelistEntry(
                    rs.getInt("id"), rs.getString("player_uuid"), rs.getString("player_name"),
                    rs.getInt("hidden") != 0, rs.getLong("created_at")));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player whitelist", e);
        }
        return list;
    }

    public CompletableFuture<Void> addPlayerWhitelistEntry(PlayerWhitelistEntry entry) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_whitelist (player_uuid, player_name, hidden, created_at) VALUES (?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET player_name=excluded.player_name, hidden=excluded.hidden";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.playerUuid()); ps.setString(2, entry.playerName());
                ps.setInt(3, entry.hidden() ? 1 : 0); ps.setLong(4, entry.createdAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add player whitelist entry", e);
            }
        }, dbExecutor);
    }

    public CompletableFuture<Void> removePlayerWhitelistEntry(String playerUuid) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM player_whitelist WHERE player_uuid=?")) {
                ps.setString(1, playerUuid); ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove player whitelist entry", e);
            }
        }, dbExecutor);
    }

    // --- World Whitelist ---

    public List<WorldWhitelistEntry> loadWorldWhitelist() {
        List<WorldWhitelistEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM world_whitelist ORDER BY id";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(new WorldWhitelistEntry(
                    rs.getInt("id"), rs.getString("world_name"), rs.getLong("created_at")));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load world whitelist", e);
        }
        return list;
    }

    public CompletableFuture<Integer> addWorldWhitelistEntry(String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT OR IGNORE INTO world_whitelist (world_name, created_at) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, worldName);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) return keys.getInt(1); }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add world whitelist entry", e);
            }
            return -1;
        }, dbExecutor);
    }

    public CompletableFuture<Void> removeWorldWhitelistEntry(String worldName) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM world_whitelist WHERE world_name=?")) {
                ps.setString(1, worldName); ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove world whitelist entry", e);
            }
        }, dbExecutor);
    }

    // --- Bulk Clear Whitelists ---

    public CompletableFuture<Integer> clearItemWhitelist() {
        return CompletableFuture.supplyAsync(() -> {
            try (Statement stmt = connection.createStatement()) {
                return stmt.executeUpdate("DELETE FROM item_whitelist");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear item whitelist", e);
                return 0;
            }
        }, dbExecutor);
    }

    public CompletableFuture<Integer> clearAreaWhitelistByType(String areaType) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM area_whitelist WHERE area_type = ?")) {
                ps.setString(1, areaType);
                return ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear area whitelist type: " + areaType, e);
                return 0;
            }
        }, dbExecutor);
    }

    public CompletableFuture<Integer> clearRegionWhitelist() {
        return CompletableFuture.supplyAsync(() -> {
            try (Statement stmt = connection.createStatement()) {
                return stmt.executeUpdate("DELETE FROM region_whitelist");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear region whitelist", e);
                return 0;
            }
        }, dbExecutor);
    }

    public CompletableFuture<Integer> clearPlayerWhitelist() {
        return CompletableFuture.supplyAsync(() -> {
            try (Statement stmt = connection.createStatement()) {
                return stmt.executeUpdate("DELETE FROM player_whitelist");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear player whitelist", e);
                return 0;
            }
        }, dbExecutor);
    }

    public CompletableFuture<Integer> clearWorldWhitelist() {
        return CompletableFuture.supplyAsync(() -> {
            try (Statement stmt = connection.createStatement()) {
                return stmt.executeUpdate("DELETE FROM world_whitelist");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear world whitelist", e);
                return 0;
            }
        }, dbExecutor);
    }

    // ==================== Helpers ====================

    private void setStringOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) ps.setString(index, value); else ps.setNull(index, Types.VARCHAR);
    }

    private void setIntOrNull(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value != null) ps.setInt(index, value); else ps.setNull(index, Types.INTEGER);
    }

    public Connection getConnection() { return connection; }

    public ExecutorService getDbExecutor() { return dbExecutor; }

    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database", e);
        }
    }
}
