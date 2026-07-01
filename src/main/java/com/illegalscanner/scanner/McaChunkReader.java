package com.illegalscanner.scanner;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads container items directly from Minecraft Anvil (.mca) region files.
 * Pure disk I/O — zero Bukkit API calls, zero chunk loading, zero world generation.
 *
 * <p>MCA file format (Anvil):
 * <pre>
 *   Header: 8 KiB
 *     4096 bytes — location table (1024 × 4-byte big-endian entries)
 *        top 3 bytes: sector offset (4096-byte sectors from file start)
 *        last byte:   sector count (ceiling of compressed data size / 4096)
 *        value 0 means chunk not present
 *     4096 bytes — timestamp table (1024 × 4-byte big-endian Unix timestamps)
 *   Body:
 *     Chunk data at (sectorOffset * 4096):
 *       4 bytes: exact byte length of compressed data (big-endian)
 *       1 byte:  compression type (1=GZip, 2=Zlib, 3=uncompressed)
 *       N bytes: compressed chunk NBT
 * </pre>
 */
public final class McaChunkReader {

    private McaChunkReader() {}

    /** A container discovered from raw chunk NBT. */
    public record ContainerFromNbt(
            String containerType,   // "CHEST", "ITEM_FRAME", etc.
            int worldX,
            int worldY,
            int worldZ,
            List<SlotItemNbt> items
    ) {}

    /** An item at a specific slot within a container. */
    public record SlotItemNbt(int slot, org.bukkit.inventory.ItemStack item) {}

    /**
     * Read container items from a single chunk stored in a region file.
     * Does NOT load the chunk into the server — pure disk read.
     *
     * @param worldFolder        the world's root directory ({@code world.getWorldFolder()})
     * @param cx                 chunk X coordinate (blockX &gt;&gt; 4)
     * @param cz                 chunk Z coordinate (blockZ &gt;&gt; 4)
     * @param registryAccess     NMS registry access for item deserialization
     * @param includeItemFrames  whether to extract item frame items
     * @param includeArmorStands whether to extract armor stand equipment
     * @param includeMinecarts   whether to extract minecart container inventories
     * @return list of containers with their items (empty on failure or no data)
     */
    public static List<ContainerFromNbt> readChunkItems(
            File worldFolder, int cx, int cz,
            RegistryAccess registryAccess,
            boolean includeItemFrames,
            boolean includeArmorStands,
            boolean includeMinecarts,
            boolean includeChestBoats,
            boolean includeEntityEquipment) {

        int rx = cx >> 5;
        int rz = cz >> 5;
        File regionFile = new File(worldFolder, "region/r." + rx + "." + rz + ".mca");
        if (!regionFile.exists()) {
            return Collections.emptyList();
        }

        int localX = cx & 31;
        int localZ = cz & 31;

        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            // Read header: location entry at index (localX + localZ * 32)
            int headerIndex = localX + localZ * 32;
            raf.seek(headerIndex * 4L);

            int location = readIntBE(raf);
            if (location == 0) {
                return Collections.emptyList(); // chunk not present in this region
            }

            int sectorOffset = (location >> 8) & 0xFFFFFF;
            // int sectorCount = location & 0xFF; // not needed for reading

            // Seek to chunk data
            long dataOffset = (long) sectorOffset * 4096L;
            raf.seek(dataOffset);

            int length = readIntBE(raf);
            // Sanity check: compressed chunk data should be between 1 byte and 1 MiB
            if (length <= 0 || length > 1024 * 1024) {
                return Collections.emptyList();
            }

            byte compressionType = raf.readByte();

            byte[] compressed = new byte[length];
            raf.readFully(compressed);

            // Decompress chunk NBT
            CompoundTag root;
            try (InputStream decompressed = decompress(compressed, compressionType)) {
                root = NbtIo.read(new DataInputStream(decompressed));
            }

            if (root == null) {
                return Collections.emptyList();
            }

            List<ContainerFromNbt> containers = new ArrayList<>();

            // Extract containers from block entities and entities
            extractBlockEntities(root, cx, cz, registryAccess, containers);
            extractEntities(root, cx, cz, registryAccess,
                    includeItemFrames, includeArmorStands, includeMinecarts, includeChestBoats,
                    includeEntityEquipment, containers);

            return containers;

        } catch (Exception e) {
            // Caller logs — this is a silent data path
            return Collections.emptyList();
        }
    }

    // ==================== Internal helpers ====================

    /** Read a 4-byte big-endian integer from a RandomAccessFile. */
    private static int readIntBE(RandomAccessFile raf) throws IOException {
        return ((raf.readByte() & 0xFF) << 24)
             | ((raf.readByte() & 0xFF) << 16)
             | ((raf.readByte() & 0xFF) << 8)
             |  (raf.readByte() & 0xFF);
    }

    /** Decompress chunk data based on compression type. */
    private static InputStream decompress(byte[] data, byte compressionType) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        return switch (compressionType) {
            case 1 -> new GZIPInputStream(bais);        // GZip (rare)
            case 2 -> new InflaterInputStream(bais);    // Zlib (default)
            case 3 -> bais;                              // uncompressed
            default -> throw new IOException("Unknown compression type: " + compressionType);
        };
    }

    /**
     * Convert a block entity's stored position to world block coordinates.
     *
     * <p>Three formats are possible depending on Minecraft version and chunk format:
     * <ol>
     *   <li><b>No {@code z} tag</b> — packed format (MC ≥ 1.18): {@code x} encodes
     *       localX | (localZ &lt;&lt; 4) | (sectionIndex &lt;&lt; 8).</li>
     *   <li><b>{@code z} tag present, x and z in 0–15</b> — legacy local format:
     *       x,z are chunk-relative (0–15), must add cx*16 / cz*16.</li>
     *   <li><b>{@code z} tag present, x or z outside 0–15</b> — absolute world
     *       coordinates (modern Paper/Spigot saves BlockPos directly). Use as-is.</li>
     * </ol>
     */
    private static int[] unpackBlockEntityPos(CompoundTag be, int cx, int cz) {
        int worldX, worldY, worldZ;
        int y = be.getInt("y");
        if (!be.contains("z")) {
            // Packed format: no z tag, x = localX | (localZ << 4) | (section << 8)
            int packed = be.getInt("x");
            int lx = packed & 0xF;
            int lz = (packed >> 4) & 0xF;
            worldX = cx * 16 + lx;
            worldY = y;
            worldZ = cz * 16 + lz;
        } else {
            int x = be.getInt("x");
            int z = be.getInt("z");
            if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                // Legacy local format: x,z in 0–15, relative to chunk corner
                worldX = cx * 16 + x;
                worldY = y;
                worldZ = cz * 16 + z;
            } else {
                // Absolute world coordinates (Paper/Spigot saves BlockPos directly)
                worldX = x;
                worldY = y;
                worldZ = z;
            }
        }
        return new int[]{worldX, worldY, worldZ};
    }

    // ==================== Block entity extraction ====================

    private static void extractBlockEntities(CompoundTag root, int cx, int cz,
            RegistryAccess registryAccess, List<ContainerFromNbt> containers) {
        if (!root.contains("block_entities", Tag.TAG_LIST)) return;

        ListTag blockEntities = root.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag be = blockEntities.getCompound(i);
            String id = be.getString("id");
            String containerType = ContainerUtil.getContainerTypeNameFromNbtId(id);
            if (containerType == null) continue;

            // Some containers (e.g., lectern) have a "Book" tag instead of "Items"
            ListTag itemsTag = null;
            if (be.contains("Items", Tag.TAG_LIST)) {
                itemsTag = be.getList("Items", Tag.TAG_COMPOUND);
            }
            // Lectern has a single Book item
            if (containerType.equals("LECTERN") && be.contains("Book", Tag.TAG_COMPOUND)) {
                CompoundTag bookTag = be.getCompound("Book");
                ListTag wrapperList = new ListTag();
                wrapperList.add(bookTag);
                itemsTag = wrapperList;
            }

            if (itemsTag == null || itemsTag.isEmpty()) continue;

            List<SlotItemNbt> items = parseItemsList(itemsTag, registryAccess);
            if (items.isEmpty()) continue;

            int[] pos = unpackBlockEntityPos(be, cx, cz);
            containers.add(new ContainerFromNbt(containerType, pos[0], pos[1], pos[2], items));
        }
    }

    // ==================== Entity extraction ====================

    private static void extractEntities(CompoundTag root, int cx, int cz,
            RegistryAccess registryAccess,
            boolean includeItemFrames, boolean includeArmorStands, boolean includeMinecarts,
            boolean includeChestBoats, boolean includeEntityEquipment,
            List<ContainerFromNbt> containers) {
        if (!root.contains("Entities", Tag.TAG_LIST)) return;

        ListTag entities = root.getList("Entities", Tag.TAG_LIST);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompound(i);
            String id = entity.getString("id");

            boolean isItemFrame = id.equals("minecraft:item_frame")
                    || id.equals("minecraft:glow_item_frame");
            boolean isArmorStand = id.equals("minecraft:armor_stand");
            boolean isChestMinecart = id.equals("minecraft:chest_minecart");
            boolean isHopperMinecart = id.equals("minecraft:hopper_minecart");
            boolean isChestBoat = id.endsWith("_chest_boat") || id.endsWith("_chest_raft");
            boolean isPlayer = id.equals("minecraft:player");
            boolean isKnownType = isItemFrame || isArmorStand || isChestMinecart || isHopperMinecart || isChestBoat || isPlayer;

            if (isItemFrame && !includeItemFrames) continue;
            if (isArmorStand && !includeArmorStands) continue;
            if ((isChestMinecart || isHopperMinecart) && !includeMinecarts) continue;
            if (isChestBoat && !includeChestBoats) continue;
            if (isPlayer) continue; // Players scanned separately by PlayerScanner
            if (!isKnownType && !includeEntityEquipment) {
                continue; // not an entity type we care about
            }
            // For entity equipment: any unknown entity type with gear is a candidate
            if (!isKnownType && includeEntityEquipment) {
                boolean hasEquipment = entity.contains("HandItems", Tag.TAG_LIST)
                        || entity.contains("ArmorItems", Tag.TAG_LIST);
                if (!hasEquipment) continue;
            }

            // Read position
            if (!entity.contains("Pos", Tag.TAG_LIST)) continue;
            ListTag posList = entity.getList("Pos", Tag.TAG_DOUBLE);
            int worldX = (int) Math.floor(posList.getDouble(0));
            int worldY = (int) Math.floor(posList.getDouble(1));
            int worldZ = (int) Math.floor(posList.getDouble(2));

            List<SlotItemNbt> items = null;
            String containerType = null;

            if (isItemFrame) {
                if (entity.contains("Item", Tag.TAG_COMPOUND)) {
                    CompoundTag itemTag = entity.getCompound("Item");
                    var bukkitItem = parseSingleItem(itemTag, registryAccess);
                    if (bukkitItem != null) {
                        items = List.of(new SlotItemNbt(0, bukkitItem));
                        containerType = "ITEM_FRAME";
                    }
                }
            } else if (isArmorStand) {
                items = new ArrayList<>();
                // ArmorItems: helmet(100), chestplate(101), leggings(102), boots(103)
                if (entity.contains("ArmorItems", Tag.TAG_LIST)) {
                    ListTag armorItems = entity.getList("ArmorItems", Tag.TAG_COMPOUND);
                    for (int s = 0; s < armorItems.size() && s < 4; s++) {
                        var bukkitItem = parseSingleItem(armorItems.getCompound(s), registryAccess);
                        if (bukkitItem != null && !bukkitItem.getType().isAir()) {
                            items.add(new SlotItemNbt(100 + s, bukkitItem));
                        }
                    }
                }
                // HandItems: mainhand(0), offhand(40)
                if (entity.contains("HandItems", Tag.TAG_LIST)) {
                    ListTag handItems = entity.getList("HandItems", Tag.TAG_COMPOUND);
                    for (int s = 0; s < handItems.size() && s < 2; s++) {
                        var bukkitItem = parseSingleItem(handItems.getCompound(s), registryAccess);
                        if (bukkitItem != null && !bukkitItem.getType().isAir()) {
                            items.add(new SlotItemNbt(s == 0 ? 0 : 40, bukkitItem));
                        }
                    }
                }
                if (!items.isEmpty()) containerType = "ARMOR_STAND";
            } else if (isChestMinecart || isHopperMinecart) {
                if (entity.contains("Items", Tag.TAG_LIST)) {
                    ListTag cartItems = entity.getList("Items", Tag.TAG_LIST);
                    items = parseItemsList(cartItems, registryAccess);
                    if (!items.isEmpty()) {
                        containerType = isChestMinecart ? "MINECART_CHEST" : "MINECART_HOPPER";
                    }
                }
            } else if (isChestBoat) {
                if (entity.contains("Items", Tag.TAG_LIST)) {
                    ListTag boatItems = entity.getList("Items", Tag.TAG_LIST);
                    items = parseItemsList(boatItems, registryAccess);
                    if (!items.isEmpty()) {
                        containerType = "CHEST_BOAT";
                    }
                }
            } else {
                // Living entity equipment (mobs wearing/holding gear)
                items = new ArrayList<>();
                // ArmorItems: helmet(100), chestplate(101), leggings(102), boots(103)
                if (entity.contains("ArmorItems", Tag.TAG_LIST)) {
                    ListTag armorItems = entity.getList("ArmorItems", Tag.TAG_COMPOUND);
                    for (int s = 0; s < armorItems.size() && s < 4; s++) {
                        var bukkitItem = parseSingleItem(armorItems.getCompound(s), registryAccess);
                        if (bukkitItem != null && !bukkitItem.getType().isAir()) {
                            items.add(new SlotItemNbt(100 + s, bukkitItem));
                        }
                    }
                }
                // HandItems: mainhand(0), offhand(40)
                if (entity.contains("HandItems", Tag.TAG_LIST)) {
                    ListTag handItems = entity.getList("HandItems", Tag.TAG_COMPOUND);
                    for (int s = 0; s < handItems.size() && s < 2; s++) {
                        var bukkitItem = parseSingleItem(handItems.getCompound(s), registryAccess);
                        if (bukkitItem != null && !bukkitItem.getType().isAir()) {
                            items.add(new SlotItemNbt(s == 0 ? 0 : 40, bukkitItem));
                        }
                    }
                }
                if (!items.isEmpty()) containerType = "ENTITY_EQUIPMENT";
            }

            if (containerType != null && items != null && !items.isEmpty()) {
                containers.add(new ContainerFromNbt(containerType, worldX, worldY, worldZ, items));
            }
        }
    }

    // ==================== Item parsing ====================

    /**
     * Parse a list of item NBT compounds (e.g., the "Items" tag inside a container).
     */
    private static List<SlotItemNbt> parseItemsList(ListTag itemsTag, RegistryAccess registryAccess) {
        List<SlotItemNbt> items = new ArrayList<>();
        for (int i = 0; i < itemsTag.size(); i++) {
            CompoundTag itemTag = itemsTag.getCompound(i);
            var bukkitItem = parseSingleItem(itemTag, registryAccess);
            if (bukkitItem != null && !bukkitItem.getType().isAir()) {
                int slot = itemTag.contains("Slot", Tag.TAG_BYTE)
                        ? itemTag.getByte("Slot") & 0xFF
                        : i;
                items.add(new SlotItemNbt(slot, bukkitItem));
            }
        }
        return items;
    }

    /**
     * Parse a single item NBT compound to a Bukkit ItemStack.
     * Same approach as {@code NbtUtil.readPlayerInventory()}: use
     * {@code ItemStack.parseOptional(registryAccess, tag)} then convert via
     * {@code CraftItemStack.asBukkitCopy()}.
     *
     * @return the parsed item, or null if the tag is empty or parsing fails
     */
    private static org.bukkit.inventory.ItemStack parseSingleItem(
            CompoundTag itemTag, RegistryAccess registryAccess) {
        if (itemTag.isEmpty()) return null;
        try {
            net.minecraft.world.item.ItemStack nmsItem =
                    net.minecraft.world.item.ItemStack.parseOptional(registryAccess, itemTag);
            if (nmsItem.isEmpty()) return null;
            return CraftItemStack.asBukkitCopy(nmsItem);
        } catch (Exception e) {
            return null; // malformed item tag — skip silently
        }
    }
}
