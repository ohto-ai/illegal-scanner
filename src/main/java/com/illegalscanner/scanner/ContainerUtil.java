package com.illegalscanner.scanner;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Utility methods for discovering containers and their items in the world.
 */
public final class ContainerUtil {

    private ContainerUtil() {}

    /**
     * Represents a discovered container with its location info.
     */
    public record ContainerInfo(
            Location location,
            String containerType,    // e.g. "CHEST", "ITEM_FRAME", "ARMOR_STAND"
            String entityType,       // null for block containers
            String entityUuid,       // null for block containers
            List<SlotItem> items
    ) {}

    /**
     * An item at a specific slot in a container.
     */
    public record SlotItem(int slot, ItemStack item) {}

    /**
     * Find all containers in a chunk and collect their items.
     */
    public static List<ContainerInfo> findContainers(Chunk chunk, boolean includeItemFrames,
                                                     boolean includeArmorStands, boolean includeMinecarts) {
        List<ContainerInfo> containers = new ArrayList<>();
        World world = chunk.getWorld();

        // --- Block containers (tile entities) ---
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Container container) {
                // Skip if the block type is one we don't want
                Material type = state.getType();
                String containerType = getContainerTypeName(type);
                if (containerType != null) {
                    List<SlotItem> items = collectItems(container.getInventory());
                    if (!items.isEmpty()) {
                        containers.add(new ContainerInfo(
                                state.getLocation(), containerType, null, null, items));
                    }
                }
            }
        }

        // --- Entity containers ---
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            // Item Frames
            if (includeItemFrames && entity instanceof ItemFrame frame) {
                ItemStack item = frame.getItem();
                if (!item.getType().isAir()) {
                    containers.add(new ContainerInfo(
                            entity.getLocation(),
                            "ITEM_FRAME",
                            entity.getType().name(),
                            entity.getUniqueId().toString(),
                            List.of(new SlotItem(0, item))
                    ));
                }
            }

            // Armor Stands
            if (includeArmorStands && entity instanceof ArmorStand stand) {
                List<SlotItem> items = collectArmorStandItems(stand);
                if (!items.isEmpty()) {
                    containers.add(new ContainerInfo(
                            entity.getLocation(),
                            "ARMOR_STAND",
                            entity.getType().name(),
                            entity.getUniqueId().toString(),
                            items
                    ));
                }
            }

            // Minecart containers
            if (includeMinecarts) {
                if (entity instanceof StorageMinecart cart) {
                    List<SlotItem> items = collectItems(cart.getInventory());
                    if (!items.isEmpty()) {
                        containers.add(new ContainerInfo(
                                entity.getLocation(),
                                "MINECART_CHEST",
                                entity.getType().name(),
                                entity.getUniqueId().toString(),
                                items
                        ));
                    }
                } else if (entity instanceof HopperMinecart cart) {
                    List<SlotItem> items = collectItems(cart.getInventory());
                    if (!items.isEmpty()) {
                        containers.add(new ContainerInfo(
                                entity.getLocation(),
                                "MINECART_HOPPER",
                                entity.getType().name(),
                                entity.getUniqueId().toString(),
                                items
                        ));
                    }
                }
            }
        }

        return containers;
    }

    /**
     * Collect all non-air items from an inventory.
     */
    public static List<SlotItem> collectItems(Inventory inventory) {
        List<SlotItem> items = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir()) {
                items.add(new SlotItem(i, item.clone())); // Clone to be safe
            }
        }
        return items;
    }

    /**
     * Collect equipped items from an armor stand.
     */
    private static List<SlotItem> collectArmorStandItems(ArmorStand stand) {
        List<SlotItem> items = new ArrayList<>();
        // Equipment slots: 0=helmet, 1=chestplate, 2=leggings, 3=boots
        // 100+ offsets to distinguish from regular inventory slots
        ItemStack helmet = stand.getEquipment().getHelmet();
        if (helmet != null && !helmet.getType().isAir()) {
            items.add(new SlotItem(100, helmet.clone()));
        }
        ItemStack chestplate = stand.getEquipment().getChestplate();
        if (chestplate != null && !chestplate.getType().isAir()) {
            items.add(new SlotItem(101, chestplate.clone()));
        }
        ItemStack leggings = stand.getEquipment().getLeggings();
        if (leggings != null && !leggings.getType().isAir()) {
            items.add(new SlotItem(102, leggings.clone()));
        }
        ItemStack boots = stand.getEquipment().getBoots();
        if (boots != null && !boots.getType().isAir()) {
            items.add(new SlotItem(103, boots.clone()));
        }
        // Hand items
        ItemStack mainHand = stand.getEquipment().getItemInMainHand();
        if (mainHand != null && !mainHand.getType().isAir()) {
            items.add(new SlotItem(0, mainHand.clone()));
        }
        ItemStack offHand = stand.getEquipment().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) {
            items.add(new SlotItem(40, offHand.clone()));
        }
        return items;
    }

    /**
     * Map block material to a human-readable container type name.
     */
    private static String getContainerTypeName(Material material) {
        return switch (material) {
            case CHEST -> "CHEST";
            case TRAPPED_CHEST -> "TRAPPED_CHEST";
            case BARREL -> "BARREL";
            case DISPENSER -> "DISPENSER";
            case DROPPER -> "DROPPER";
            case HOPPER -> "HOPPER";
            case FURNACE -> "FURNACE";
            case BLAST_FURNACE -> "BLAST_FURNACE";
            case SMOKER -> "SMOKER";
            case BREWING_STAND -> "BREWING_STAND";
            case SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX,
                 MAGENTA_SHULKER_BOX, LIGHT_BLUE_SHULKER_BOX,
                 YELLOW_SHULKER_BOX, LIME_SHULKER_BOX, PINK_SHULKER_BOX,
                 GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX, CYAN_SHULKER_BOX,
                 PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX, BROWN_SHULKER_BOX,
                 GREEN_SHULKER_BOX, RED_SHULKER_BOX, BLACK_SHULKER_BOX -> "SHULKER_BOX";
            case LECTERN -> "LECTERN";
            case CHISELED_BOOKSHELF -> "CHISELED_BOOKSHELF";
            case DECORATED_POT -> "DECORATED_POT";
            case CRAFTER -> "CRAFTER";
            default -> null; // Not a container we care about
        };
    }

    /**
     * Format a location as a compact string.
     */
    public static String formatLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
