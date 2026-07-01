package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates data components on items against vanilla survival rules.
 */
public class ComponentValidator implements ItemValidator {

    private final IllegalScanner plugin;

    private static final Pattern JSON_INJECTION = Pattern.compile(
            "\\{\"text\":\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARS = Pattern.compile(
            "[\\u200B-\\u200F\\u2028-\\u202F\\uFEFF\\u00AD\\u2060]");
    // Formatting codes in text — anvil cannot produce § characters
    private static final Pattern ANY_SECTION = Pattern.compile(
            "§[0-9a-fklmnor]");

    public ComponentValidator(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "ComponentValidator";
    }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        Material type = itemStack.getType();

        // === Unbreakable ===
        if (ItemAccessor.isUnbreakable(itemStack)) {
            violations.add(Violation.illegal("COMPONENT_UNBREAKABLE",
                    msg("COMPONENT_UNBREAKABLE")));
        }

        // === Lore === (no survival source)
        if (plugin.getConfigManager().getConfig().getBoolean("validation.flag_lore_presence", true)) {
            if (ItemAccessor.hasLore(itemStack)) {
                List<String> loreLines = ItemAccessor.getLoreLines(itemStack);
                if (!loreLines.isEmpty()) {
                    violations.add(Violation.illegal("COMPONENT_LORE_PRESENT",
                            msg("COMPONENT_LORE_PRESENT")));
                    for (int i = 0; i < loreLines.size(); i++) {
                        checkTextContent(violations, loreLines.get(i), "lore line " + (i + 1));
                    }
                }
            }
        }

        // === Item Name Override ===
        if (plugin.getConfigManager().getConfig().getBoolean("validation.flag_item_name_presence", true)) {
            if (ItemAccessor.hasItemName(itemStack)) {
                // Vanilla translatable item_names (e.g. Ominous Banner / 灾厄旗帜)
                // are legitimate survival items and should not be flagged
                if (!ItemAccessor.isItemNameTranslatable(itemStack)) {
                    String itemName = ItemAccessor.getItemName(itemStack);
                    if (itemName != null) {
                        violations.add(Violation.illegal("COMPONENT_ITEM_NAME",
                                msg("COMPONENT_ITEM_NAME")));
                        checkTextContent(violations, itemName, "item_name");
                    }
                }
            }
        }

        // === Custom Name === (anvil IS survival, but limited)
        if (ItemAccessor.hasCustomName(itemStack)) {
            String customName = ItemAccessor.getCustomNamePlain(itemStack);
            if (customName != null) {
                int maxLength = plugin.getConfigManager().getConfig()
                        .getInt("validation.custom_name_max_length", 50);
                if (customName.length() > maxLength) {
                    violations.add(Violation.illegal("CUSTOM_NAME_TOO_LONG",
                            msg("CUSTOM_NAME_TOO_LONG",
                                    "{length}", String.valueOf(customName.length()),
                                    "{max}", String.valueOf(maxLength))));
                }

                // Any style formatting (color, bold, italic override, etc.) = command only
                if (plugin.getConfigManager().getConfig()
                        .getBoolean("validation.flag_styled_custom_name", true)) {
                    if (ItemAccessor.isCustomNameStyled(itemStack)) {
                        violations.add(Violation.illegal("CUSTOM_NAME_STYLED",
                                msg("CUSTOM_NAME_STYLED")));
                    }
                }

                if (JSON_INJECTION.matcher(customName).find()) {
                    violations.add(Violation.illegal("CUSTOM_NAME_JSON_INJECTION",
                            msg("CUSTOM_NAME_JSON_INJECTION")));
                }
                if (CONTROL_CHARS.matcher(customName).find()) {
                    violations.add(Violation.warn("CUSTOM_NAME_CONTROL_CHARS",
                            msg("CUSTOM_NAME_CONTROL_CHARS")));
                }
                if (ANY_SECTION.matcher(customName).find()) {
                    violations.add(Violation.illegal("CUSTOM_NAME_STYLED",
                            msg("CUSTOM_NAME_STYLED")));
                }
            }
        }

        // === Can Place On / Can Destroy ===
        if (plugin.getConfigManager().getConfig().getBoolean("validation.flag_adventure_tags", true)) {
            if (ItemAccessor.hasCanPlaceOn(itemStack)) {
                violations.add(Violation.illegal("COMPONENT_ADVENTURE_TAG",
                        msg("COMPONENT_ADVENTURE_TAG", "{tag}", "can_place_on")));
            }
            if (ItemAccessor.hasCanDestroy(itemStack)) {
                violations.add(Violation.illegal("COMPONENT_ADVENTURE_TAG",
                        msg("COMPONENT_ADVENTURE_TAG", "{tag}", "can_destroy")));
            }
        }

        // === Max Stack Size ===
        Integer maxStack = ItemAccessor.getMaxStackSize(itemStack);
        if (maxStack != null) {
            int vanillaMax = getVanillaMaxStackSize(type);
            if (maxStack > vanillaMax) {
                violations.add(Violation.illegal("COMPONENT_MAX_STACK_SIZE",
                        msg("COMPONENT_MAX_STACK_SIZE",
                                "{found}", String.valueOf(maxStack),
                                "{max}", String.valueOf(vanillaMax))));
            }
        }

        // === Max Damage ===
        Integer maxDamage = ItemAccessor.getMaxDamage(itemStack);
        if (maxDamage != null) {
            int vanillaMax = type.getMaxDurability();
            if (maxDamage != vanillaMax) {
                violations.add(Violation.illegal("COMPONENT_MAX_DAMAGE",
                        msg("COMPONENT_MAX_DAMAGE",
                                "{found}", String.valueOf(maxDamage),
                                "{expected}", String.valueOf(vanillaMax))));
            }
        }

        // === Custom Model Data ===
        if (plugin.getConfigManager().getConfig().getBoolean("validation.flag_custom_model_data", true)) {
            if (ItemAccessor.hasCustomModelData(itemStack)) {
                violations.add(Violation.warn("COMPONENT_CUSTOM_MODEL_DATA",
                        msg("COMPONENT_CUSTOM_MODEL_DATA", "{value}", "present")));
            }
        }

        // === Hide Tooltip ===
        if (ItemAccessor.hasHideTooltip(itemStack)) {
            violations.add(Violation.warn("COMPONENT_HIDE_TOOLTIP",
                    msg("COMPONENT_HIDE_TOOLTIP")));
        }

        return violations;
    }

    private void checkTextContent(List<Violation> violations, String text, String source) {
        if (JSON_INJECTION.matcher(text).find()) {
            violations.add(Violation.illegal("CUSTOM_NAME_JSON_INJECTION",
                    msg("CUSTOM_NAME_JSON_INJECTION")));
        }
        if (CONTROL_CHARS.matcher(text).find()) {
            violations.add(Violation.warn("CUSTOM_NAME_CONTROL_CHARS",
                    msg("CUSTOM_NAME_CONTROL_CHARS")));
        }
    }

    private int getVanillaMaxStackSize(Material type) {
        if (type.isBlock()) return 64;
        return switch (type) {
            case ENDER_PEARL, SNOWBALL, EGG,
                 BUCKET, WATER_BUCKET, LAVA_BUCKET, MILK_BUCKET,
                 PUFFERFISH_BUCKET, SALMON_BUCKET, COD_BUCKET,
                 TROPICAL_FISH_BUCKET, AXOLOTL_BUCKET, TADPOLE_BUCKET,
                 POWDER_SNOW_BUCKET, HONEY_BOTTLE -> 16;
            case OAK_SIGN, SPRUCE_SIGN, BIRCH_SIGN, JUNGLE_SIGN,
                 ACACIA_SIGN, DARK_OAK_SIGN, MANGROVE_SIGN,
                 CHERRY_SIGN, BAMBOO_SIGN, CRIMSON_SIGN, WARPED_SIGN -> 16;
            case WHITE_BANNER, ORANGE_BANNER, MAGENTA_BANNER, LIGHT_BLUE_BANNER,
                 YELLOW_BANNER, LIME_BANNER, PINK_BANNER, GRAY_BANNER,
                 LIGHT_GRAY_BANNER, CYAN_BANNER, PURPLE_BANNER, BLUE_BANNER,
                 BROWN_BANNER, GREEN_BANNER, RED_BANNER, BLACK_BANNER -> 16;
            default -> {
                if (type.getMaxDurability() > 0 || type.name().endsWith("_SWORD")
                        || type.name().endsWith("_PICKAXE") || type.name().endsWith("_AXE")
                        || type.name().endsWith("_SHOVEL") || type.name().endsWith("_HOE")
                        || type == Material.BOW || type == Material.CROSSBOW
                        || type == Material.TRIDENT || type == Material.MACE
                        || type == Material.FISHING_ROD || type == Material.SHIELD
                        || type == Material.SHEARS || type == Material.FLINT_AND_STEEL
                        || type == Material.ELYTRA || type == Material.BRUSH
                        || type == Material.CARROT_ON_A_STICK
                        || type == Material.WARPED_FUNGUS_ON_A_STICK
                        || type == Material.WRITABLE_BOOK || type == Material.WRITTEN_BOOK
                        || type == Material.ENCHANTED_BOOK
                        || type == Material.POTION || type == Material.SPLASH_POTION
                        || type == Material.LINGERING_POTION) {
                    yield 1;
                }
                yield 64;
            }
        };
    }

    private String msg(String key, String... replacements) {
        String template = plugin.getConfigManager().getConfig()
                .getString("violation_messages." + key, key);
        String result = template;
        for (int i = 0; i < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }
}
