package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.logging.Level;

/**
 * Validates attribute modifiers using a whitelist approach.
 * Builds the whitelist from vanilla item prototypes via NMS access.
 */
public class AttributeValidator implements ItemValidator {

    private final IllegalScanner plugin;
    private final Map<Material, Set<AttributeSpec>> whitelist = new EnumMap<>(Material.class);
    private final Set<Material> materialsWithNoAttributes = EnumSet.noneOf(Material.class);

    public AttributeValidator(IllegalScanner plugin) {
        this.plugin = plugin;
        buildWhitelist();
    }

    private void buildWhitelist() {
        int count = 0;
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isLegacy()) continue;
            try {
                org.bukkit.inventory.ItemStack bukkitProto = new ItemStack(material);
                List<ItemAccessor.AttributeModifierEntry> entries =
                        ItemAccessor.getAttributeModifiers(bukkitProto);
                if (entries.isEmpty()) {
                    materialsWithNoAttributes.add(material);
                    continue;
                }
                Set<AttributeSpec> specs = new HashSet<>();
                for (var e : entries) {
                    specs.add(new AttributeSpec(e.attributeName(), e.value(), e.operation(), e.slot()));
                }
                whitelist.put(material, specs);
                count++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE,
                        "Skip prototype for " + material + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Attribute whitelist: " + count + " item types, " +
                materialsWithNoAttributes.size() + " no-attribute types.");
    }

    @Override
    public String getName() { return "AttributeValidator"; }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        Material material = itemStack.getType();
        List<ItemAccessor.AttributeModifierEntry> entries =
                ItemAccessor.getAttributeModifiers(itemStack);

        if (entries.isEmpty()) return violations;

        // This material should NOT have any attributes
        if (materialsWithNoAttributes.contains(material)) {
            for (var e : entries) {
                violations.add(Violation.illegal("ATTR_NOT_IN_WHITELIST",
                        msg("ATTR_NOT_IN_WHITELIST",
                                "{attr}", e.attributeName(),
                                "{item}", material.name())));
            }
            return violations;
        }

        Set<AttributeSpec> allowedSpecs = whitelist.get(material);
        if (allowedSpecs == null || allowedSpecs.isEmpty()) {
            for (var e : entries) {
                violations.add(Violation.illegal("ATTR_NOT_IN_WHITELIST",
                        msg("ATTR_NOT_IN_WHITELIST",
                                "{attr}", e.attributeName(),
                                "{item}", material.name())));
            }
            return violations;
        }

        int allowedCount = allowedSpecs.size();
        if (entries.size() > allowedCount) {
            violations.add(Violation.illegal("ATTR_EXTRA_MODIFIER",
                    msg("ATTR_EXTRA_MODIFIER",
                            "{item}", material.name(),
                            "{found}", String.valueOf(entries.size()),
                            "{expected}", String.valueOf(allowedCount))));
        }

        for (var e : entries) {
            AttributeSpec itemSpec = new AttributeSpec(
                    e.attributeName(), e.value(), e.operation(), e.slot());
            boolean matched = allowedSpecs.stream().anyMatch(a -> a.matches(itemSpec));
            if (!matched) {
                boolean attrNameFound = allowedSpecs.stream()
                        .anyMatch(a -> a.attributeName.equals(e.attributeName()));
                if (attrNameFound) {
                    violations.add(Violation.illegal("ATTR_VALUE_MISMATCH",
                            msg("ATTR_VALUE_MISMATCH",
                                    "{attr}", e.attributeName(),
                                    "{found}", String.format("%.2f", e.value()),
                                    "{item}", material.name())));
                } else {
                    violations.add(Violation.illegal("ATTR_NOT_IN_WHITELIST",
                            msg("ATTR_NOT_IN_WHITELIST",
                                    "{attr}", e.attributeName(),
                                    "{item}", material.name())));
                }
            }
        }

        return violations;
    }

    private record AttributeSpec(String attributeName, double value, int operation, String slot) {
        boolean matches(AttributeSpec other) {
            return this.attributeName.equals(other.attributeName)
                    && Math.abs(this.value - other.value) < 0.0001
                    && this.operation == other.operation
                    && this.slot.equals(other.slot);
        }
    }

    private String msg(String key, String... r) {
        String t = plugin.getConfigManager().getConfig()
                .getString("violation_messages." + key, key);
        for (int i = 0; i < r.length; i += 2) t = t.replace(r[i], r[i + 1]);
        return t;
    }
}
