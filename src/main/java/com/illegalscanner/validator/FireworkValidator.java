package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class FireworkValidator implements ItemValidator {

    private final IllegalScanner plugin;

    public FireworkValidator(IllegalScanner plugin) { this.plugin = plugin; }

    @Override
    public String getName() { return "FireworkValidator"; }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        int maxFlight = plugin.getConfigManager().getConfig()
                .getInt("validation.firework_max_flight", 3);
        int maxExplosions = plugin.getConfigManager().getConfig()
                .getInt("validation.firework_max_explosions", 10);

        if (ItemAccessor.hasFireworks(itemStack)) {
            int flight = ItemAccessor.getFireworkFlight(itemStack);
            if (flight > maxFlight) {
                violations.add(Violation.illegal("FIREWORK_FLIGHT_EXCEEDED",
                        msg("FIREWORK_FLIGHT_EXCEEDED",
                                "{found}", String.valueOf(flight),
                                "{max}", String.valueOf(maxFlight))));
            }
            int explosions = ItemAccessor.getFireworkExplosionCount(itemStack);
            if (explosions > maxExplosions) {
                violations.add(Violation.illegal("FIREWORK_EXPLOSIONS_EXCEEDED",
                        msg("FIREWORK_EXPLOSIONS_EXCEEDED",
                                "{found}", String.valueOf(explosions),
                                "{max}", String.valueOf(maxExplosions))));
            }
        }

        if (ItemAccessor.hasFireworkExplosion(itemStack)) {
            // Single explosion star — just flag if suspicious
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
