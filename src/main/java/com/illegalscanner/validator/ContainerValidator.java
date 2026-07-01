package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively validates items inside containers.
 */
public class ContainerValidator implements ItemValidator {

    private final IllegalScanner plugin;

    public ContainerValidator(IllegalScanner plugin) { this.plugin = plugin; }

    @Override
    public String getName() { return "ContainerValidator"; }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        String typeName = itemStack.getType().name();

        // Shulker box — check internal items
        if (typeName.endsWith("SHULKER_BOX")) {
            violations.addAll(checkShulkerBox(itemStack));
        }

        // Bundle — check internal items
        if (ItemAccessor.hasBundleContents(itemStack)) {
            List<ItemStack> bundleItems = ItemAccessor.getBundleItems(itemStack);
            // Audit: skip bundle level if it holds an exclusion marker
            if (!com.illegalscanner.scanner.ContainerUtil.hasIgnoreMarker(
                    bundleItems.toArray(new ItemStack[0]))) {
                for (int i = 0; i < bundleItems.size(); i++) {
                    List<Violation> inner = plugin.getValidationEngine().validate(bundleItems.get(i));
                    for (Violation v : inner) {
                        violations.add(Violation.illegal(v.type(),
                                "[收纳袋 槽位 " + i + "] " + v.message()));
                    }
                }
            }
        }

        // === Container contents (minecraft:container) ===
        // Only applies to non-shulker-box block items (chests, hoppers, etc.)
        // that were Ctrl+Pick Block'd with items inside. Empty containers are normal.
        if (!typeName.endsWith("SHULKER_BOX") && ItemAccessor.hasContainerContents(itemStack)) {
            int itemCount = ItemAccessor.getContainerItemCount(itemStack);
            if (itemCount > 0) {
                violations.add(Violation.illegal("CONTAINER_CONTENTS_PRESENT",
                        "物品携带方块实体数据，内含 " + itemCount + " 个物品 " +
                        "（仅创造模式 Ctrl+中键选取或命令可获得）"));

                List<ItemStack> containerItems = ItemAccessor.getContainerItems(itemStack);
                // Audit: skip container component level if it holds an exclusion marker
                if (!com.illegalscanner.scanner.ContainerUtil.hasIgnoreMarker(
                        containerItems.toArray(new ItemStack[0]))) {
                    for (int i = 0; i < containerItems.size(); i++) {
                        ItemStack innerItem = containerItems.get(i);
                        if (innerItem != null && !innerItem.getType().isAir()) {
                            List<Violation> inner = plugin.getValidationEngine().validate(innerItem);
                            for (Violation v : inner) {
                                violations.add(Violation.illegal(v.type(),
                                        "[方块容器 槽位 " + i + " - " +
                                        innerItem.getType().name() + "] " + v.message()));
                            }
                        }
                    }
                }
            }
            // Empty container component is normal for block items — don't flag
        }

        // === Container loot (minecraft:container_loot) ===
        // This is the loot table reference. When a chest generates in the world,
        // it has this component. But when you break it, this is lost.
        // An item with this component was created via commands or creative tools.
        if (ItemAccessor.hasContainerLoot(itemStack)) {
            violations.add(Violation.warn("CONTAINER_LOOT_PRESENT",
                    "物品携带战利品表数据（原版生存破坏容器不会保留）"));
        }

        return violations;
    }

    private List<Violation> checkShulkerBox(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        if (itemStack.getItemMeta() instanceof BlockStateMeta meta) {
            if (meta.getBlockState() instanceof ShulkerBox shulker) {
                ItemStack[] contents = shulker.getInventory().getContents();
                // Audit: skip shulker level if it holds an exclusion marker
                if (com.illegalscanner.scanner.ContainerUtil.hasIgnoreMarker(contents)) {
                    return violations;
                }
                for (int i = 0; i < contents.length; i++) {
                    ItemStack contained = contents[i];
                    if (contained != null && !contained.getType().isAir()) {
                        List<Violation> inner = plugin.getValidationEngine().validate(contained);
                        for (Violation v : inner) {
                            violations.add(Violation.illegal(v.type(),
                                    "[潜影盒 槽位 " + i + "] " + v.message()));
                        }
                    }
                }
            }
        }
        return violations;
    }
}
