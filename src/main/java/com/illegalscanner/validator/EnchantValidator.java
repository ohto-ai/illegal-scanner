package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Validates enchantment levels, conflicts, and item-type compatibility
 * against vanilla survival rules (Java Edition 1.21).
 */
public class EnchantValidator implements ItemValidator {

    private final IllegalScanner plugin;

    // ==================== Max Level Map ====================
    private static final Map<String, Integer> MAX_LEVELS = new LinkedHashMap<>();

    static {
        MAX_LEVELS.put("aqua_affinity", 1);
        MAX_LEVELS.put("bane_of_arthropods", 5);
        MAX_LEVELS.put("blast_protection", 4);
        MAX_LEVELS.put("breach", 4);
        MAX_LEVELS.put("channeling", 1);
        MAX_LEVELS.put("binding_curse", 1);
        MAX_LEVELS.put("vanishing_curse", 1);
        MAX_LEVELS.put("density", 5);
        MAX_LEVELS.put("depth_strider", 3);
        MAX_LEVELS.put("efficiency", 5);
        MAX_LEVELS.put("feather_falling", 4);
        MAX_LEVELS.put("fire_aspect", 2);
        MAX_LEVELS.put("fire_protection", 4);
        MAX_LEVELS.put("flame", 1);
        MAX_LEVELS.put("fortune", 3);
        MAX_LEVELS.put("frost_walker", 2);
        MAX_LEVELS.put("impaling", 5);
        MAX_LEVELS.put("infinity", 1);
        MAX_LEVELS.put("knockback", 2);
        MAX_LEVELS.put("looting", 3);
        MAX_LEVELS.put("loyalty", 3);
        MAX_LEVELS.put("luck_of_the_sea", 3);
        MAX_LEVELS.put("lure", 3);
        MAX_LEVELS.put("mending", 1);
        MAX_LEVELS.put("multishot", 1);
        MAX_LEVELS.put("piercing", 4);
        MAX_LEVELS.put("power", 5);
        MAX_LEVELS.put("projectile_protection", 4);
        MAX_LEVELS.put("protection", 4);
        MAX_LEVELS.put("punch", 2);
        MAX_LEVELS.put("quick_charge", 3);
        MAX_LEVELS.put("respiration", 3);
        MAX_LEVELS.put("riptide", 3);
        MAX_LEVELS.put("sharpness", 5);
        MAX_LEVELS.put("silk_touch", 1);
        MAX_LEVELS.put("smite", 5);
        MAX_LEVELS.put("soul_speed", 3);
        MAX_LEVELS.put("sweeping_edge", 3);
        MAX_LEVELS.put("swift_sneak", 3);
        MAX_LEVELS.put("thorns", 3);
        MAX_LEVELS.put("unbreaking", 3);
        MAX_LEVELS.put("wind_burst", 3);
    }

    // ==================== Conflict Matrix ====================
    private static final Map<String, Set<String>> CONFLICTS = new LinkedHashMap<>();

    static {
        // Sharpness group
        Set<String> sharpnessGroup = Set.of("sharpness", "smite", "bane_of_arthropods", "breach", "density");
        for (String s : sharpnessGroup) {
            Set<String> conflicts = new HashSet<>(sharpnessGroup);
            conflicts.remove(s);
            CONFLICTS.put(s, conflicts);
        }

        // Protection group
        Set<String> protGroup = Set.of("protection", "fire_protection", "blast_protection", "projectile_protection");
        for (String s : protGroup) {
            Set<String> conflicts = new HashSet<>(protGroup);
            conflicts.remove(s);
            CONFLICTS.put(s, conflicts);
        }

        // Fortune <-> Silk Touch
        CONFLICTS.put("fortune", Set.of("silk_touch"));
        CONFLICTS.put("silk_touch", Set.of("fortune"));

        // Depth Strider <-> Frost Walker
        CONFLICTS.put("depth_strider", Set.of("frost_walker"));
        CONFLICTS.put("frost_walker", Set.of("depth_strider"));

        // Infinity <-> Mending
        CONFLICTS.put("infinity", Set.of("mending"));
        CONFLICTS.put("mending", Set.of("infinity"));

        // Multishot <-> Piercing
        CONFLICTS.put("multishot", Set.of("piercing"));
        CONFLICTS.put("piercing", Set.of("multishot"));

        // Loyalty <-> Riptide, Channeling <-> Riptide
        CONFLICTS.put("loyalty", Set.of("riptide"));
        CONFLICTS.put("riptide", Set.of("loyalty", "channeling"));
        CONFLICTS.put("channeling", Set.of("riptide"));
    }

    // ==================== Item-Type Compatibility ====================
    // Maps enchantment -> set of material name prefixes/categories it can be applied to
    private static final Map<String, Set<Material>> ENCHANT_ITEMS = new LinkedHashMap<>();

    static {
        // --- Swords & Axes ---
        Set<Material> swords = materialSet(
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
            Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD,
            Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE,
            Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE
        );
        Set<Material> swordOnly = materialSet(
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
            Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD
        );

        // --- Tools ---
        Set<Material> tools = materialSet(
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE,
            Material.WOODEN_SHOVEL, Material.STONE_SHOVEL, Material.IRON_SHOVEL,
            Material.GOLDEN_SHOVEL, Material.DIAMOND_SHOVEL, Material.NETHERITE_SHOVEL,
            Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE,
            Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE,
            Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE,
            Material.GOLDEN_HOE, Material.DIAMOND_HOE, Material.NETHERITE_HOE
        );

        Set<Material> pickaxeOnly = materialSet(
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE
        );

        // --- Armor ---
        Set<Material> helmet = materialSet(
            Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET,
            Material.GOLDEN_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
            Material.TURTLE_HELMET
        );
        Set<Material> chestplate = materialSet(
            Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.IRON_CHESTPLATE,
            Material.GOLDEN_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE
        );
        Set<Material> leggings = materialSet(
            Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS,
            Material.GOLDEN_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS
        );
        Set<Material> boots = materialSet(
            Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS,
            Material.GOLDEN_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS
        );
        Set<Material> allArmor = new HashSet<>();
        allArmor.addAll(helmet);
        allArmor.addAll(chestplate);
        allArmor.addAll(leggings);
        allArmor.addAll(boots);

        // --- Specific weapons ---
        Set<Material> trident = materialSet(Material.TRIDENT);
        Set<Material> mace = materialSet(Material.MACE);
        Set<Material> bow = materialSet(Material.BOW);
        Set<Material> crossbow = materialSet(Material.CROSSBOW);
        Set<Material> fishingRod = materialSet(Material.FISHING_ROD);
        Set<Material> shears = materialSet(Material.SHEARS);
        Set<Material> flintAndSteel = materialSet(Material.FLINT_AND_STEEL);
        Set<Material> shield = materialSet(Material.SHIELD);
        Set<Material> elytra = materialSet(Material.ELYTRA);
        Set<Material> brush = materialSet(Material.BRUSH);
        Set<Material> carrotOnAStick = materialSet(Material.CARROT_ON_A_STICK);
        Set<Material> warpedFungusOnAStick = materialSet(Material.WARPED_FUNGUS_ON_A_STICK);
        Set<Material> carvedPumpkin = materialSet(Material.CARVED_PUMPKIN);

        // --- Build mappings ---
        // Aqua Affinity
        ENCHANT_ITEMS.put("aqua_affinity", helmet);
        // Bane of Arthropods
        ENCHANT_ITEMS.put("bane_of_arthropods", swords);
        // Blast Protection
        ENCHANT_ITEMS.put("blast_protection", allArmor);
        // Breach
        ENCHANT_ITEMS.put("breach", mace);
        // Channeling
        ENCHANT_ITEMS.put("channeling", trident);
        // Curse of Binding
        ENCHANT_ITEMS.put("binding_curse", new HashSet<>(allArmor) {{ addAll(elytra); addAll(carvedPumpkin); }});
        // Curse of Vanishing
        Set<Material> vanishingItems = new HashSet<>(allArmor);
        vanishingItems.addAll(tools); vanishingItems.addAll(swordOnly);
        vanishingItems.addAll(fishingRod); vanishingItems.addAll(bow);
        vanishingItems.addAll(shears); vanishingItems.addAll(flintAndSteel);
        vanishingItems.addAll(shield); vanishingItems.addAll(elytra);
        vanishingItems.addAll(carvedPumpkin); vanishingItems.addAll(trident);
        vanishingItems.addAll(crossbow); vanishingItems.addAll(brush); vanishingItems.addAll(mace);
        ENCHANT_ITEMS.put("vanishing_curse", vanishingItems);
        // Density
        ENCHANT_ITEMS.put("density", mace);
        // Depth Strider
        ENCHANT_ITEMS.put("depth_strider", boots);
        // Efficiency
        Set<Material> efficiencyItems = new HashSet<>(tools);
        efficiencyItems.addAll(shears);
        ENCHANT_ITEMS.put("efficiency", efficiencyItems);
        // Feather Falling
        ENCHANT_ITEMS.put("feather_falling", boots);
        // Fire Aspect
        ENCHANT_ITEMS.put("fire_aspect", swords);
        // Fire Protection
        ENCHANT_ITEMS.put("fire_protection", allArmor);
        // Flame
        ENCHANT_ITEMS.put("flame", bow);
        // Fortune
        ENCHANT_ITEMS.put("fortune", tools);
        // Frost Walker
        ENCHANT_ITEMS.put("frost_walker", boots);
        // Impaling
        ENCHANT_ITEMS.put("impaling", trident);
        // Infinity
        ENCHANT_ITEMS.put("infinity", bow);
        // Knockback
        ENCHANT_ITEMS.put("knockback", swords);
        // Looting
        ENCHANT_ITEMS.put("looting", swords);
        // Loyalty
        ENCHANT_ITEMS.put("loyalty", trident);
        // Luck of the Sea
        ENCHANT_ITEMS.put("luck_of_the_sea", fishingRod);
        // Lure
        ENCHANT_ITEMS.put("lure", fishingRod);
        // Mending
        Set<Material> mendingItems = new HashSet<>(allArmor);
        mendingItems.addAll(tools); mendingItems.addAll(swordOnly);
        mendingItems.addAll(fishingRod); mendingItems.addAll(bow);
        mendingItems.addAll(shears); mendingItems.addAll(flintAndSteel);
        mendingItems.addAll(shield); mendingItems.addAll(elytra);
        mendingItems.addAll(trident); mendingItems.addAll(crossbow);
        mendingItems.addAll(brush); mendingItems.addAll(mace);
        mendingItems.addAll(carrotOnAStick); mendingItems.addAll(warpedFungusOnAStick);
        ENCHANT_ITEMS.put("mending", mendingItems);
        // Multishot
        ENCHANT_ITEMS.put("multishot", crossbow);
        // Piercing
        ENCHANT_ITEMS.put("piercing", crossbow);
        // Power
        ENCHANT_ITEMS.put("power", bow);
        // Projectile Protection
        ENCHANT_ITEMS.put("projectile_protection", allArmor);
        // Protection
        ENCHANT_ITEMS.put("protection", allArmor);
        // Punch
        ENCHANT_ITEMS.put("punch", bow);
        // Quick Charge
        ENCHANT_ITEMS.put("quick_charge", crossbow);
        // Respiration
        ENCHANT_ITEMS.put("respiration", helmet);
        // Riptide
        ENCHANT_ITEMS.put("riptide", trident);
        // Sharpness
        ENCHANT_ITEMS.put("sharpness", swords);
        // Silk Touch
        Set<Material> silkTouchItems = new HashSet<>(tools);
        ENCHANT_ITEMS.put("silk_touch", silkTouchItems);
        // Smite
        ENCHANT_ITEMS.put("smite", swords);
        // Soul Speed
        ENCHANT_ITEMS.put("soul_speed", boots);
        // Sweeping Edge
        ENCHANT_ITEMS.put("sweeping_edge", swordOnly);
        // Swift Sneak
        ENCHANT_ITEMS.put("swift_sneak", leggings);
        // Thorns
        Set<Material> thornsItems = new HashSet<>(allArmor);
        ENCHANT_ITEMS.put("thorns", thornsItems);
        // Unbreaking
        Set<Material> unbreakingItems = new HashSet<>(allArmor);
        unbreakingItems.addAll(tools); unbreakingItems.addAll(swordOnly);
        unbreakingItems.addAll(fishingRod); unbreakingItems.addAll(bow);
        unbreakingItems.addAll(trident); unbreakingItems.addAll(crossbow);
        unbreakingItems.addAll(mace); unbreakingItems.addAll(shears);
        unbreakingItems.addAll(flintAndSteel); unbreakingItems.addAll(shield);
        unbreakingItems.addAll(elytra); unbreakingItems.addAll(brush);
        unbreakingItems.addAll(carrotOnAStick); unbreakingItems.addAll(warpedFungusOnAStick);
        ENCHANT_ITEMS.put("unbreaking", unbreakingItems);
        // Wind Burst
        ENCHANT_ITEMS.put("wind_burst", mace);
    }

    private static Set<Material> materialSet(Material... materials) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        Collections.addAll(set, materials);
        return set;
    }

    // ==================================================================

    public EnchantValidator(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "EnchantValidator";
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("validation.enforce_max_levels", true);
    }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        Map<Enchantment, Integer> enchantments = itemStack.getEnchantments();

        if (enchantments.isEmpty()) {
            return violations;
        }

        Material itemType = itemStack.getType();
        List<String> enchantKeys = new ArrayList<>();

        // Check each enchantment
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            String enchantKey = entry.getKey().getKey().getKey().toLowerCase();
            int level = entry.getValue();

            // Check for duplicate enchantments (same key appears more than once — impossible through API
            // but detectable if someone manipulates raw data)
            if (enchantKeys.contains(enchantKey)) {
                violations.add(Violation.illegal("ENCHANT_DUPLICATE",
                        msg("ENCHANT_DUPLICATE",
                                "{enchant}", enchantKey)));
            }
            enchantKeys.add(enchantKey);

            // 1. Check max level
            Integer maxLevel = MAX_LEVELS.get(enchantKey);
            if (maxLevel != null && level > maxLevel) {
                violations.add(Violation.illegal("ENCHANT_LEVEL_EXCEEDED",
                        msg("ENCHANT_LEVEL_EXCEEDED",
                                "{enchant}", enchantKey,
                                "{found}", String.valueOf(level),
                                "{max}", String.valueOf(maxLevel))));
            } else if (maxLevel == null) {
                // Unknown enchantment ID — not in vanilla
                violations.add(Violation.illegal("ENCHANT_UNKNOWN",
                        "Unknown enchantment: " + enchantKey + " (level " + level + ")"));
                continue;
            }

            // 2. Check item-type compatibility
            Set<Material> validItems = ENCHANT_ITEMS.get(enchantKey);
            if (validItems != null && !validItems.contains(itemType)) {
                violations.add(Violation.illegal("ENCHANT_WRONG_ITEM",
                        msg("ENCHANT_WRONG_ITEM",
                                "{enchant}", enchantKey,
                                "{item}", itemType.name())));
            }

            // 3. Check conflicts with other enchantments on the same item
            Set<String> conflictSet = CONFLICTS.get(enchantKey);
            if (conflictSet != null) {
                for (String otherKey : enchantKeys) {
                    if (!otherKey.equals(enchantKey) && conflictSet.contains(otherKey)) {
                        violations.add(Violation.illegal("ENCHANT_CONFLICT",
                                msg("ENCHANT_CONFLICT",
                                        "{enchant1}", enchantKey,
                                        "{enchant2}", otherKey)));
                    }
                }
            }
        }

        return violations;
    }

    private String msg(String key, String... replacements) {
        String template = plugin.getConfigManager().getConfig()
                .getString("violation_messages." + key,
                        key + ": {enchant}/{found}/{max}");
        String result = template;
        for (int i = 0; i < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }
}
