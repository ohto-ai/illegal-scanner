package com.illegalscanner.scanner;

import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Utility for NBT serialization of ItemStacks.
 * Uses Paper 1.21's built-in serializeAsBytes/deserializeBytes.
 */
public final class NbtUtil {

    private NbtUtil() {}

    /**
     * Serialize an ItemStack to a Base64-encoded string for database storage.
     */
    public static String itemStackToBase64(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        byte[] bytes = item.serializeAsBytes();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Deserialize from Base64.
     */
    public static ItemStack itemStackFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        byte[] bytes = Base64.getDecoder().decode(base64);
        return ItemStack.deserializeBytes(bytes);
    }

    /**
     * Full JSON snapshot with Base64 NBT blob.
     */
    public static String itemStackToJson(ItemStack item) {
        if (item == null || item.getType().isAir()) return "{}";
        return "{\"type\":\"" + item.getType().name() + "\"," +
               "\"amount\":" + item.getAmount() + "," +
               "\"base64\":\"" + itemStackToBase64(item) + "\"}";
    }

    /**
     * Deserialize an ItemStack from a JSON snapshot string (produced by itemStackToJson).
     */
    public static ItemStack itemStackFromJson(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) return null;
        String base64 = null;
        int b64Idx = json.indexOf("\"base64\":\"");
        if (b64Idx >= 0) {
            int start = b64Idx + 10;
            int end = json.indexOf("\"", start);
            if (end > start) base64 = json.substring(start, end);
        }
        if (base64 != null && !base64.isEmpty()) {
            return itemStackFromBase64(base64);
        }
        return null;
    }

    /**
     * Read player inventory from .dat file.
     * TODO Phase 6: Full NBT parsing implementation.
     */
    public static List<ItemStack> readPlayerInventory(File playerDataFile) {
        return Collections.emptyList();
    }
}
