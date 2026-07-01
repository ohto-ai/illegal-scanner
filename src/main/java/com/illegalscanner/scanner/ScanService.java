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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import net.minecraft.core.RegistryAccess;

/**
 * Core scan service. Routes all /is scan commands.
 * Phase 2 will fully implement area/res/world/full via chunk decomposition.
 */
public class ScanService {

    private final IllegalScanner plugin;
    private final ChunkScanner chunkScanner;
    private final PlayerScanner playerScanner;
    private final ScanSessionManager sessionManager;
    private final ChunkScanDedupCache dedupCache;
    private final RegistryAccess registryAccess;
    private final ExecutorService mcaReadPool;

    // Active scan tasks, keyed by session ID
    private final Map<Integer, org.bukkit.scheduler.BukkitTask> activeTasks = new ConcurrentHashMap<>();
    // Current scan indices, keyed by session ID (for pause/resume)
    private final Map<Integer, AtomicInteger> scanIndices = new ConcurrentHashMap<>();
    // Paused scan state, keyed by session ID (for resume)
    private final Map<Integer, PausedScan> pausedScans = new ConcurrentHashMap<>();

    public ScanService(IllegalScanner plugin, ChunkScanDedupCache dedupCache) {
        this.plugin = plugin;
        this.chunkScanner = new ChunkScanner(plugin);
        this.playerScanner = new PlayerScanner(plugin, chunkScanner);
        this.sessionManager = new ScanSessionManager(plugin);
        this.dedupCache = dedupCache;
        this.registryAccess = ((org.bukkit.craftbukkit.CraftServer) plugin.getServer())
                .getServer().registryAccess();
        this.mcaReadPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "illegal-scanner-MCA");
            t.setDaemon(true);
            return t;
        });
    }

    public ChunkScanner getChunkScanner() { return chunkScanner; }
    public PlayerScanner getPlayerScanner() { return playerScanner; }

    // ==================== Chunk Scan ====================

    public int scanChunk(World world, int chunkX, int chunkZ, long commandTime) {
        // Dedup: skip if this chunk was already scanned by a newer command
        if (dedupCache.shouldSkip(world.getName(), chunkX, chunkZ, commandTime)) {
            plugin.getLogger().fine("Skipping chunk " + world.getName() + " " + chunkX + "," + chunkZ
                    + " — already scanned by a newer command");
            return 0;
        }
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) {
            plugin.getLogger().warning("Chunk not loaded: " + world.getName() + " " + chunkX + "," + chunkZ);
            return 0;
        }
        int result = scanChunkInternal(chunk, "chunk", 0);
        dedupCache.markScanned(world.getName(), chunkX, chunkZ, System.currentTimeMillis());
        return result;
    }

    /**
     * Scan a chunk and record results under a session.
     * Deletes old monitor AND scan records for this chunk before scanning —
     * the new scan result is the sole source of truth for this chunk.
     * @return number of items flagged
     */
    private int scanChunkInternal(Chunk chunk, String scanType, int sessionId) {
        String worldName = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        // Delete old records — new scan is authoritative for this chunk
        plugin.getDatabaseManager().deleteMonitorRecordsByChunk(worldName, cx, cz);
        plugin.getDatabaseManager().deleteScanRecordsByChunk(worldName, cx, cz);

        int flagged = chunkScanner.scanChunk(chunk, (item, slot, container, loc, violations, severity) -> {
            recordViolation(item, slot, container, loc, violations, severity,
                    scanType, sessionId, null, null);
        });

        return flagged;
    }

    /**
     * Scan containers extracted from raw MCA chunk NBT (no Bukkit Chunk involved).
     * Deletes old monitor AND scan records for this chunk before scanning —
     * the new scan result is the sole source of truth for this chunk.
     *
     * @return number of items flagged
     */
    private int scanContainersFromNbt(List<McaChunkReader.ContainerFromNbt> containers,
                                       World world, int cx, int cz,
                                       String scanType, int sessionId) {
        String worldName = world.getName();

        // Delete old records BEFORE checking containers —
        // new scan is authoritative for this chunk even if all containers are now empty.
        plugin.getDatabaseManager().deleteMonitorRecordsByChunk(worldName, cx, cz);
        plugin.getDatabaseManager().deleteScanRecordsByChunk(worldName, cx, cz);

        if (containers.isEmpty()) {
            return 0;
        }

        int flagged = 0;
        for (McaChunkReader.ContainerFromNbt container : containers) {
            Location loc = new Location(world, container.worldX(), container.worldY(), container.worldZ());
            for (McaChunkReader.SlotItemNbt slotItem : container.items()) {
                org.bukkit.inventory.ItemStack item = slotItem.item();
                List<Violation> violations = plugin.getValidationEngine().validate(item, loc);
                if (!violations.isEmpty()) {
                    ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);
                    recordViolation(item, slotItem.slot(), container.containerType(), loc,
                            violations, severity, scanType, sessionId, null, null);
                    flagged++;
                }
            }
        }

        return flagged;
    }

    // ==================== Force Scan (bypass dedup) ====================

    /**
     * Scan a chunk bypassing the dedup cache. Used for explicit user-initiated
     * rescans from the GUI. Attempts synchronous chunk load if needed.
     *
     * @param world  the world containing the chunk (from context, not player)
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return number of items flagged, or -1 if the chunk could not be loaded
     */
    public int forceScanChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) {
            // Synchronous load — may cause brief tick lag for large region files
            chunk.load(true);
        }
        if (!chunk.isLoaded()) {
            plugin.getLogger().warning("forceScanChunk: failed to load chunk "
                    + world.getName() + " " + chunkX + "," + chunkZ);
            return -1;
        }
        int result = scanChunkInternal(chunk, "chunk", 0);
        dedupCache.markScanned(world.getName(), chunkX, chunkZ, System.currentTimeMillis());
        return result;
    }

    /**
     * Scan a player by UUID, bypassing name-based resolution. Used for explicit
     * user-initiated rescans from the player view GUI.
     *
     * @param playerUuid the player's UUID (from holder.context)
     * @param playerName cached player name for display
     * @return number of items flagged, or -1 if player data could not be found
     */
    public int forceScanPlayer(java.util.UUID playerUuid, String playerName) {
        // Try online player first (UUID-based, exact match)
        Player online = plugin.getServer().getPlayer(playerUuid);
        if (online != null && online.isOnline()) {
            return scanOnlinePlayer(online, "player", 0);
        }

        // Try offline player
        org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerUuid);
        if (offline.getName() != null && offline.hasPlayedBefore()) {
            plugin.getDatabaseManager().deleteMonitorRecordsByPlayer(playerUuid.toString());
            return playerScanner.scanOfflinePlayer(offline,
                    (item, slot, container, loc, violations, severity) -> {
                        recordViolation(item, slot, container, loc, violations, severity,
                                "player", 0, playerUuid.toString(), playerName);
                    });
        }

        plugin.getLogger().warning("forceScanPlayer: player not found: " + playerUuid);
        return -1;
    }

    // ==================== Player Scan ====================

    public int scanPlayer(String playerName) {
        // Exact match only (no prefix matching)
        Player onlinePlayer = resolveExactPlayer(playerName);
        if (onlinePlayer != null) {
            return scanOnlinePlayer(onlinePlayer, "player", 0);
        }
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (offline.getName() != null && offline.getName().equalsIgnoreCase(playerName)
                && offline.hasPlayedBefore()) {
            // Clean old monitor records — scan replaces all previous monitor state for this player
            plugin.getDatabaseManager().deleteMonitorRecordsByPlayer(offline.getUniqueId().toString());
            return playerScanner.scanOfflinePlayer(offline, (item, slot, container, loc, violations, severity) -> {
                recordViolation(item, slot, container, loc, violations, severity,
                        "player", 0, offline.getUniqueId().toString(), offline.getName());
            });
        }
        plugin.getLogger().warning("Player not found: " + playerName);
        return 0;
    }

    public int scanOnlinePlayer(Player player, String scanType, int sessionId) {
        // Clean old monitor records — scan replaces all previous monitor state for this player
        plugin.getDatabaseManager().deleteMonitorRecordsByPlayer(player.getUniqueId().toString());

        return playerScanner.scanOnlinePlayer(player, (item, slot, container, loc, violations, severity) -> {
            recordViolation(item, slot, container, loc, violations, severity,
                    scanType, sessionId, player.getUniqueId().toString(), player.getName());
        });
    }

    /**
     * Scan a specific online player by name. Only scans if the player is online.
     * @return number of flagged items, or -1 if player is not online
     */
    public int scanOnlinePlayerByName(String playerName) {
        Player p = resolveExactPlayer(playerName);
        if (p == null) return -1;
        return scanOnlinePlayer(p, "player", 0);
    }

    /**
     * Scan a specific offline player by name. Only scans if NOT online.
     * @return number of flagged items, or -1 if no data found
     */
    public int scanOfflinePlayerByName(String playerName) {
        // Don't scan if the player is currently online
        Player online = Bukkit.getPlayer(playerName);
        if (online != null && online.isOnline()) return -1;

        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (!offline.hasPlayedBefore()) return -1;

        // Clean old monitor records — scan replaces all previous monitor state for this player
        plugin.getDatabaseManager().deleteMonitorRecordsByPlayer(offline.getUniqueId().toString());
        return playerScanner.scanOfflinePlayer(offline, (item, slot, container, loc, violations, severity) -> {
            recordViolation(item, slot, container, loc, violations, severity,
                    "player", 0, offline.getUniqueId().toString(), offline.getName());
        });
    }

    /**
     * Scan all online players in a session.
     */
    public CompletableFuture<Integer> scanAllOnlinePlayers() {
        int onlineCount = plugin.getServer().getOnlinePlayers().size();
        return sessionManager.createSession("player", "all online", onlineCount).thenCompose(sessionId -> {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int flagged = 0;
                // Iterate players manually so we can capture UUID/name in the callback closure
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    try {
                        final String puid = player.getUniqueId().toString();
                        final String pname = player.getName();
                        flagged += playerScanner.scanOnlinePlayer(player,
                            (item, slot, container, loc, violations, severity) -> {
                                recordViolation(item, slot, container, loc, violations, severity,
                                        "player", sessionId, puid, pname);
                            });
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Error scanning player " + player.getName(), e);
                    }
                }
                sessionManager.completeSession(sessionId, onlineCount, flagged);
                future.complete(flagged);
            });
            return future;
        });
    }

    /**
     * Scan all offline players (those with .dat files) in a session.
     */
    public CompletableFuture<Integer> scanAllOfflinePlayers() {
        int estimated = estimateOfflinePlayerCount();
        return sessionManager.createSession("player", "all offline", estimated).thenCompose(sessionId -> {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int flagged = 0;
                java.util.Set<java.util.UUID> scanned = new java.util.HashSet<>();
                for (World world : plugin.getServer().getWorlds()) {
                    java.io.File playerDataFolder = new java.io.File(
                            world.getWorldFolder(), "playerdata");
                    if (!playerDataFolder.exists() || !playerDataFolder.isDirectory()) continue;
                    java.io.File[] playerFiles = playerDataFolder.listFiles(
                            (dir, name) -> name.endsWith(".dat"));
                    if (playerFiles == null) continue;
                    for (java.io.File file : playerFiles) {
                        try {
                            String uuidStr = file.getName().replace(".dat", "");
                            java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
                            if (!scanned.add(uuid)) continue;
                            org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
                            final String puid = uuid.toString();
                            final String pname = offline.getName() != null ? offline.getName() : uuidStr;
                            // Clean old monitor records — scan replaces all previous monitor state
                            plugin.getDatabaseManager().deleteMonitorRecordsByPlayer(puid);
                            flagged += playerScanner.scanOfflinePlayer(offline,
                                (item, slot, container, loc, violations, severity) -> {
                                    recordViolation(item, slot, container, loc, violations, severity,
                                            "player", sessionId, puid, pname);
                                });
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Error scanning offline player file: " + file.getName(), e);
                        }
                    }
                }
                sessionManager.completeSession(sessionId, estimated, flagged);
                future.complete(flagged);
            });
            return future;
        });
    }

    private int estimateOfflinePlayerCount() {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            java.io.File playerDataFolder = new java.io.File(world.getWorldFolder(), "playerdata");
            if (playerDataFolder.exists()) {
                java.io.File[] files = playerDataFolder.listFiles((d, n) -> n.endsWith(".dat"));
                if (files != null) count = Math.max(count, files.length);
            }
        }
        return count;
    }

    // ==================== Area / Res / World ====================

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

        long commandTime = System.currentTimeMillis();
        String target = worldName + " " + x1 + "," + z1 + " ~ " + x2 + "," + z2;
        plugin.getLogger().info("Area scan: " + chunks.size() + " chunks in " + target);

        return sessionManager.createSession("area", target, chunks.size()).thenCompose(sessionId ->
            processSyncBatch(world, chunks, sessionId, "area", commandTime));
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
     * Scan a world with the given mode.
     *
     * @param worldName the world to scan
     * @param mode      "loaded" (only loaded chunks), "unloaded" (only region-file chunks not loaded),
     *                  or "all" (both, default)
     */
    public CompletableFuture<Integer> scanWorld(String worldName, String mode) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return CompletableFuture.completedFuture(0);
        }

        String effectiveMode = (mode == null || mode.isEmpty()) ? "all" : mode;
        plugin.getLogger().info("World scan started: " + worldName + " (mode=" + effectiveMode + ")");

        long commandTime = System.currentTimeMillis();
        List<long[]> chunks = buildWorldChunkList(world, effectiveMode);

        plugin.getLogger().info("World scan queue: " + chunks.size() + " chunks for " + worldName);
        return sessionManager.createSession("world", worldName, chunks.size()).thenCompose(sessionId ->
            processSyncBatch(world, chunks, sessionId, "world", commandTime));
    }

    /**
     * Build a chunk coordinate list for a world based on scan mode.
     */
    private List<long[]> buildWorldChunkList(World world, String mode) {
        List<long[]> chunks = new ArrayList<>();
        boolean includeLoaded = mode.equals("loaded") || mode.equals("all");
        boolean includeUnloaded = mode.equals("unloaded") || mode.equals("all");

        if (includeLoaded) {
            for (Chunk chunk : world.getLoadedChunks()) {
                chunks.add(new long[]{chunk.getX(), chunk.getZ()});
            }
        }

        if (includeUnloaded) {
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
        }

        return chunks;
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
                                                         int sessionId, String scanType,
                                                         long commandTime) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        AtomicInteger flagged = new AtomicInteger(0);
        AtomicInteger scanned = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger index = new AtomicInteger(0);

        int chunksPerTick = plugin.getConfigManager().getConfig()
                .getInt("scan.scan_chunks_per_tick", 1);

        final String worldName = world.getName();

        // Pending retry queue for chunks that are being async-loaded (legacy path only)
        List<long[]> pendingChunks = new ArrayList<>();
        // In-flight async MCA reads
        List<McaReadFuture> inFlightFutures = new ArrayList<>();

        // Store state for pause/resume
        scanIndices.put(sessionId, index);
        pausedScans.put(sessionId, new PausedScan(world, null, chunks, 0,
                scanned, flagged, skipped, scanType, commandTime, pendingChunks, false, 0, inFlightFutures));

        org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
        int[] tickCount = new int[1]; // throttle progress updates to every 20 ticks (1 sec)
        taskHolder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!activeTasks.containsKey(sessionId)) {
                // Scan was stopped externally
                taskHolder[0].cancel();
                scanIndices.remove(sessionId);
                if (!future.isDone()) future.complete(flagged.get());
                return;
            }

            boolean mcaDirect = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.mca_direct_read", true);
            boolean sf = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_item_frames", true);
            boolean sa = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_armor_stands", true);
            boolean sm = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_minecart_containers", true);
            boolean scb = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_chest_boats", true);
            boolean see = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_entity_equipment", true);
            int asyncWindow = plugin.getConfigManager().getConfig()
                    .getInt("scan.mca_async_window", 40);
            boolean mainPassDone = index.get() >= chunks.size();

            // ---- Step 1: Drain completed async MCA futures ----
            List<McaReadFuture> completed = drainCompletedFutures(inFlightFutures);
            for (McaReadFuture mf : completed) {
                int fcx = mf.cx();
                int fcz = mf.cz();
                try {
                    List<McaChunkReader.ContainerFromNbt> containers = mf.future().get();
                    int f = scanContainersFromNbt(containers, world, fcx, fcz, scanType, sessionId);
                    flagged.addAndGet(f);
                } catch (Exception e) {
                    plugin.getLogger().warning("MCA read failed for chunk " + fcx + "," + fcz
                            + " in " + worldName + ": " + e.getMessage());
                }
                dedupCache.markScanned(worldName, fcx, fcz, System.currentTimeMillis());
                scanned.incrementAndGet();
            }

            if (!mainPassDone) {
                if (mcaDirect) {
                    // ---- Async pipeline: submit MCA reads, keep window full ----
                    while (inFlightFutures.size() < asyncWindow && index.get() < chunks.size()) {
                        int i = index.getAndIncrement();
                        long[] coords = chunks.get(i);
                        int cx = (int) coords[0];
                        int cz = (int) coords[1];

                        if (dedupCache.shouldSkip(worldName, cx, cz, commandTime)) {
                            skipped.incrementAndGet();
                            scanned.incrementAndGet();
                            continue;
                        }

                        if (world.isChunkLoaded(cx, cz)) {
                            // Already loaded: process synchronously
                            try {
                                Chunk chunk = world.getChunkAt(cx, cz);
                                int f = scanChunkInternal(chunk, scanType, sessionId);
                                flagged.addAndGet(f);
                            } catch (Exception e) {
                                plugin.getLogger().fine("Chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                            }
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            scanned.incrementAndGet();
                        } else {
                            // Unloaded: submit MCA read to background thread pool
                            CompletableFuture<List<McaChunkReader.ContainerFromNbt>> mcaFuture =
                                    CompletableFuture.supplyAsync(
                                        () -> McaChunkReader.readChunkItems(world.getWorldFolder(),
                                                cx, cz, registryAccess, sf, sa, sm, scb, see),
                                        mcaReadPool);
                            inFlightFutures.add(new McaReadFuture(mcaFuture, cx, cz));
                        }
                    }
                } else {
                    // ---- Legacy sync path (mca_direct_read=false) ----
                    int processed = 0;
                    while (processed < chunksPerTick && index.get() < chunks.size()) {
                        int i = index.getAndIncrement();
                        long[] coords = chunks.get(i);
                        int cx = (int) coords[0];
                        int cz = (int) coords[1];

                        if (dedupCache.shouldSkip(worldName, cx, cz, commandTime)) {
                            skipped.incrementAndGet();
                            scanned.incrementAndGet();
                            processed++;
                            continue;
                        }

                        try {
                            Chunk chunk = world.getChunkAt(cx, cz);
                            if (!chunk.isLoaded()) {
                                chunk.load();
                                pendingChunks.add(new long[]{cx, cz});
                                continue;
                            }
                            int f = scanChunkInternal(chunk, scanType, sessionId);
                            flagged.addAndGet(f);
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            scanned.incrementAndGet();
                        } catch (Exception e) {
                            plugin.getLogger().fine("Chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                            scanned.incrementAndGet();
                        }
                        processed++;
                    }
                }
            } else {
                // ---- Phase 2: drain pending queue (legacy path only) ----
                int retryIdx = 0;
                int processed = 0;
                while (processed < chunksPerTick && retryIdx < pendingChunks.size()) {
                    long[] coords = pendingChunks.get(retryIdx);
                    int cx = (int) coords[0];
                    int cz = (int) coords[1];

                    try {
                        Chunk chunk = world.getChunkAt(cx, cz);
                        if (!chunk.isLoaded()) {
                            chunk.load(true);
                        }
                        if (chunk.isLoaded()) {
                            int f = scanChunkInternal(chunk, scanType, sessionId);
                            flagged.addAndGet(f);
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            pendingChunks.remove(retryIdx);
                        } else {
                            pendingChunks.remove(retryIdx);
                            scanned.incrementAndGet();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Retry chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                        pendingChunks.remove(retryIdx);
                        scanned.incrementAndGet();
                    }
                    processed++;
                }
            }

            // Update progress in DB every 20 ticks (1 second) to reduce DB pressure
            if (++tickCount[0] % 20 == 0) {
                sessionManager.updateProgress(sessionId, scanned.get(), flagged.get());
            }

            if (index.get() >= chunks.size() && inFlightFutures.isEmpty() && pendingChunks.isEmpty()) {
                // All done — flush final progress
                sessionManager.updateProgress(sessionId, scanned.get(), flagged.get());
                taskHolder[0].cancel();
                activeTasks.remove(sessionId);
                scanIndices.remove(sessionId);
                pausedScans.remove(sessionId);
                sessionManager.completeSession(sessionId, scanned.get(), flagged.get());
                String dedupMsg = skipped.get() > 0 ? " (" + skipped.get() + " skipped — already scanned)" : "";
                plugin.getLogger().info(scanType + " scan complete: " + scanned.get()
                        + " chunks, " + flagged.get() + " violations" + dedupMsg);
                future.complete(flagged.get());
            }
        }, 1L, 1L); // every tick

        activeTasks.put(sessionId, taskHolder[0]);
        return future;
    }

    /**
     * Scan all worlds (loaded chunks only for performance).
     */
    public CompletableFuture<Integer> scanFull() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) return CompletableFuture.completedFuture(0);

        // Build chunk list across all worlds
        List<long[]> allChunks = new ArrayList<>();
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                // Encode world index + chunk coords: worldIndex in high 32 bits, cx/cz in lower
                int worldIdx = worlds.indexOf(world);
                allChunks.add(new long[]{worldIdx, chunk.getX(), chunk.getZ()});
            }
        }

        if (allChunks.isEmpty()) return CompletableFuture.completedFuture(0);

        long commandTime = System.currentTimeMillis();
        String target = allChunks.size() + " chunks across " + worlds.size() + " worlds";
        plugin.getLogger().info("Full scan: " + target);

        return sessionManager.createSession("full", target, allChunks.size()).thenCompose(sessionId -> {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            AtomicInteger flagged = new AtomicInteger(0);
            AtomicInteger scanned = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicInteger index = new AtomicInteger(0);

            int chunksPerTick = plugin.getConfigManager().getConfig()
                    .getInt("scan.scan_chunks_per_tick", 1);

            // Store state for pause/resume
            scanIndices.put(sessionId, index);
            List<World> worldsCopy = new ArrayList<>(worlds); // capture for pause state
            List<long[]> fullPendingChunks = new ArrayList<>();
            pausedScans.put(sessionId, new PausedScan(null, worldsCopy, allChunks, 0,
                    scanned, flagged, skipped, "full", commandTime, fullPendingChunks, false, 0, new ArrayList<>()));

            org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
            int[] tickCount = new int[1]; // throttle progress updates
            taskHolder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (!activeTasks.containsKey(sessionId)) {
                    // Scan was stopped externally
                    taskHolder[0].cancel();
                    scanIndices.remove(sessionId);
                    if (!future.isDone()) future.complete(flagged.get());
                    return;
                }

                int processed = 0;
                boolean mainPassDone = index.get() >= allChunks.size();

                if (!mainPassDone) {
                    // ---- Phase 1: scan loaded chunks with Bukkit, unloaded via MCA direct read ----
                    boolean mcaDirect = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.mca_direct_read", true);
                    boolean sf = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.scan_item_frames", true);
                    boolean sa = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.scan_armor_stands", true);
                    boolean sm = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.scan_minecart_containers", true);
                    boolean scb = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.scan_chest_boats", true);
                    boolean see = plugin.getConfigManager().getConfig()
                            .getBoolean("scan.scan_entity_equipment", true);

                    while (processed < chunksPerTick && index.get() < allChunks.size()) {
                        int i = index.getAndIncrement();
                        long[] tuple = allChunks.get(i);
                        int worldIdx = (int) tuple[0];
                        int cx = (int) tuple[1];
                        int cz = (int) tuple[2];

                        try {
                            World world = worlds.get(worldIdx);
                            String wName = world.getName();

                            if (dedupCache.shouldSkip(wName, cx, cz, commandTime)) {
                                skipped.incrementAndGet();
                                scanned.incrementAndGet();
                                processed++;
                                continue;
                            }

                            if (world.isChunkLoaded(cx, cz)) {
                                // Already loaded — safe to use Bukkit API
                                Chunk chunk = world.getChunkAt(cx, cz);
                                int f = scanChunkInternal(chunk, "full", sessionId);
                                flagged.addAndGet(f);
                                dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                            } else if (mcaDirect) {
                                // MCA direct read — zero chunk load, zero worldgen
                                List<McaChunkReader.ContainerFromNbt> containers =
                                        McaChunkReader.readChunkItems(world.getWorldFolder(), cx, cz,
                                                registryAccess, sf, sa, sm, scb, see);
                                int f = scanContainersFromNbt(containers, world, cx, cz,
                                        "full", sessionId);
                                flagged.addAndGet(f);
                                dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                            } else {
                                // Legacy path (mca_direct_read=false)
                                Chunk chunk = world.getChunkAt(cx, cz);
                                if (!chunk.isLoaded()) {
                                    chunk.load(); // async, non-blocking
                                    fullPendingChunks.add(new long[]{worldIdx, cx, cz});
                                    continue; // don't count as processed
                                }
                                int f = scanChunkInternal(chunk, "full", sessionId);
                                flagged.addAndGet(f);
                                dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                            }
                        } catch (Exception e) {
                            plugin.getLogger().fine("Full scan chunk failed: " + e.getMessage());
                        }
                        scanned.incrementAndGet();
                        processed++;
                    }
                } else {
                    // ---- Phase 2: drain pending queue, force-load stubborn chunks ----
                    int retryIdx = 0;
                    while (processed < chunksPerTick && retryIdx < fullPendingChunks.size()) {
                        long[] tuple = fullPendingChunks.get(retryIdx);
                        int worldIdx = (int) tuple[0];
                        int cx = (int) tuple[1];
                        int cz = (int) tuple[2];

                        try {
                            World world = worlds.get(worldIdx);
                            String wName = world.getName();

                            Chunk chunk = world.getChunkAt(cx, cz);
                            if (!chunk.isLoaded()) {
                                chunk.load(true); // fallback sync load
                            }
                            if (chunk.isLoaded()) {
                                int f = scanChunkInternal(chunk, "full", sessionId);
                                flagged.addAndGet(f);
                                dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                                fullPendingChunks.remove(retryIdx);
                            } else {
                                fullPendingChunks.remove(retryIdx);
                                scanned.incrementAndGet();
                            }
                        } catch (Exception e) {
                            plugin.getLogger().fine("Full scan retry failed: " + e.getMessage());
                            fullPendingChunks.remove(retryIdx);
                            scanned.incrementAndGet();
                        }
                        processed++;
                    }
                }

                // Update progress in DB every 20 ticks (1 second) to reduce DB pressure
                if (++tickCount[0] % 20 == 0) {
                    sessionManager.updateProgress(sessionId, scanned.get(), flagged.get());
                }

                if (index.get() >= allChunks.size() && fullPendingChunks.isEmpty()) {
                    // Flush final progress
                    sessionManager.updateProgress(sessionId, scanned.get(), flagged.get());
                    taskHolder[0].cancel();
                    activeTasks.remove(sessionId);
                    scanIndices.remove(sessionId);
                    pausedScans.remove(sessionId);
                    sessionManager.completeSession(sessionId, scanned.get(), flagged.get());
                    String dedupMsg = skipped.get() > 0 ? " (" + skipped.get() + " skipped — already scanned)" : "";
                    plugin.getLogger().info("Full scan complete: " + scanned.get()
                            + " chunks, " + flagged.get() + " violations" + dedupMsg);
                    future.complete(flagged.get());
                }
            }, 1L, 1L);

            activeTasks.put(sessionId, taskHolder[0]);
            return future;
        });
    }

    /** Resolve a player by exact name (case-insensitive). Unlike Bukkit.getPlayer() this does NOT do prefix matching. */
    private Player resolveExactPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    // ==================== Scan Control (Pause / Resume / Stop / Restart) ====================

    /**
     * Tracks an in-flight async MCA read with its chunk coordinates.
     */
    private record McaReadFuture(
            CompletableFuture<List<McaChunkReader.ContainerFromNbt>> future,
            int cx, int cz
    ) {}

    /**
     * Drain all completed futures from the in-flight list.
     * Completed exceptionally futures are returned too (caller checks with .isCompletedExceptionally()).
     */
    private static List<McaReadFuture> drainCompletedFutures(List<McaReadFuture> futures) {
        List<McaReadFuture> completed = new ArrayList<>();
        Iterator<McaReadFuture> it = futures.iterator();
        while (it.hasNext()) {
            McaReadFuture mf = it.next();
            if (mf.future().isDone()) {
                completed.add(mf);
                it.remove();
            }
        }
        return completed;
    }

    /**
     * Holds the state needed to resume a paused chunk-based scan.
     */
    private static class PausedScan {
        final World world;          // for area/res/world scans (null for full scans)
        final List<World> worlds;   // for full scans (null otherwise)
        final List<long[]> chunks;  // remaining chunks to process
        final int resumeIndex;
        final AtomicInteger scanned;
        final AtomicInteger flagged;
        final AtomicInteger skipped;
        final String scanType;
        final long commandTime;
        final List<long[]> pendingChunks;  // chunks waiting for async load retry (legacy path)
        final boolean mainPassDone;        // true if main list exhausted (in retry phase)
        final int asyncSubmitIndex;        // next chunk index to submit (async MCA pipeline)
        final List<McaReadFuture> asyncFutures; // in-flight MCA reads (async pipeline)

        PausedScan(World world, List<World> worlds, List<long[]> chunks, int resumeIndex,
                   AtomicInteger scanned, AtomicInteger flagged, AtomicInteger skipped,
                   String scanType, long commandTime,
                   List<long[]> pendingChunks, boolean mainPassDone,
                   int asyncSubmitIndex, List<McaReadFuture> asyncFutures) {
            this.world = world;
            this.worlds = worlds;
            this.chunks = chunks;
            this.resumeIndex = resumeIndex;
            this.scanned = scanned;
            this.flagged = flagged;
            this.skipped = skipped;
            this.scanType = scanType;
            this.commandTime = commandTime;
            this.pendingChunks = pendingChunks;
            this.mainPassDone = mainPassDone;
            this.asyncSubmitIndex = asyncSubmitIndex;
            this.asyncFutures = asyncFutures;
        }
    }

    /**
     * Pause a running scan session.
     * @return future that completes when the DB status has been updated to PAUSED.
     */
    public CompletableFuture<Void> pauseScan(int sessionId) {
        org.bukkit.scheduler.BukkitTask task = activeTasks.remove(sessionId);
        if (task != null) {
            // Save current progress
            AtomicInteger index = scanIndices.remove(sessionId);
            PausedScan paused = pausedScans.remove(sessionId); // get the state saved by processSyncBatch
            if (paused != null && index != null) {
                // Update the resume index to current position
                boolean mainDone = index.get() >= paused.chunks.size();
                PausedScan updated = new PausedScan(paused.world, paused.worlds, paused.chunks,
                        index.get(), paused.scanned, paused.flagged, paused.skipped,
                        paused.scanType, paused.commandTime,
                        paused.pendingChunks, mainDone,
                        paused.asyncSubmitIndex, paused.asyncFutures);
                pausedScans.put(sessionId, updated);
            }
            task.cancel();
            plugin.getLogger().info("Scan session " + sessionId + " paused at index " +
                    (paused != null ? paused.resumeIndex : "?"));
            return sessionManager.pauseSession(sessionId);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Resume a paused scan session.
     * If the in-memory paused state was lost (e.g. server restart), the old session is
     * stopped and a new scan is started with the same parameters.
     * @return future that completes when the DB status has been updated and the scan has started.
     */
    public CompletableFuture<Void> resumeScan(int sessionId) {
        PausedScan paused = pausedScans.remove(sessionId);
        if (paused == null) {
            // In-memory state lost — most likely from a server restart.
            // Stop the old session (unrecoverable) and restart from original parameters.
            plugin.getLogger().warning("No paused state found for session " + sessionId
                    + " — in-memory state was lost (server restart?). "
                    + "Stopping old session and restarting scan from scratch.");
            // First stop the old session, then restart with same parameters on main thread
            return sessionManager.stopSession(sessionId).thenRun(() -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    restartScan(sessionId);
                });
            });
        }

        // First update DB status to RUNNING, then start the scan on main thread
        return sessionManager.resumeSession(sessionId).thenRun(() -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (paused.worlds != null) {
                    resumeFullScan(sessionId, paused);
                } else {
                    resumeChunkScan(sessionId, paused);
                }
                plugin.getLogger().info("Scan session " + sessionId + " resumed from index " + paused.resumeIndex);
            });
        });
    }

    /** Resume a standard chunk-based scan (area/res/world). */
    private void resumeChunkScan(int sessionId, PausedScan paused) {
        AtomicInteger index = new AtomicInteger(paused.resumeIndex);
        scanIndices.put(sessionId, index);
        pausedScans.put(sessionId, paused); // allow pause again

        int chunksPerTick = plugin.getConfigManager().getConfig()
                .getInt("scan.scan_chunks_per_tick", 1);
        final String worldName = paused.world.getName();

        org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
        int[] tickCount = new int[1]; // throttle progress updates
        List<McaReadFuture> inFlightFutures = paused.asyncFutures;

        taskHolder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!activeTasks.containsKey(sessionId)) {
                // Scan was stopped externally
                taskHolder[0].cancel();
                return;
            }

            boolean mcaDirect = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.mca_direct_read", true);
            boolean sf = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_item_frames", true);
            boolean sa = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_armor_stands", true);
            boolean sm = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_minecart_containers", true);
            boolean scb = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_chest_boats", true);
            boolean see = plugin.getConfigManager().getConfig()
                    .getBoolean("scan.scan_entity_equipment", true);
            int asyncWindow = plugin.getConfigManager().getConfig()
                    .getInt("scan.mca_async_window", 40);
            boolean mainPassDone = paused.mainPassDone || index.get() >= paused.chunks.size();

            // ---- Step 1: Drain completed async MCA futures ----
            List<McaReadFuture> completed = drainCompletedFutures(inFlightFutures);
            for (McaReadFuture mf : completed) {
                int fcx = mf.cx();
                int fcz = mf.cz();
                try {
                    List<McaChunkReader.ContainerFromNbt> containers = mf.future().get();
                    int f = scanContainersFromNbt(containers, paused.world, fcx, fcz,
                            paused.scanType, sessionId);
                    paused.flagged.addAndGet(f);
                } catch (Exception e) {
                    plugin.getLogger().warning("MCA read failed for chunk " + fcx + "," + fcz
                            + " in " + worldName + ": " + e.getMessage());
                }
                dedupCache.markScanned(worldName, fcx, fcz, System.currentTimeMillis());
                paused.scanned.incrementAndGet();
            }

            if (!mainPassDone) {
                if (mcaDirect) {
                    // ---- Async pipeline: submit MCA reads, keep window full ----
                    while (inFlightFutures.size() < asyncWindow && index.get() < paused.chunks.size()) {
                        int i = index.getAndIncrement();
                        long[] coords = paused.chunks.get(i);
                        int cx = (int) coords[0];
                        int cz = (int) coords[1];

                        if (dedupCache.shouldSkip(worldName, cx, cz, paused.commandTime)) {
                            paused.skipped.incrementAndGet();
                            paused.scanned.incrementAndGet();
                            continue;
                        }

                        if (paused.world.isChunkLoaded(cx, cz)) {
                            try {
                                Chunk chunk = paused.world.getChunkAt(cx, cz);
                                int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                                paused.flagged.addAndGet(f);
                            } catch (Exception e) {
                                plugin.getLogger().fine("Chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                            }
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            paused.scanned.incrementAndGet();
                        } else {
                            CompletableFuture<List<McaChunkReader.ContainerFromNbt>> mcaFuture =
                                    CompletableFuture.supplyAsync(
                                        () -> McaChunkReader.readChunkItems(paused.world.getWorldFolder(),
                                                cx, cz, registryAccess, sf, sa, sm, scb, see),
                                        mcaReadPool);
                            inFlightFutures.add(new McaReadFuture(mcaFuture, cx, cz));
                        }
                    }
                } else {
                    // ---- Legacy sync path (mca_direct_read=false) ----
                    int processed = 0;
                    while (processed < chunksPerTick && index.get() < paused.chunks.size()) {
                        int i = index.getAndIncrement();
                        long[] coords = paused.chunks.get(i);
                        int cx = (int) coords[0];
                        int cz = (int) coords[1];

                        if (dedupCache.shouldSkip(worldName, cx, cz, paused.commandTime)) {
                            paused.skipped.incrementAndGet();
                            paused.scanned.incrementAndGet();
                            processed++;
                            continue;
                        }

                        try {
                            Chunk chunk = paused.world.getChunkAt(cx, cz);
                            if (!chunk.isLoaded()) {
                                chunk.load();
                                paused.pendingChunks.add(new long[]{cx, cz});
                                continue;
                            }
                            int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            paused.scanned.incrementAndGet();
                        } catch (Exception e) {
                            plugin.getLogger().fine("Chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                            paused.scanned.incrementAndGet();
                        }
                        processed++;
                    }
                }
            } else {
                // ---- Phase 2: drain pending queue (legacy path only) ----
                int retryIdx = 0;
                int legProcessed = 0;
                while (legProcessed < chunksPerTick && retryIdx < paused.pendingChunks.size()) {
                    long[] coords = paused.pendingChunks.get(retryIdx);
                    int cx = (int) coords[0];
                    int cz = (int) coords[1];

                    try {
                        Chunk chunk = paused.world.getChunkAt(cx, cz);
                        if (!chunk.isLoaded()) {
                            chunk.load(true);
                        }
                        if (chunk.isLoaded()) {
                            int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(worldName, cx, cz, System.currentTimeMillis());
                            paused.pendingChunks.remove(retryIdx);
                        } else {
                            paused.pendingChunks.remove(retryIdx);
                            paused.scanned.incrementAndGet();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Retry chunk scan failed (" + cx + "," + cz + "): " + e.getMessage());
                        paused.pendingChunks.remove(retryIdx);
                        paused.scanned.incrementAndGet();
                    }
                    legProcessed++;
                }
            }

            // Update progress in DB every 20 ticks (1 second)
            if (++tickCount[0] % 20 == 0) {
                sessionManager.updateProgress(sessionId, paused.scanned.get(), paused.flagged.get());
            }

            if (index.get() >= paused.chunks.size() && inFlightFutures.isEmpty() && paused.pendingChunks.isEmpty()) {
                // Flush final progress
                sessionManager.updateProgress(sessionId, paused.scanned.get(), paused.flagged.get());
                taskHolder[0].cancel();
                activeTasks.remove(sessionId);
                scanIndices.remove(sessionId);
                sessionManager.completeSession(sessionId, paused.scanned.get(), paused.flagged.get());
                String dedupMsg = paused.skipped.get() > 0 ? " (" + paused.skipped.get() + " skipped)" : "";
                plugin.getLogger().info(paused.scanType + " scan complete: " + paused.scanned.get()
                        + " chunks, " + paused.flagged.get() + " violations" + dedupMsg);
            }
        }, 1L, 1L);

        activeTasks.put(sessionId, taskHolder[0]);
    }

    /** Resume a full scan from paused state. */
    private void resumeFullScan(int sessionId, PausedScan paused) {
        AtomicInteger index = new AtomicInteger(paused.resumeIndex);
        scanIndices.put(sessionId, index);
        pausedScans.put(sessionId, paused); // allow pause again

        int chunksPerTick = plugin.getConfigManager().getConfig()
                .getInt("scan.scan_chunks_per_tick", 1);

        org.bukkit.scheduler.BukkitTask[] taskHolder = new org.bukkit.scheduler.BukkitTask[1];
        int[] tickCount = new int[1]; // throttle progress updates
        taskHolder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!activeTasks.containsKey(sessionId)) {
                taskHolder[0].cancel();
                return;
            }

            int processed = 0;
            boolean mainPassDone = paused.mainPassDone || index.get() >= paused.chunks.size();

            if (!mainPassDone) {
                // ---- Phase 1: scan loaded chunks with Bukkit, unloaded via MCA direct read ----
                boolean mcaDirect = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.mca_direct_read", true);
                boolean sf = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.scan_item_frames", true);
                boolean sa = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.scan_armor_stands", true);
                boolean sm = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.scan_minecart_containers", true);
                boolean scb = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.scan_chest_boats", true);
                boolean see = plugin.getConfigManager().getConfig()
                        .getBoolean("scan.scan_entity_equipment", true);

                while (processed < chunksPerTick && index.get() < paused.chunks.size()) {
                    int i = index.getAndIncrement();
                    long[] tuple = paused.chunks.get(i);
                    int worldIdx = (int) tuple[0];
                    int cx = (int) tuple[1];
                    int cz = (int) tuple[2];

                    try {
                        World world = paused.worlds.get(worldIdx);
                        String wName = world.getName();

                        if (dedupCache.shouldSkip(wName, cx, cz, paused.commandTime)) {
                            paused.skipped.incrementAndGet();
                            paused.scanned.incrementAndGet();
                            processed++;
                            continue;
                        }

                        if (world.isChunkLoaded(cx, cz)) {
                            // Already loaded — safe to use Bukkit API
                            Chunk chunk = world.getChunkAt(cx, cz);
                            int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                        } else if (mcaDirect) {
                            // MCA direct read — zero chunk load, zero worldgen
                            List<McaChunkReader.ContainerFromNbt> containers =
                                    McaChunkReader.readChunkItems(world.getWorldFolder(), cx, cz,
                                            registryAccess, sf, sa, sm, scb, see);
                            int f = scanContainersFromNbt(containers, world, cx, cz,
                                    paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                        } else {
                            // Legacy path (mca_direct_read=false)
                            Chunk chunk = world.getChunkAt(cx, cz);
                            if (!chunk.isLoaded()) {
                                chunk.load(); // async, non-blocking
                                paused.pendingChunks.add(new long[]{worldIdx, cx, cz});
                                continue; // don't count as processed
                            }
                            int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Full scan chunk failed: " + e.getMessage());
                    }
                    paused.scanned.incrementAndGet();
                    processed++;
                }
            } else {
                // ---- Phase 2: drain pending queue, force-load stubborn chunks ----
                int retryIdx = 0;
                while (processed < chunksPerTick && retryIdx < paused.pendingChunks.size()) {
                    long[] tuple = paused.pendingChunks.get(retryIdx);
                    int worldIdx = (int) tuple[0];
                    int cx = (int) tuple[1];
                    int cz = (int) tuple[2];

                    try {
                        World world = paused.worlds.get(worldIdx);
                        String wName = world.getName();

                        Chunk chunk = world.getChunkAt(cx, cz);
                        if (!chunk.isLoaded()) {
                            chunk.load(true); // fallback sync load
                        }
                        if (chunk.isLoaded()) {
                            int f = scanChunkInternal(chunk, paused.scanType, sessionId);
                            paused.flagged.addAndGet(f);
                            dedupCache.markScanned(wName, cx, cz, System.currentTimeMillis());
                            paused.pendingChunks.remove(retryIdx);
                        } else {
                            paused.pendingChunks.remove(retryIdx);
                            paused.scanned.incrementAndGet();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Full scan retry failed: " + e.getMessage());
                        paused.pendingChunks.remove(retryIdx);
                        paused.scanned.incrementAndGet();
                    }
                    processed++;
                }
            }

            // Update progress in DB every 20 ticks (1 second)
            if (++tickCount[0] % 20 == 0) {
                sessionManager.updateProgress(sessionId, paused.scanned.get(), paused.flagged.get());
            }

            if (index.get() >= paused.chunks.size() && paused.pendingChunks.isEmpty()) {
                // Flush final progress
                sessionManager.updateProgress(sessionId, paused.scanned.get(), paused.flagged.get());
                taskHolder[0].cancel();
                activeTasks.remove(sessionId);
                scanIndices.remove(sessionId);
                sessionManager.completeSession(sessionId, paused.scanned.get(), paused.flagged.get());
                String dedupMsg = paused.skipped.get() > 0 ? " (" + paused.skipped.get() + " skipped)" : "";
                plugin.getLogger().info("Full scan complete: " + paused.scanned.get()
                        + " chunks, " + paused.flagged.get() + " violations" + dedupMsg);
            }
        }, 1L, 1L);

        activeTasks.put(sessionId, taskHolder[0]);
    }

    /**
     * Stop a running or paused scan session.
     * @return future that completes when the DB status has been updated to STOPPED.
     */
    public CompletableFuture<Void> stopScan(int sessionId) {
        // Cancel any active task
        org.bukkit.scheduler.BukkitTask task = activeTasks.remove(sessionId);
        if (task != null) {
            task.cancel();
        }
        scanIndices.remove(sessionId);
        pausedScans.remove(sessionId);
        plugin.getLogger().info("Scan session " + sessionId + " stopped.");
        return sessionManager.stopSession(sessionId);
    }

    /**
     * Restart a stopped scan session — re-initiates based on original session parameters.
     */
    public void restartScan(int sessionId) {
        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            plugin.getLogger().warning("Cannot restart: session " + sessionId + " not found.");
            return;
        }

        plugin.getLogger().info("Restarting scan session " + sessionId + " (" + session.scanType() + ": " + session.target() + ")");

        // Re-initiate based on scan type
        switch (session.scanType()) {
            case "area" -> restartAreaScan(session);
            case "res" -> scanRes(session.target());
            case "world" -> restartWorldScan(session);
            case "full" -> scanFull();
            case "player" -> restartPlayerScan(session);
            default -> plugin.getLogger().warning("Cannot restart scan type: " + session.scanType());
        }
    }

    /** Parse area scan target "world x1,z1 ~ x2,z2" and re-scan. */
    private void restartAreaScan(DatabaseManager.ScanSession session) {
        try {
            String target = session.target();
            String[] parts = target.split(" ");
            if (parts.length < 2) return;
            String worldName = parts[0];
            // parts[1] = "x1,z1", parts[2] = "~", parts[3] = "x2,z2"
            String[] coords1 = parts[1].split(",");
            String[] coords2 = parts[3].split(",");
            int x1 = Integer.parseInt(coords1[0]);
            int z1 = Integer.parseInt(coords1[1]);
            int x2 = Integer.parseInt(coords2[0]);
            int z2 = Integer.parseInt(coords2[1]);
            scanArea(worldName, x1, z1, x2, z2);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse area target for restart: " + session.target());
        }
    }

    /** Restart a world scan. */
    private void restartWorldScan(DatabaseManager.ScanSession session) {
        String target = session.target();
        String mode = "all";
        String worldName = target;
        // Parse: target might be just world name or "worldName mode"
        if (target.contains(" ")) {
            String[] parts = target.split(" ");
            worldName = parts[0];
            mode = parts.length > 1 ? parts[1] : "all";
        }
        scanWorld(worldName, mode);
    }

    /** Restart a player scan. */
    private void restartPlayerScan(DatabaseManager.ScanSession session) {
        String target = session.target();
        if ("all online".equals(target)) {
            scanAllOnlinePlayers();
        } else if ("all offline".equals(target)) {
            scanAllOfflinePlayers();
        } else {
            // Single player scan
            scanPlayer(target);
        }
    }

    /**
     * Check if a session is currently active (has a running task).
     */
    public boolean isSessionRunning(int sessionId) {
        return activeTasks.containsKey(sessionId);
    }

    /**
     * Get a session's current status from DB.
     */
    public String getSessionStatus(int sessionId) {
        var session = sessionManager.getSession(sessionId);
        return session != null ? session.status() : null;
    }

    public void shutdown() {
        // Cancel all active tasks
        for (var entry : activeTasks.entrySet()) {
            entry.getValue().cancel();
        }
        activeTasks.clear();
        scanIndices.clear();
        pausedScans.clear();
        mcaReadPool.shutdownNow();
        plugin.getLogger().info("Scan service shut down.");
    }
}
