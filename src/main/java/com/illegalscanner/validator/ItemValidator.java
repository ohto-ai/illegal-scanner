package com.illegalscanner.validator;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Interface for item validators.
 * Each validator checks a specific aspect of an item (enchantments, attributes, etc.)
 */
public interface ItemValidator {

    /**
     * @return Unique name of this validator, used in reports and config
     */
    String getName();

    /**
     * Validate an item and return all violations found.
     *
     * @param itemStack the item to validate
     * @return list of violations (empty if item is clean)
     */
    List<Violation> validate(ItemStack itemStack);

    /**
     * @return true if this validator is currently enabled
     */
    default boolean isEnabled() {
        return true;
    }
}
