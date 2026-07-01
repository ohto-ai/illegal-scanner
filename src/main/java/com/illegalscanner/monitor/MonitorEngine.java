package com.illegalscanner.monitor;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager.MonitorRecord;
import com.illegalscanner.scanner.ChunkScanner;
import com.illegalscanner.validator.ValidationResult;
import com.illegalscanner.validator.Violation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.BlockState;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified monitoring engine — replaces all old event listeners.
 * Listens to configured events, validates items, and writes to monitor_records.
 */
public class MonitorEngine implements Listener {

    private final IllegalScanner plugin;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final Set<MonitorEventType> activeEvents = EnumSet.allOf(MonitorEventType.class);

    // Configurable timing
    private long flushIntervalMs = 5000; // dedup window
    private int retentionDays = 7;
    private long scanIntervalMs = 300_000; // periodic loaded chunk scan
    private int periodicScanTaskId = -1;

    public MonitorEngine(IllegalScanner plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /** Public reload from config file (called by /is config reload). */
    public void loadConfigFromFile() { loadConfig(); }

    private void loadConfig() {
        var cfg = plugin.getConfigManager().getConfig();
        flushIntervalMs = cfg.getLong("monitor.flush_seconds", 5) * 1000L;
        retentionDays = cfg.getInt("monitor.retention_days", 7);
        scanIntervalMs = cfg.getLong("monitor.interval_seconds", 300) * 1000L;
    }

    /**
     * Start monitoring: register Bukkit listeners.
     */
    public void start() {
        if (!enabled.get()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Monitor engine started. Active events: " + activeEvents);
        startPeriodicScan();
    }

    /**
     * Stop monitoring: unregister + stop periodic.
     */
    public void stop() {
        enabled.set(false);
        if (periodicScanTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(periodicScanTaskId);
            periodicScanTaskId = -1;
        }
        // Bukkit doesn't support unregistering individual listeners easily.
        // We use the enabled flag.
        plugin.getLogger().info("Monitor engine stopped.");
    }

    // ==================== Config ====================

    public boolean isEnabled() { return enabled.get(); }

    public void setEnabled(boolean v) {
        enabled.set(v);
        if (v) start();
        else stop();
    }

    public Set<MonitorEventType> getActiveEvents() { return activeEvents; }

    public void enableEvent(MonitorEventType type) { activeEvents.add(type); }
    public void disableEvent(MonitorEventType type) { activeEvents.remove(type); }

    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long ms) { this.flushIntervalMs = ms; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int days) { this.retentionDays = days; }

    // ==================== Inventory Click ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.INVENTORY_CLICK)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isPlayerExempt(player)) return;

        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) {
            checkItem(current, player, event.getInventory(), event.getSlot(), "INVENTORY_CLICK");
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            checkItem(cursor, player, event.getInventory(), -1, "INVENTORY_CLICK_CURSOR");
        }
    }

    // ==================== Inventory Drag ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.INVENTORY_DRAG)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isPlayerExempt(player)) return;

        for (ItemStack newItem : event.getNewItems().values()) {
            if (newItem != null && !newItem.getType().isAir()) {
                checkItem(newItem, player, event.getInventory(), -1, "INVENTORY_DRAG");
            }
        }
    }

    // ==================== Item Move (hoppers etc.) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.ITEM_MOVE)) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        Location destLoc = null;
        InventoryHolder destHolder = event.getDestination().getHolder();
        if (destHolder instanceof Container c) destLoc = c.getLocation();

        List<Violation> violations = plugin.getValidationEngine().validate(item, destLoc);
        if (!violations.isEmpty()) {
            ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);
            recordMonitor(item, -1, "container_move", destLoc, null, null, violations, severity, "ITEM_MOVE");
        }
    }

    // ==================== Player Join ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.PLAYER_JOIN)) return;
        Player player = event.getPlayer();
        if (isPlayerExempt(player)) return;

        // Scan player's inventory on join
        plugin.getScanService().getPlayerScanner().scanOnlinePlayer(player,
                (item, slot, container, loc, violations, severity) -> {
                    recordMonitor(item, slot, container, loc,
                            player.getUniqueId().toString(), player.getName(),
                            violations, severity, "PLAYER_JOIN");
                });
    }

    // ==================== Chunk Load ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.CHUNK_LOAD)) return;

        // Defer to avoid blocking chunk loading
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getScanService().getChunkScanner().scanChunk(event.getChunk(),
                    (item, slot, container, loc, violations, severity) -> {
                        recordMonitor(item, slot, container, loc, null, null,
                                violations, severity, "CHUNK_LOAD");
                    });
        });
    }

    // ==================== Container Close (re-scan snapshot) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled.get() || !activeEvents.contains(MonitorEventType.CONTAINER_OPEN)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // Whitelisted players can silently modify containers — skip re-scan.
        // Container state will be updated on the next chunk scan.
        if (isPlayerExempt(player)) return;

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();

        // Only handle block containers (chests, barrels, furnaces, etc.)
        if (!(holder instanceof Container)) return;

        // Defer to next tick to avoid blocking inventory close
        Location containerLoc = ((Container) holder).getLocation();
        if (containerLoc == null) return;

        final Player finalPlayer = player;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            scanSingleContainer(inventory, containerLoc, finalPlayer);
        });
    }

    /**
     * Scan a single block container's contents and record a CONTAINER_CLOSE snapshot.
     * Deletes all old monitor records for this container, then compares current
     * violations against old scan records. Items that were in old scans but are
     * now gone get a CONTAINER_CLEAN marker so the View hides them.
     */
    private void scanSingleContainer(Inventory inventory, Location containerLoc, Player player) {
        String worldName = containerLoc.getWorld() != null ? containerLoc.getWorld().getName() : "unknown";
        String containerLocStr = worldName + "," + containerLoc.getBlockX() + ","
                + containerLoc.getBlockY() + "," + containerLoc.getBlockZ();
        String containerType = inventory.getType().name();
        int chunkX = containerLoc.getBlockX() >> 4;
        int chunkZ = containerLoc.getBlockZ() >> 4;
        long now = System.currentTimeMillis();

        // 1. Delete all old monitor records for this container (scan覆盖monitor)
        plugin.getDatabaseManager().deleteMonitorRecordsByContainerLoc(containerLocStr);

        // 2. Get old scan item_hashes for this container — these need to be
        //    superseded if the items are no longer present
        java.util.Set<String> oldScanHashes = plugin.getDatabaseManager()
                .getScanItemHashesByContainerLoc(containerLocStr);

        // 3. Scan each slot, collect current violation hashes
        //    Audit: skip if the container holds an exclusion marker.
        java.util.Set<String> currentHashes = new java.util.HashSet<>();
        if (!com.illegalscanner.scanner.ContainerUtil.hasIgnoreMarker(
                com.illegalscanner.scanner.ContainerUtil.collectItems(inventory))) {
            int slot = 0;
            for (ItemStack item : inventory.getContents()) {
                if (item != null && !item.getType().isAir()) {
                    List<Violation> violations = plugin.getValidationEngine().validate(item, containerLoc);
                    if (!violations.isEmpty()) {
                        ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);
                        String itemHash = plugin.getItemHashService().resolve(item);
                        if (itemHash != null) {
                            currentHashes.add(itemHash);

                            if (plugin.getQueryService() != null) {
                                plugin.getQueryService().invalidateAll();
                            }

                            String violationJson = buildViolationJson(violations);
                            MonitorRecord record = new MonitorRecord(
                                    0, itemHash, "CONTAINER_CLOSE",
                                    worldName, chunkX, chunkZ,
                                    player.getUniqueId().toString(), player.getName(),
                                    slot, containerType, containerLocStr,
                                    violationJson, severity.name(), now);
                            plugin.getDatabaseManager().insertMonitorRecord(record);

                            if (severity == ValidationResult.ILLEGAL
                                    && plugin.getConfigManager().getConfig().getBoolean("alerts.console_log", true)) {
                                plugin.getLogger().warning("[CONTAINER_CLOSE] ILLEGAL: " + item.getType().name()
                                        + " from container @ " + containerLocStr
                                        + " (closed by " + player.getName() + ")"
                                        + " - " + violations.size() + " violations");
                            }
                        }
                    }
                }
                slot++;
            }
        }

        // 4. For old scan items that are NO LONGER in the container,
        //    insert a CONTAINER_CLEAN marker to supersede the old scan record.
        //    The View dedup keeps the latest record per item_hash (CONTAINER_CLEAN wins),
        //    then filters out severity=CLEAN records — effectively hiding the item.
        for (String oldHash : oldScanHashes) {
            if (!currentHashes.contains(oldHash)) {
                MonitorRecord cleanRecord = new MonitorRecord(
                        0, oldHash, "CONTAINER_CLEAN",
                        worldName, chunkX, chunkZ,
                        player.getUniqueId().toString(), player.getName(),
                        null, containerType, containerLocStr,
                        "[]", "CLEAN", now);
                plugin.getDatabaseManager().insertMonitorRecord(cleanRecord);
            }
        }
    }

    // ==================== Core Logic ====================

    private void checkItem(ItemStack item, Player player, Inventory inventory, int slot, String eventType) {
        // Determine container type and correct location for validation & recording
        InventoryHolder holder = inventory.getHolder();
        String container;
        Location containerLoc;
        String pUuid = null;
        String pName = null;

        if (holder instanceof Player p) {
            // Player's own inventory — use player location (will be filtered from chunk views)
            container = "inventory";
            containerLoc = p.getLocation();
            pUuid = p.getUniqueId().toString();
            pName = p.getName();
        } else if (holder instanceof Container c) {
            // Block container (chest, barrel, etc.) — use the container's fixed location
            container = c.getInventory().getType().name();
            containerLoc = c.getLocation();
        } else {
            // Virtual inventory or unknown — fall back to player location
            container = inventory.getType().name();
            containerLoc = player.getLocation();
        }

        // Validate using the correct location (container location for block containers,
        // player location for player inventories) so region whitelist checks are accurate
        Location loc = containerLoc != null ? containerLoc : player.getLocation();
        List<Violation> violations = plugin.getValidationEngine().validate(item, loc);
        if (violations.isEmpty()) return;

        ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);

        recordMonitor(item, slot, container, containerLoc, pUuid, pName, violations, severity, eventType);

        // Console alert
        if (severity == ValidationResult.ILLEGAL
                && plugin.getConfigManager().getConfig().getBoolean("alerts.console_log", true)) {
            plugin.getLogger().warning("[" + eventType + "] ILLEGAL: " + item.getType().name()
                    + " from " + (pName != null ? pName : "container")
                    + " - " + violations.size() + " violations");
        }
    }

    /**
     * Record a violation to monitor_records (with dedup).
     */
    private void recordMonitor(ItemStack item, int slot, String container,
                                Location loc, String playerUuid, String playerName,
                                List<Violation> violations, ValidationResult severity,
                                String eventType) {
        if (loc == null) return;
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        String containerLoc = worldName + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();

        // Compute item hash
        String itemHash = plugin.getItemHashService().resolve(item);
        if (itemHash == null) return;

        // Dedup check
        if (plugin.getDatabaseManager().hasRecentMonitorRecord(itemHash, playerUuid, containerLoc, flushIntervalMs)) {
            return; // Skip duplicate within flush window
        }

        // Invalidate unified cache
        if (plugin.getQueryService() != null) {
            plugin.getQueryService().invalidateAll();
        }

        // Build violation JSON
        String violationJson = buildViolationJson(violations);

        MonitorRecord record = new MonitorRecord(
                0, itemHash, eventType,
                worldName, chunkX, chunkZ,
                playerUuid, playerName, slot >= 0 ? slot : null,
                container, containerLoc,
                violationJson, severity.name(),
                System.currentTimeMillis());

        plugin.getDatabaseManager().insertMonitorRecord(record);
    }

    // ==================== Periodic Scan ====================

    private void startPeriodicScan() {
        if (periodicScanTaskId >= 0) return;
        long ticks = scanIntervalMs / 50;
        periodicScanTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!enabled.get()) return;
            for (var world : Bukkit.getWorlds()) {
                for (var chunk : world.getLoadedChunks()) {
                    plugin.getScanService().getChunkScanner().scanChunk(chunk,
                            (item, slot, container, loc, violations, severity) -> {
                                recordMonitor(item, slot, container, loc, null, null,
                                        violations, severity, "PERIODIC_SCAN");
                            });
                }
            }
        }, ticks, ticks);
    }

    // ==================== Helpers ====================

    private boolean isPlayerExempt(Player player) {
        return plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().isWhitelisted(player.getUniqueId());
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
}
