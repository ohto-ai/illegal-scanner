package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class PotionValidator implements ItemValidator {

    private final IllegalScanner plugin;
    private static final Map<String, Integer> MAX_AMPLIFIERS = new LinkedHashMap<>();
    static {
        MAX_AMPLIFIERS.put("speed", 1); MAX_AMPLIFIERS.put("slowness", 0);
        MAX_AMPLIFIERS.put("haste", 0); MAX_AMPLIFIERS.put("mining_fatigue", 0);
        MAX_AMPLIFIERS.put("strength", 1); MAX_AMPLIFIERS.put("instant_health", 1);
        MAX_AMPLIFIERS.put("instant_damage", 1); MAX_AMPLIFIERS.put("jump_boost", 1);
        MAX_AMPLIFIERS.put("nausea", 0); MAX_AMPLIFIERS.put("regeneration", 1);
        MAX_AMPLIFIERS.put("resistance", 0); MAX_AMPLIFIERS.put("fire_resistance", 0);
        MAX_AMPLIFIERS.put("water_breathing", 0); MAX_AMPLIFIERS.put("invisibility", 0);
        MAX_AMPLIFIERS.put("blindness", 0); MAX_AMPLIFIERS.put("night_vision", 0);
        MAX_AMPLIFIERS.put("hunger", 0); MAX_AMPLIFIERS.put("weakness", 0);
        MAX_AMPLIFIERS.put("poison", 1); MAX_AMPLIFIERS.put("wither", 0);
        MAX_AMPLIFIERS.put("health_boost", 0); MAX_AMPLIFIERS.put("absorption", 0);
        MAX_AMPLIFIERS.put("saturation", 0); MAX_AMPLIFIERS.put("glowing", 0);
        MAX_AMPLIFIERS.put("levitation", 0); MAX_AMPLIFIERS.put("slow_falling", 0);
        MAX_AMPLIFIERS.put("conduit_power", 0); MAX_AMPLIFIERS.put("dolphins_grace", 0);
    }

    private static final Set<Set<String>> IMPOSSIBLE_COMBOS = new LinkedHashSet<>();
    static {
        IMPOSSIBLE_COMBOS.add(Set.of("instant_health", "instant_damage"));
        IMPOSSIBLE_COMBOS.add(Set.of("instant_health", "poison"));
        IMPOSSIBLE_COMBOS.add(Set.of("speed", "slowness"));
        IMPOSSIBLE_COMBOS.add(Set.of("strength", "weakness"));
        IMPOSSIBLE_COMBOS.add(Set.of("regeneration", "poison"));
        IMPOSSIBLE_COMBOS.add(Set.of("regeneration", "wither"));
        IMPOSSIBLE_COMBOS.add(Set.of("night_vision", "blindness"));
        IMPOSSIBLE_COMBOS.add(Set.of("haste", "mining_fatigue"));
        IMPOSSIBLE_COMBOS.add(Set.of("jump_boost", "levitation"));
    }

    public PotionValidator(IllegalScanner plugin) { this.plugin = plugin; }

    @Override
    public String getName() { return "PotionValidator"; }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        if (!ItemAccessor.hasPotionContents(itemStack)) return violations;

        int maxAmplifier = plugin.getConfigManager().getConfig()
                .getInt("validation.potion_max_amplifier", 1);
        int maxDuration = plugin.getConfigManager().getConfig()
                .getInt("validation.potion_max_duration_ticks", 9600);

        List<ItemAccessor.PotionEffectEntry> effects = ItemAccessor.getCustomPotionEffects(itemStack);
        if (!effects.isEmpty()) {
            List<String> effectKeys = new ArrayList<>();
            for (var effect : effects) {
                String key = effect.effectKey().replace("minecraft:", "");
                effectKeys.add(key);

                Integer maxAmp = MAX_AMPLIFIERS.get(key);
                if (maxAmp != null && effect.amplifier() > maxAmp) {
                    violations.add(Violation.illegal("POTION_AMPLIFIER",
                            msg("POTION_AMPLIFIER", "{effect}", key,
                                    "{found}", String.valueOf(effect.amplifier()),
                                    "{max}", String.valueOf(maxAmp))));
                } else if (effect.amplifier() > maxAmplifier) {
                    violations.add(Violation.illegal("POTION_AMPLIFIER",
                            msg("POTION_AMPLIFIER", "{effect}", key,
                                    "{found}", String.valueOf(effect.amplifier()),
                                    "{max}", String.valueOf(maxAmplifier))));
                }

                if (!effect.isInstant() && effect.duration() > maxDuration) {
                    violations.add(Violation.illegal("POTION_DURATION",
                            msg("POTION_DURATION", "{effect}", key,
                                    "{found}", String.valueOf(effect.duration()),
                                    "{max}", String.valueOf(maxDuration))));
                }
                if (!MAX_AMPLIFIERS.containsKey(key)) {
                    violations.add(Violation.warn("POTION_UNKNOWN_EFFECT",
                            "Unknown potion effect: " + key));
                }
            }

            // Impossible combinations
            for (int i = 0; i < effectKeys.size(); i++) {
                for (int j = i + 1; j < effectKeys.size(); j++) {
                    Set<String> pair = Set.of(effectKeys.get(i), effectKeys.get(j));
                    for (Set<String> combo : IMPOSSIBLE_COMBOS) {
                        if (combo.equals(pair)) {
                            violations.add(Violation.illegal("POTION_CONFLICT",
                                    msg("POTION_CONFLICT",
                                            "{effect1}", effectKeys.get(i),
                                            "{effect2}", effectKeys.get(j))));
                        }
                    }
                }
            }
        }

        // Custom color without effects
        Integer color = ItemAccessor.getPotionColor(itemStack);
        if (color != null && effects.isEmpty()) {
            violations.add(Violation.warn("POTION_INVALID_COLOR",
                    msg("POTION_INVALID_COLOR")));
        }

        return violations;
    }

    private String msg(String key, String... r) {
        String t = plugin.getConfigManager().getConfig()
                .getString("violation_messages." + key, key);
        for (int i = 0; i < r.length; i += 2) t = t.replace(r[i], r[i + 1]);
        return t;
    }
}
