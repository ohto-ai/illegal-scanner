package com.illegalscanner.whitelist;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import com.illegalscanner.scanner.ItemAccessor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages the item whitelist — items matching whitelist entries are skipped during validation.
 * Supports smart matching on material, custom name pattern, lore pattern, enchantments, and attribute modifiers.
 */
public class ItemWhitelistManager {

    private final IllegalScanner plugin;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Gson gson = new Gson();
    private List<DatabaseManager.ItemWhitelistEntry> cache = new ArrayList<>();

    private static final Type ENCHANTS_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();
    private static final Type ATTRS_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    public ItemWhitelistManager(IllegalScanner plugin) {
        this.plugin = plugin;
        loadCache();
    }

    /**
     * Reload the whitelist cache from the database.
     */
    public void loadCache() {
        lock.writeLock().lock();
        try {
            cache = plugin.getDatabaseManager().loadItemWhitelist();
            plugin.getLogger().info("Item whitelist loaded: " + cache.size() + " entries");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Check if an item is whitelisted (matching any entry).
     */
    public boolean isWhitelisted(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        lock.readLock().lock();
        try {
            for (DatabaseManager.ItemWhitelistEntry entry : cache) {
                if (matchesEntry(item, entry)) {
                    return true;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return false;
    }

    /**
     * Smart match an item against a whitelist entry.
     */
    private boolean matchesEntry(ItemStack item, DatabaseManager.ItemWhitelistEntry entry) {
        // 0. Hash-based exact match (fast path)
        if (entry.itemHash() != null && !entry.itemHash().isEmpty()) {
            String itemHash = plugin.getItemHashService().computeHash(item);
            return entry.itemHash().equals(itemHash);
        }

        // 1. Material must match
        Material entryMaterial = Material.getMaterial(entry.material());
        if (entryMaterial == null || item.getType() != entryMaterial) {
            return false;
        }

        // 2. Custom name pattern (substring match)
        if (entry.customNamePattern() != null && !entry.customNamePattern().isEmpty()) {
            String customName = ItemAccessor.getCustomNamePlain(item);
            if (customName == null || !customName.contains(entry.customNamePattern())) {
                return false;
            }
        }

        // 3. Lore pattern (joined lines substring match)
        if (entry.lorePattern() != null && !entry.lorePattern().isEmpty()) {
            List<String> loreLines = ItemAccessor.getLoreLines(item);
            if (loreLines.isEmpty()) {
                return false;
            }
            String joinedLore = String.join("\n", loreLines);
            if (!joinedLore.contains(entry.lorePattern())) {
                return false;
            }
        }

        // 4. Enchantments (contains check: all specified must be present at >= level; extra allowed)
        if (entry.enchantmentsJson() != null && !entry.enchantmentsJson().isEmpty()) {
            try {
                Map<String, Integer> requiredEnchants = gson.fromJson(entry.enchantmentsJson(), ENCHANTS_MAP_TYPE);
                Map<Enchantment, Integer> itemEnchants = item.getEnchantments();
                for (Map.Entry<String, Integer> req : requiredEnchants.entrySet()) {
                    String key = normalizeEnchantKey(req.getKey());
                    int requiredLevel = req.getValue();
                    boolean found = false;
                    for (Map.Entry<Enchantment, Integer> ie : itemEnchants.entrySet()) {
                        if (normalizeEnchantKey(ie.getKey().getKey().getKey()).equals(key) && ie.getValue() >= requiredLevel) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return false;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse enchantmentsJson for whitelist entry #" + entry.id() + ": " + e.getMessage());
                return false;
            }
        }

        // 5. Attribute modifiers (all specified must match: name, value ±0.01, operation)
        if (entry.attributeModifiersJson() != null && !entry.attributeModifiersJson().isEmpty()) {
            try {
                List<Map<String, Object>> requiredAttrs = gson.fromJson(entry.attributeModifiersJson(), ATTRS_LIST_TYPE);
                List<ItemAccessor.AttributeModifierEntry> itemAttrs = ItemAccessor.getAttributeModifiers(item);
                for (Map<String, Object> req : requiredAttrs) {
                    String reqName = (String) req.get("name");
                    double reqValue = ((Number) req.get("value")).doubleValue();
                    int reqOp = ((Number) req.get("operation")).intValue();
                    boolean found = false;
                    for (ItemAccessor.AttributeModifierEntry ie : itemAttrs) {
                        if (ie.attributeName().equals(reqName)
                                && Math.abs(ie.value() - reqValue) < 0.01
                                && ie.operation() == reqOp) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return false;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse attributeModifiersJson for whitelist entry #" + entry.id() + ": " + e.getMessage());
                return false;
            }
        }

        return true;
    }

    /**
     * Strip "minecraft:" namespace prefix from an enchantment key.
     */
    private String normalizeEnchantKey(String key) {
        if (key.startsWith("minecraft:")) {
            return key.substring(10);
        }
        return key;
    }

    /**
     * Add an item whitelist entry (async DB write + immediate cache update).
     */
    public void addEntry(String material, String itemHash, String customNamePattern, String lorePattern,
                         String enchantmentsJson, String attributeModifiersJson) {
        DatabaseManager.ItemWhitelistEntry entry = new DatabaseManager.ItemWhitelistEntry(
                -1, material, itemHash, customNamePattern, lorePattern,
                enchantmentsJson, attributeModifiersJson, System.currentTimeMillis());

        plugin.getDatabaseManager().addItemWhitelistEntry(entry).thenAccept(id -> {
            if (id >= 0) {
                // Reload cache to include the new entry with its generated ID
                loadCache();
            }
        });
    }

    /**
     * Add an item whitelist entry synchronously (returns the new ID).
     */
    public int addEntrySync(String material, String itemHash, String customNamePattern, String lorePattern,
                            String enchantmentsJson, String attributeModifiersJson) {
        DatabaseManager.ItemWhitelistEntry entry = new DatabaseManager.ItemWhitelistEntry(
                -1, material, itemHash, customNamePattern, lorePattern,
                enchantmentsJson, attributeModifiersJson, System.currentTimeMillis());

        try {
            int id = plugin.getDatabaseManager().addItemWhitelistEntry(entry).get();
            if (id >= 0) {
                loadCache();
            }
            return id;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add item whitelist entry: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Remove an entry by database ID.
     */
    public void removeEntry(int id) {
        plugin.getDatabaseManager().removeItemWhitelistEntry(id).thenRun(this::loadCache);
    }

    /**
     * After adding a new item whitelist entry, scan existing records for matches.
     * Returns the count of existing records that now match the whitelist (informational only).
     */
    public java.util.concurrent.CompletableFuture<Integer> cleanupAfterWhitelistAdd() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            int matched = 0;
            lock.readLock().lock();
            try {
                var db = plugin.getDatabaseManager();
                // Get all distinct item hashes that have active violation records
                var items = db.getDistinctViolationItems();
                for (var entry : items) {
                    // Check against all current whitelist entries (skip hash-only entries —
                    // those would have already been caught during scan time)
                    for (var wl : cache) {
                        // Only check non-hash entries (hash entries are exact-match only)
                        if (wl.itemHash() == null || wl.itemHash().isEmpty()) {
                            // Try to match by material
                            if (entry.itemType().equals(wl.material())) {
                                // For material-only entries, count all records for this item hash
                                if (wl.customNamePattern() == null && wl.lorePattern() == null
                                        && wl.enchantmentsJson() == null && wl.attributeModifiersJson() == null) {
                                    matched += db.getItemRecordCount(entry.itemHash());
                                    break;
                                }
                                // For entries with additional criteria, try to reconstruct the item
                                // from snapshot and test match
                                var snapshot = db.getItemByHash(entry.itemHash());
                                if (snapshot != null) {
                                    try {
                                        var itemStack = com.illegalscanner.scanner.NbtUtil.itemStackFromJson(snapshot.itemSnapshot());
                                        if (itemStack != null && matchesEntry(itemStack, wl)) {
                                            matched += db.getItemRecordCount(entry.itemHash());
                                            break;
                                        }
                                    } catch (Exception ignored) {
                                        // Skip items that can't be deserialized
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                lock.readLock().unlock();
            }
            return matched;
        }, plugin.getDatabaseManager().getDbExecutor());
    }

    /**
     * Async cleanup — fire and forget (logs result when done).
     */
    public void cleanupAfterWhitelistAddAsync() {
        cleanupAfterWhitelistAdd().thenAccept(count -> {
            if (count > 0) {
                plugin.getLogger().info("物品白名单清理完成: 自动处理了 " + count + " 条历史记录。");
            }
        }).exceptionally(e -> {
            plugin.getLogger().warning("物品白名单清理失败: " + e.getMessage());
            return null;
        });
    }

    /**
     * Get all whitelist entries (for listing).
     */
    public List<DatabaseManager.ItemWhitelistEntry> listEntries() {
        lock.readLock().lock();
        try {
            return List.copyOf(cache);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Build enchantments JSON from a held item for whitelist storage.
     */
    public String buildEnchantmentsJson(ItemStack item) {
        Map<Enchantment, Integer> enchants = item.getEnchantments();
        if (enchants.isEmpty()) return null;
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            map.put(normalizeEnchantKey(e.getKey().getKey().getKey()), e.getValue());
        }
        return gson.toJson(map);
    }

    /**
     * Build attribute modifiers JSON from a held item for whitelist storage.
     */
    public String buildAttributesJson(ItemStack item) {
        List<ItemAccessor.AttributeModifierEntry> attrs = ItemAccessor.getAttributeModifiers(item);
        if (attrs.isEmpty()) return null;
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemAccessor.AttributeModifierEntry a : attrs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", a.attributeName());
            map.put("value", a.value());
            map.put("operation", a.operation());
            list.add(map);
        }
        return gson.toJson(list);
    }
}
