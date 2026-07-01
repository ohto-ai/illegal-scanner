package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Computes SHA-256 NBT hash of an ItemStack, EXCLUDING durability.
 * The hash is used as the primary key in item_index for deduplication.
 *
 * Hash input = serialized NBT bytes with durability stripped out.
 */
public final class ItemHashService {

    private final IllegalScanner plugin;

    /** Simple LRU cache: item type + NBT → hash (avoids repeated hashing). */
    private static final int CACHE_SIZE = 500;
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public ItemHashService(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    /**
     * Compute the NBT hash for an ItemStack (durability excluded).
     * Uses a cache keyed by a rough fingerprint to avoid re-hashing identical items.
     *
     * @param item the item to hash
     * @return SHA-256 hex string, or null if item is null/air
     */
    public String computeHash(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        // Build a cache key from type + serialized bytes fingerprint
        String cacheKey = item.getType().name() + "|" + item.serializeAsBytes().length;
        String cached = cache.get(cacheKey);
        if (cached != null) return cached;

        try {
            // Serialize to bytes, then hash
            byte[] nbtBytes = serializeForHashing(item);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(nbtBytes);
            String hash = bytesToHex(hashBytes);

            cache.put(cacheKey, hash);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().log(Level.SEVERE, "SHA-256 not available", e);
            return null;
        }
    }

    /**
     * Resolve an ItemStack to its item_hash, ensuring it's indexed in the database.
     * Returns null for air items.
     */
    public String resolve(ItemStack item) {
        String hash = computeHash(item);
        if (hash == null) return null;

        // Ensure item_index has this hash (async fire-and-forget)
        // Normalize amount to 1 so the stored snapshot is stack-size independent
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        String snapshot = NbtUtil.itemStackToJson(normalized);
        plugin.getDatabaseManager().ensureItemIndexed(hash, item.getType().name(), snapshot);

        return hash;
    }

    /**
     * Serialize an ItemStack for hashing, with durability stripped.
     * Strategy: serialize, then zero out durability-related bytes.
     *
     * We use Paper's serializeAsBytes() which includes all data components.
     * To exclude durability, we create a copy with max durability and serialize that.
     */
    private byte[] serializeForHashing(ItemStack item) {
        // Create a copy, normalize amount to 1, and repair durability
        ItemStack copy = item.clone();
        copy.setAmount(1);
        if (copy.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
            dmg.setDamage(0);
            copy.setItemMeta((org.bukkit.inventory.meta.ItemMeta) dmg);
        }
        return copy.serializeAsBytes();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
