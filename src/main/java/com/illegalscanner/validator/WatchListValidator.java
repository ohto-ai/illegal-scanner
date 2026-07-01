package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Validator that flags items whose Material is on the watch list.
 * This is a pure material-ID check — NBT is completely ignored.
 * Items unobtainable in survival (bedrock, spawner, end portal frame, etc.)
 * have no suspicious NBT but should still be flagged.
 */
public class WatchListValidator implements ItemValidator {

    private final IllegalScanner plugin;

    public WatchListValidator(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "WatchList";
    }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return Collections.emptyList();
        }

        Material type = itemStack.getType();
        if (plugin.getConfigManager().isMaterialWatched(type)) {
            return List.of(Violation.illegal(
                    "WATCH_MATERIAL",
                    "Watch list material " + type.name() + " (not obtainable in survival)"));
        }

        return Collections.emptyList();
    }
}
