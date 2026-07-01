package com.illegalscanner.scanner;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.*;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapter for accessing Minecraft 1.21 data components on ItemStacks.
 * Uses paperweight userdev NMS access (Mojang mappings).
 */
public final class ItemAccessor {

    private ItemAccessor() {}

    // ==================== Conversion ====================

    public static net.minecraft.world.item.ItemStack toNms(ItemStack bukkit) {
        return CraftItemStack.asNMSCopy(bukkit);
    }

    public static ItemStack fromNms(net.minecraft.world.item.ItemStack nms) {
        return CraftItemStack.asBukkitCopy(nms);
    }

    // ==================== Component Checks ====================

    /** Check if the unbreakable component is present and true */
    public static boolean isUnbreakable(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.UNBREAKABLE) &&
               nms.get(DataComponents.UNBREAKABLE).showInTooltip();
    }

    /** Check if item has lore */
    public static boolean hasLore(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.LORE);
    }

    /** Get lore lines as plain text strings */
    public static List<String> getLoreLines(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<String> lines = new ArrayList<>();
        if (nms.has(DataComponents.LORE)) {
            ItemLore lore = nms.get(DataComponents.LORE);
            if (lore != null) {
                for (var line : lore.lines()) {
                    lines.add(line.getString());
                }
            }
        }
        return lines;
    }

    /** Check if item has item_name component (overrides default name) */
    public static boolean hasItemName(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.ITEM_NAME);
    }

    /** Get item_name plain text */
    public static String getItemName(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.ITEM_NAME)) {
            return nms.get(DataComponents.ITEM_NAME).getString();
        }
        return null;
    }

    /** Check if item has custom_name component */
    public static boolean hasCustomName(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CUSTOM_NAME);
    }

    /** Get custom_name plain text */
    public static String getCustomNamePlain(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.CUSTOM_NAME)) {
            return nms.get(DataComponents.CUSTOM_NAME).getString();
        }
        return null;
    }

    /**
     * Check if custom_name has any style formatting beyond plain text.
     * Anvil renames produce a plain literal text component with empty/default style.
     * Any non-empty style (color, bold, italic override, etc.) = command/creative only.
     */
    public static boolean isCustomNameStyled(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.CUSTOM_NAME)) {
            var name = nms.get(DataComponents.CUSTOM_NAME);
            return name != null && !name.getStyle().isEmpty();
        }
        return false;
    }

    /** Check for can_place_on component */
    public static boolean hasCanPlaceOn(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CAN_PLACE_ON);
    }

    /** Check for can_destroy component */
    public static boolean hasCanDestroy(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CAN_BREAK);
    }

    /** Get max_stack_size override if present */
    public static Integer getMaxStackSize(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.MAX_STACK_SIZE)) {
            return nms.get(DataComponents.MAX_STACK_SIZE);
        }
        return null;
    }

    /** Get max_damage override if present */
    public static Integer getMaxDamage(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.MAX_DAMAGE)) {
            return nms.get(DataComponents.MAX_DAMAGE);
        }
        return null;
    }

    /** Check for custom_model_data component */
    public static boolean hasCustomModelData(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CUSTOM_MODEL_DATA);
    }

    /** Check for hide_tooltip component */
    public static boolean hasHideTooltip(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.HIDE_TOOLTIP);
    }

    // ==================== Attribute Modifiers ====================

    /**
     * Get the attribute modifiers on an item.
     * Returns the vanilla prototype's modifiers for fresh items,
     * or the modified attributes for custom items.
     */
    public static List<AttributeModifierEntry> getAttributeModifiers(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<AttributeModifierEntry> result = new ArrayList<>();

        if (nms.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers modifiers = nms.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers != null) {
                for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                    result.add(new AttributeModifierEntry(
                            entry.attribute().getRegisteredName(),
                            entry.modifier().amount(),
                            entry.modifier().operation().ordinal(),
                            entry.slot().getSerializedName()
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Get the vanilla prototype attribute modifiers for a material.
     */
    public static List<AttributeModifierEntry> getPrototypeAttributeModifiers(
            net.minecraft.world.item.Item nmsItem) {
        List<AttributeModifierEntry> result = new ArrayList<>();
        net.minecraft.world.item.ItemStack prototype = new net.minecraft.world.item.ItemStack(nmsItem);

        if (prototype.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers modifiers = prototype.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers != null) {
                for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                    result.add(new AttributeModifierEntry(
                            entry.attribute().getRegisteredName(),
                            entry.modifier().amount(),
                            entry.modifier().operation().ordinal(),
                            entry.slot().getSerializedName()
                    ));
                }
            }
        }
        return result;
    }

    public record AttributeModifierEntry(
            String attributeName,
            double value,
            int operation,
            String slot
    ) {}

    // ==================== Potion ====================

    /** Check if item has potion_contents */
    public static boolean hasPotionContents(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.POTION_CONTENTS);
    }

    /** Get custom potion effects (list of effect type + amplifier + duration) */
    public static List<PotionEffectEntry> getCustomPotionEffects(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<PotionEffectEntry> result = new ArrayList<>();
        if (nms.has(DataComponents.POTION_CONTENTS)) {
            var contents = nms.get(DataComponents.POTION_CONTENTS);
            if (contents != null) {
                for (var effect : contents.customEffects()) {
                    result.add(new PotionEffectEntry(
                            effect.getEffect().getRegisteredName(),
                            effect.getAmplifier(),
                            effect.getDuration(),
                            effect.getEffect().value().isInstantenous()
                    ));
                }
            }
        }
        return result;
    }

    /** Get custom potion color if present */
    public static Integer getPotionColor(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.POTION_CONTENTS)) {
            var contents = nms.get(DataComponents.POTION_CONTENTS);
            if (contents != null) {
                Optional<Integer> color = contents.customColor();
                return color.isPresent() ? color.get() : null;
            }
        }
        return null;
    }

    public record PotionEffectEntry(
            String effectKey,
            int amplifier,
            int duration,
            boolean isInstant
    ) {}

    // ==================== Written Book ====================

    /** Check if item has written_book_content */
    public static boolean hasWrittenBookContent(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.WRITTEN_BOOK_CONTENT);
    }

    /** Get written book pages */
    public static List<String> getBookPages(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<String> pages = new ArrayList<>();
        if (nms.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            var content = nms.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content != null) {
                for (var page : content.pages()) {
                    pages.add(page.raw().getString());
                }
            }
        }
        return pages;
    }

    /** Get written book author */
    public static String getBookAuthor(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            var content = nms.get(DataComponents.WRITTEN_BOOK_CONTENT);
            return content != null ? content.author() : null;
        }
        return null;
    }

    /** Get written book title as plain text */
    public static String getBookTitlePlain(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
            var content = nms.get(DataComponents.WRITTEN_BOOK_CONTENT);
            return content != null ? content.title().raw() : null;
        }
        return null;
    }

    // ==================== Fireworks ====================

    /** Check if item has fireworks component */
    public static boolean hasFireworks(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.FIREWORKS);
    }

    /** Get firework flight duration */
    public static int getFireworkFlight(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.FIREWORKS)) {
            var fw = nms.get(DataComponents.FIREWORKS);
            return fw != null ? fw.flightDuration() : 0;
        }
        return 0;
    }

    /** Get firework explosion count */
    public static int getFireworkExplosionCount(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.FIREWORKS)) {
            var fw = nms.get(DataComponents.FIREWORKS);
            return fw != null ? fw.explosions().size() : 0;
        }
        return 0;
    }

    /** Check if item has single firework explosion */
    public static boolean hasFireworkExplosion(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.FIREWORK_EXPLOSION);
    }

    // ==================== Container Items ====================

    /** Check if item has bundle_contents */
    public static boolean hasBundleContents(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.BUNDLE_CONTENTS);
    }

    /** Get items inside a bundle */
    public static List<ItemStack> getBundleItems(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<ItemStack> items = new ArrayList<>();
        if (nms.has(DataComponents.BUNDLE_CONTENTS)) {
            var contents = nms.get(DataComponents.BUNDLE_CONTENTS);
            if (contents != null) {
                for (var stack : contents.items()) {
                    items.add(fromNms(stack));
                }
            }
        }
        return items;
    }

    /**
     * Check if item has container contents (block entity inventory).
     * This is the "minecraft:container" component — non-empty means
     * the item was created via Ctrl+Pick Block on a filled container,
     * which is NEVER obtainable in survival.
     */
    public static boolean hasContainerContents(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CONTAINER);
    }

    /**
     * Get the number of item stacks stored in the container component.
     * Returns 0 if not present.
     */
    public static int getContainerItemCount(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        if (nms.has(DataComponents.CONTAINER)) {
            var container = nms.get(DataComponents.CONTAINER);
            if (container != null) {
                int count = 0;
                for (var stack : container.nonEmptyItems()) {
                    if (!stack.isEmpty()) count++;
                }
                return count;
            }
        }
        return 0;
    }

    /**
     * Extract all items from the container component (block entity inventory).
     * These are the items you'd get from Ctrl+Pick Block on a filled container.
     */
    public static List<ItemStack> getContainerItems(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        List<ItemStack> items = new ArrayList<>();
        if (nms.has(DataComponents.CONTAINER)) {
            var container = nms.get(DataComponents.CONTAINER);
            if (container != null) {
                for (var stack : container.nonEmptyItems()) {
                    items.add(fromNms(stack));
                }
            }
        }
        return items;
    }

    /**
     * Check if item has container_loot component (loot table reference).
     * This should never persist on an item — blocks lose this when broken.
     */
    public static boolean hasContainerLoot(ItemStack item) {
        net.minecraft.world.item.ItemStack nms = toNms(item);
        return nms.has(DataComponents.CONTAINER_LOOT);
    }
}
