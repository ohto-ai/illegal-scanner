package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.validator.ValidationResult;
import com.illegalscanner.validator.Violation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Scans player inventories (both online and offline).
 */
public class PlayerScanner {

    private final IllegalScanner plugin;
    private final ChunkScanner chunkScanner;

    public PlayerScanner(IllegalScanner plugin, ChunkScanner chunkScanner) {
        this.plugin = plugin;
        this.chunkScanner = chunkScanner;
    }

    /**
     * Scan an online player's full inventory with callback for each violation.
     */
    public int scanOnlinePlayer(Player player, ChunkScanner.ViolationCallback callback) {
        // Player whitelist check
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().isWhitelisted(player.getUniqueId())) {
            return 0;
        }

        int totalFlagged = 0;
        Location loc = player.getLocation();

        // Scan main inventory + hotbar (0-35)
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                if (scanItemWithCallback(item, slot, "inventory", loc, callback)) totalFlagged++;
            }
        }

        // Scan armor slots (100-103)
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && !armor[i].getType().isAir()) {
                if (scanItemWithCallback(armor[i], 100 + i, "armor", loc, callback)) totalFlagged++;
            }
        }

        // Scan offhand (40)
        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            if (scanItemWithCallback(offhand, 40, "offhand", loc, callback)) totalFlagged++;
        }

        // Scan ender chest
        if (plugin.getConfigManager().getConfig().getBoolean("scan.scan_player_enderchests", true)) {
            org.bukkit.inventory.Inventory enderChest = player.getEnderChest();
            for (int slot = 0; slot < 27; slot++) {
                ItemStack item = enderChest.getItem(slot);
                if (item != null && !item.getType().isAir()) {
                    if (scanItemWithCallback(item, slot, "enderchest", loc, callback)) totalFlagged++;
                }
            }
        }

        return totalFlagged;
    }

    private boolean scanItemWithCallback(ItemStack item, int slot, String container,
                                          Location loc, ChunkScanner.ViolationCallback callback) {
        List<Violation> violations = plugin.getValidationEngine().validate(item, loc);
        if (violations.isEmpty()) return false;
        ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);
        if (callback != null) {
            callback.onViolation(item, slot, container, loc, violations, severity);
        }
        return true;
    }

    /**
     * Scan an offline player's inventory by parsing their .dat file.
     */
    public int scanOfflinePlayer(OfflinePlayer offlinePlayer, ChunkScanner.ViolationCallback callback) {
        // Player whitelist check
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().isWhitelisted(offlinePlayer.getUniqueId())) {
            return 0;
        }

        File playerDataFolder = new File(
                plugin.getServer().getWorlds().get(0).getWorldFolder(), "playerdata");
        File playerFile = new File(playerDataFolder, offlinePlayer.getUniqueId() + ".dat");

        if (!playerFile.exists()) return 0;

        // Read inventory items from NBT
        List<ItemStack> items = NbtUtil.readPlayerInventory(playerFile, plugin);
        if (items.isEmpty()) return 0;

        int totalFlagged = 0;
        Location loc = offlinePlayer.getBedSpawnLocation() != null
                ? offlinePlayer.getBedSpawnLocation()
                : new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0);

        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack item = items.get(slot);
            if (item == null || item.getType().isAir()) continue;

            List<Violation> violations = plugin.getValidationEngine().validate(item, loc);
            if (violations.isEmpty()) continue;

            ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);
            String container = slot < 36 ? "inventory" : "enderchest";
            if (callback != null) {
                callback.onViolation(item, slot, container, loc, violations, severity);
            }
            totalFlagged++;
        }

        return totalFlagged;
    }

    /**
     * Scan all online players with callback.
     */
    public int scanAllOnlinePlayers(ChunkScanner.ViolationCallback callback) {
        int totalFlagged = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                totalFlagged += scanOnlinePlayer(player, callback);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scanning player " + player.getName(), e);
            }
        }
        return totalFlagged;
    }

    /**
     * Scan all offline players with callback.
     */
    public int scanAllOfflinePlayers(ChunkScanner.ViolationCallback callback) {
        int totalFlagged = 0;
        java.util.Set<UUID> scanned = new java.util.HashSet<>();

        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            File playerDataFolder = new File(world.getWorldFolder(), "playerdata");
            if (!playerDataFolder.exists() || !playerDataFolder.isDirectory()) continue;
            File[] playerFiles = playerDataFolder.listFiles((dir, name) -> name.endsWith(".dat"));
            if (playerFiles == null) continue;
            for (File file : playerFiles) {
                try {
                    String uuidStr = file.getName().replace(".dat", "");
                    UUID uuid = UUID.fromString(uuidStr);
                    // Dedup across worlds
                    if (!scanned.add(uuid)) continue;
                    OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(uuid);
                    totalFlagged += scanOfflinePlayer(offlinePlayer, callback);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error scanning offline player file: " + file.getName(), e);
                }
            }
        }
        return totalFlagged;
    }
}
