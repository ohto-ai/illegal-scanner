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

    // ==================== Core Logic ====================

    private void checkItem(ItemStack item, Player player, Inventory inventory, int slot, String eventType) {
        Location loc = player.getLocation();
        List<Violation> violations = plugin.getValidationEngine().validate(item, loc);
        if (violations.isEmpty()) return;

        ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);

        // Determine container type (player inventory vs block container)
        InventoryHolder holder = inventory.getHolder();
        String container;
        Location containerLoc;
        String pUuid = null;
        String pName = null;

        if (holder instanceof Player p) {
            container = "inventory";
            containerLoc = p.getLocation();
            pUuid = p.getUniqueId().toString();
            pName = p.getName();
        } else if (holder instanceof Container c) {
            container = c.getInventory().getType().name();
            containerLoc = c.getLocation();
        } else {
            container = inventory.getType().name();
            containerLoc = player.getLocation();
        }

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
