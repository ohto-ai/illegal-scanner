package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates all validators and runs them against items.
 */
public class ValidationEngine {

    private final IllegalScanner plugin;
    private final List<ItemValidator> validators = new ArrayList<>();

    public ValidationEngine(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialize and register all validators.
     */
    public void initialize() {
        // Register validators in order (order matters for performance:
        // faster checks first to allow early exit optimizations)
        validators.add(new EnchantValidator(plugin));
        validators.add(new AttributeValidator(plugin));
        validators.add(new ComponentValidator(plugin));
        validators.add(new PotionValidator(plugin));
        validators.add(new BookValidator(plugin));
        validators.add(new FireworkValidator(plugin));
        // WatchListValidator: cheap material-ID check (before heavier NBT validators)
        validators.add(new WatchListValidator(plugin));
        // ContainerValidator runs last as it triggers recursive validation
        validators.add(new ContainerValidator(plugin));
    }

    /**
     * Validate a single item against all registered validators.
     *
     * @param itemStack the item to validate (null or air returns empty list)
     * @return aggregate list of all violations found
     */
    public List<Violation> validate(ItemStack itemStack) {
        return validate(itemStack, null);
    }

    /**
     * Validate a single item against all registered validators,
     * with optional location context for region whitelist checks.
     *
     * @param itemStack the item to validate (null or air returns empty list)
     * @param location  the location of the item (null if not applicable)
     * @return aggregate list of all violations found
     */
    public List<Violation> validate(ItemStack itemStack, Location location) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return Collections.emptyList();
        }

        // FAST PATH: Check item whitelist
        if (plugin.getItemWhitelistManager() != null
                && plugin.getItemWhitelistManager().isWhitelisted(itemStack)) {
            return Collections.emptyList();
        }

        // FAST PATH: Check region whitelist
        if (location != null
                && plugin.getRegionWhitelistManager() != null
                && plugin.getRegionWhitelistManager().isLocationWhitelisted(location)) {
            return Collections.emptyList();
        }

        List<Violation> allViolations = new ArrayList<>();
        for (ItemValidator validator : validators) {
            if (validator.isEnabled()) {
                try {
                    List<Violation> result = validator.validate(itemStack);
                    if (result != null && !result.isEmpty()) {
                        allViolations.addAll(result);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            "Validator " + validator.getName() + " threw exception: " + e.getMessage());
                }
            }
        }
        return allViolations;
    }

    /**
     * Validate all items in a player's full inventory.
     */
    public record PlayerItemViolation(int slot, ItemStack item, List<Violation> violations) {}

    public List<PlayerItemViolation> validatePlayerInventory(PlayerInventory inventory) {
        List<PlayerItemViolation> results = new ArrayList<>();
        // Check all inventory contents
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            List<Violation> violations = validate(item);
            if (!violations.isEmpty()) {
                results.add(new PlayerItemViolation(i, item, violations));
            }
        }
        // Check armor
        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null) {
                List<Violation> violations = validate(armor[i]);
                if (!violations.isEmpty()) {
                    // Armor slots: 100=helmet, 101=chestplate, 102=leggings, 103=boots
                    results.add(new PlayerItemViolation(100 + i, armor[i], violations));
                }
            }
        }
        // Check offhand
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            List<Violation> violations = validate(offhand);
            if (!violations.isEmpty()) {
                results.add(new PlayerItemViolation(40, offhand, violations));
            }
        }
        return results;
    }

    /**
     * Determine the overall severity from a list of violations.
     */
    public ValidationResult getOverallSeverity(List<Violation> violations) {
        if (violations.isEmpty()) return ValidationResult.PASS;
        for (Violation v : violations) {
            if (v.severity() == ValidationResult.ILLEGAL) {
                return ValidationResult.ILLEGAL;
            }
        }
        return ValidationResult.WARN;
    }

    public int getValidatorCount() {
        return validators.size();
    }

    public void registerValidator(ItemValidator validator) {
        validators.add(validator);
    }
}
