package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import com.illegalscanner.database.DatabaseManager.ScanRecord;
import com.illegalscanner.validator.ValidationResult;
import com.illegalscanner.validator.Violation;
import com.illegalscanner.whitelist.RegionWhitelistManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Core scan service. Routes all /is scan commands.
 * Phase 2 will fully implement area/res/world/full via chunk decomposition.
 */
public class ScanService {

    private final IllegalScanner plugin;
    private final ChunkScanner chunkScanner;
    private final PlayerScanner playerScanner;
    private final ScanSessionManager sessionManager;

    public ScanService(IllegalScanner plugin) {
        this.plugin = plugin;
        this.chunkScanner = new ChunkScanner(plugin);
        this.playerScanner = new PlayerScanner(plugin, chunkScanner);
        this.sessionManager = new ScanSessionManager(plugin);
    }

    public ChunkScanner getChunkScanner() { return chunkScanner; }
    public PlayerScanner getPlayerScanner() { return playerScanner; }

    // ==================== Chunk Scan ====================

    public int scanChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) {
            plugin.getLogger().warning("Chunk not loaded: " + world.getName() + " " + chunkX + "," + chunkZ);
            return 0;
        }
        return scanChunkInternal(chunk, "chunk", 0);
    }

    /**
     * Scan a chunk and record results under a session.
     * @return number of items flagged
     */
    private int scanChunkInternal(Chunk chunk, String scanType, int sessionId) {
        return chunkScanner.scanChunk(chunk, (item, slot, container, loc, violations, severity) -> {
            recordViolation(item, slot, container, loc, violations, severity,
                    scanType, sessionId, null, null);
        });
    }

    // ==================== Player Scan ====================

    public int scanPlayer(String playerName) {
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return scanOnlinePlayer(onlinePlayer, "player", 0);
        }
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (offline.hasPlayedBefore()) {
            return playerScanner.scanOfflinePlayer(offline, (item, slot, container, loc, violations, severity) -> {
                recordViolation(item, slot, container, loc, violations, severity,
                        "player", 0, offline.getUniqueId().toString(), offline.getName());
            });
        }
        plugin.getLogger().warning("Player not found: " + playerName);
        return 0;
    }

    public int scanOnlinePlayer(Player player, String scanType, int sessionId) {
        return playerScanner.scanOnlinePlayer(player, (item, slot, container, loc, violations, severity) -> {
            recordViolation(item, slot, container, loc, violations, severity,
                    scanType, sessionId, player.getUniqueId().toString(), player.getName());
        });
    }

    // ==================== Area / Res / World / Full ====================

    /**
     * Scan a rectangular area by decomposing it into chunks (runs on main thread).
     */
    public CompletableFuture<Integer> scanArea(String worldName, int x1, int z1, int x2, int z2) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return CompletableFuture.completedFuture(0);
        }

        int minChunkX = Math.min(x1, x2) >> 4;
        int maxChunkX = Math.max(x1, x2) >> 4;
        int minChunkZ = Math.min(z1, z2) >> 4;
        int maxChunkZ = Math.max(z1, z2) >> 4;

        // Build chunk list
        List<long[]> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunks.add(new long[]{cx, cz});
            }
        }

        String target = worldName + " " + x1 + "," + z1 + " ~ " + x2 + "," + z2;
        plugin.getLogger().info("Area scan: " + chunks.size() + " chunks in " + target);

        return sessionManager.createSession("area", target, chunks.size()).thenCompose(sessionId ->
            processSyncBatch(world, chunks, sessionId, "area"));
    }

    /**
     * Scan a named residence/region by decomposing its bounds into chunks.
     */
    public CompletableFuture<Integer> scanRes(String resName) {
        var regionMgr = plugin.getRegionWhitelistManager();
        // Try to find the region bounds — iterate over worlds
        RegionWhitelistManager.RegionBounds bounds = null;
        World foundWorld = null;
        for (World world : plugin.getServer().getWorlds()) {
            for (String pluginName : regionMgr.getAvailablePlugins()) {
                bounds = regionMgr.getRegionBounds(pluginName, resName, world);
                if (bounds != null) {
                    foundWorld = world;
                    break;
                }
            }
            if (bounds != null) break;
        }

        if (bounds == null) {
            plugin.getLogger().warning("Region not found: " + resName);
            return CompletableFuture.completedFuture(0);
        }

        return scanArea(foundWorld.getName(), bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ())
                .thenApply(count -> {
                    plugin.getLogger().info("Res scan complete: " + resName + " — " + count + " violations");
                    return count;
                });
    }

    /**
     * Scan an entire world by enumerating region files and decomposing to chunks (sync-safe).
     */
    public CompletableFuture<Integer> scanWorld(String worldName) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return CompletableFuture.completedFuture(0);
        }

        plugin.getLogger().info("World scan started: " + worldName);

        // Build chunk list from region files (can enumerate on any thread)
        List<long[]> chunks = new ArrayList<>();
        // Add loaded chunks
        for (Chunk chunk : world.getLoadedChunks()) {
            chunks.add(new long[]{chunk.getX(), chunk.getZ()});
        }
        // Enumerate from region files
        java.io.File regionFolder = new java.io.File(world.getWorldFolder(), "region");
        java.io.File[] regionFiles = regionFolder.listFiles((d, n) -> n.endsWith(".mca"));
        if (regionFiles != null) {
            java.util.Set<Long> loadedKeys = new java.util.HashSet<>();
            for (Chunk c : world.getLoadedChunks()) {
                loadedKeys.add(((long) c.getX()) << 32 | (c.getZ() & 0xFFFFFFFFL));
            }
            for (java.io.File rf : regionFiles) {
                String[] parts = rf.getName().split("\\.");
                if (parts.length != 4) continue;
                try {
                    int rx = Integer.parseInt(parts[1]);
                    int rz = Integer.parseInt(parts[2]);
                    for (int dx = 0; dx < 32; dx++) {
                        for (int dz = 0; dz < 32; dz++) {
                            int cx = rx * 32 + dx;
                            int cz = rz * 32 + dz;
                            long key = ((long) cx) << 32 | (cz & 0xFFFFFFFFL);
                            if (!loadedKeys.contains(key)) {
                                chunks.add(new long[]{cx, cz});
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        plugin.getLogger().info("World scan queue: " + chunks.size() + " chunks for " + worldName);
        return sessionManager.createSession("world", worldName, chunks.size()).thenCompose(sessionId ->
            processSyncBatch(world, chunks, sessionId, "world"));
    }

    /**
     * Full scan — scan all worlds sequentially.
     */
    public CompletableFuture<Integer> scanFull() {
        plugin.getLogger().info("Full scan started...");
        return sessionManager.createSession("full", "all worlds", plugin.getServer().getWorlds().size()).thenCompose(sessionId ->
            scanAllWorldsRecursive(plugin.getServer().getWorlds(), 0, sessionId, 0));
    }

    private CompletableFuture<Integer> scanAllWorldsRecursive(
            List<World> worlds, int index, int sessionId, int totalFlagged) {
        if (index >= worlds.size()) {
            sessionManager.completeSession(sessionId, worlds.size(), totalFlagged);
            plugin.getLogger().info("Full scan complete: " + totalFlagged + " violations across all worlds");
            return CompletableFuture.completedFuture(totalFlagged);
        }
        World world = worlds.get(index);
        // Build chunk list for this world
        List<long[]> chunks = new ArrayList<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            chunks.add(new long[]{chunk.getX(), chunk.getZ()});
        }
        java.io.File regionFolder = new java.io.File(world.getWorldFolder(), "region");
        java.io.File[] regionFiles = regionFolder.listFiles((d, n) -> n.endsWith(".mca"));
        if (regionFiles != null) {
            java.util.Set<Long> loadedKeys = new java.util.HashSet<>();
            for (Chunk c : world.getLoadedChunks()) {
                loadedKeys.add(((long) c.getX()) << 32 | (c.getZ() & 0xFFFFFFFFL));
            }
            for (java.io.File rf : regionFiles) {
                String[] parts = rf.getName().split("\\.");
                if (parts.length != 4) continue;
                try {
                    int rx = Integer.parseInt(parts[1]);
                    int rz = Integer.parseInt(parts[2]);
                    for (int dx = 0; dx < 32; dx++) {
                        for (int dz = 0; dz < 32; dz++) {
                            int cx = rx * 32 + dx;
                            int cz = rz * 32 + dz;
                            long key = ((long) cx) << 32 | (cz & 0xFFFFFFFFL);
                            if (!loadedKeys.contains(key)) {
                                chunks.add(new long[]{cx, cz});
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        plugin.getLogger().info("Full scan: world " + world.getName() + " — " + chunks.size() + " chunks");
        return processSyncBatch(world, chunks, sessionId, "full")
                .thenCompose(flagged -> scanAllWorldsRecursive(worlds, index + 1, sessionId, totalFlagged + flagged));
    }

    // ==================== Core Recording ====================

    /**
     * Record a violation to scan_records and item_index.
     */
    private void recordViolation(org.bukkit.inventory.ItemStack item, int slot,
                                  String container, Location loc,
                                  List<Violation> violations, ValidationResult severity,
                                  String scanType, int sessionId,
                                  String playerUuid, String playerName) {
        String itemHash = plugin.getItemHashService().resolve(item);
        if (itemHash == null) return;

        String worldName = loc != null && loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        int chunkX = loc != null ? loc.getBlockX() >> 4 : 0;
        int chunkZ = loc != null ? loc.getBlockZ() >> 4 : 0;
        String containerLoc = loc != null ? loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() : null;

        String violationJson = buildViolationJson(violations);

        ScanRecord record = new ScanRecord(
                0, sessionId > 0 ? sessionId : null, itemHash, scanType,
                worldName, chunkX, chunkZ,
                playerUuid, playerName, slot >= 0 ? slot : null,
                container, containerLoc,
                violationJson, severity.name(),
                System.currentTimeMillis());

        plugin.getDatabaseManager().insertScanRecord(record);

        // Console alert
        if (severity == ValidationResult.ILLEGAL
                && plugin.getConfigManager().getConfig().getBoolean("alerts.console_log", true)) {
            plugin.getLogger().warning("[" + scanType.toUpperCase() + "] ILLEGAL: " + item.getType().name()
                    + (playerName != null ? " from " + playerName : "")
                    + " @ " + (containerLoc != null ? containerLoc : "?")
                    + " - " + violations.size() + " violations");
        }
    }

    private String buildViolationJson(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Violation v : violations) {
            if (!first) sb.append(",");
            sb.append("{\"type\":\"").append(escapeJson(v.type())).append("\",");
            sb.append("\"message\":\"").append(escapeJson(v.message())).append("\",");
            sb.append("\"severity\":\"").append(v.severity().name()).append("\"}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ==================== Sync Batch Processing ====================

    /**
     * Process a list of chunks on the MAIN thread in batches (chunksPerTick per tick)
     * to avoid freezing the server. Loads chunks if needed, scans containers,
     * and completes the future when done.
     */
    private CompletableFuture<Integer> processSyncBatch(World world, List<long[]> chunks,
                                                         int sessionId, String scanType) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        AtomicInteger flagged = new AtomicInteger(0);
        AtomicInteger scanned = new AtomicInteger(0);
        AtomicInteger index = new AtomicInteger(0);

        int chunksPerTick = plugin.getConfigManager().getConfig()
                .getInt("scan.full_scan_chunks_per_tick", 1);

        org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
        taskHolder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int processed = 0;
            while (processed < chunksPerTick && index.get() < chunks.size()) {
                int i = index.getAndIncrement();
                long[] coords = chunks.get(i);
                int cx = (int) coords[0];
                int cz = (int) coords[1];

                try {
                    Chunk chunk = world.getChunkAt(cx, cz);
                    if (!chunk.isLoaded()) {
                        chunk.load();
                    }
                    int f = scanChunkInternal(chunk, scanType, sessionId);
                    flagged.addAndGet(f);
                    scanned.incrementAndGet();
                } catch (Exception e) {
                    plugin.getLogger().fine("Chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                    scanned.incrementAndGet();
                }
                processed++;
            }

            if (index.get() >= chunks.size()) {
                // All done
                taskHolder[0].cancel();
                sessionManager.completeSession(sessionId, scanned.get(), flagged.get());
                plugin.getLogger().info(scanType + " scan complete: " + scanned.get()
                        + " chunks, " + flagged.get() + " violations");
                future.complete(flagged.get());
            }
        }, 1L, 1L); // every tick

        return future;
    }

    public void shutdown() {
        plugin.getLogger().info("Scan service shut down.");
    }
}
