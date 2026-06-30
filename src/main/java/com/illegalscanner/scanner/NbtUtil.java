package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Utility for NBT serialization of ItemStacks.
 * Uses Paper's built-in serializeAsBytes/deserializeBytes.
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
     * Read player inventory items from a Minecraft player .dat file.
     * Parses the NBT compound and extracts items from the Inventory and EnderItems lists.
     *
     * @param playerDataFile the player's .dat file
     * @param plugin         for logging
     * @return list of ItemStacks found in the player's inventory and ender chest
     */
    public static List<ItemStack> readPlayerInventory(File playerDataFile, IllegalScanner plugin) {
        if (!playerDataFile.exists()) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();

        CompoundTag root = null;
        java.nio.file.Path path = playerDataFile.toPath();

        // Detect compression by reading first 2 bytes (gzip magic = 0x1F 0x8B)
        boolean isGzip = false;
        try (java.io.InputStream probeIn = java.nio.file.Files.newInputStream(path)) {
            byte[] magic = new byte[2];
            if (probeIn.read(magic) == 2) {
                isGzip = (magic[0] == (byte) 0x1F && magic[1] == (byte) 0x8B);
            }
        } catch (Exception ignored) {}

        try {
            if (isGzip) {
                // Read gzip-compressed NBT
                java.io.DataInputStream dis = new java.io.DataInputStream(
                        new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(path)));
                root = NbtIo.read(dis);
                dis.close();
            } else {
                // Read uncompressed NBT
                java.io.DataInputStream dis = new java.io.DataInputStream(
                        new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path)));
                root = NbtIo.read(dis);
                dis.close();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to read player data: " + playerDataFile.getName()
                            + " (gzip=" + isGzip + ") - " + e.getMessage());
            return items;
        }

        if (root == null) return items;

        try {
            // Get registry access from NMS server (1.20.6 compatible)
            net.minecraft.core.RegistryAccess registryAccess =
                    ((org.bukkit.craftbukkit.CraftServer) plugin.getServer())
                            .getServer().registryAccess();

            // Read main Inventory list
            if (root.contains("Inventory", Tag.TAG_LIST)) {
                ListTag invList = root.getList("Inventory", Tag.TAG_COMPOUND);
                for (int i = 0; i < invList.size(); i++) {
                    CompoundTag itemTag = invList.getCompound(i);
                    // 1.20.6: parseOptional returns ItemStack directly (not Optional)
                    net.minecraft.world.item.ItemStack nmsItem =
                            net.minecraft.world.item.ItemStack.parseOptional(
                                    registryAccess, itemTag);
                    if (!nmsItem.isEmpty()) {
                        items.add(CraftItemStack.asBukkitCopy(nmsItem));
                    }
                }
            }

            // Read EnderItems list (ender chest contents)
            if (root.contains("EnderItems", Tag.TAG_LIST)) {
                ListTag enderList = root.getList("EnderItems", Tag.TAG_COMPOUND);
                for (int i = 0; i < enderList.size(); i++) {
                    CompoundTag itemTag = enderList.getCompound(i);
                    net.minecraft.world.item.ItemStack nmsItem =
                            net.minecraft.world.item.ItemStack.parseOptional(
                                    registryAccess, itemTag);
                    if (!nmsItem.isEmpty()) {
                        items.add(CraftItemStack.asBukkitCopy(nmsItem));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to parse player inventory items: " + playerDataFile.getName() + " - " + e.getMessage());
        }

        return items;
    }
}
