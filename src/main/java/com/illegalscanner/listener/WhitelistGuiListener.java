package com.illegalscanner.listener;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.command.WhitelistCommandHandler.WhitelistGuiHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Handles whitelist GUI interactions: remove entries, get copies, add from inventory.
 */
public class WhitelistGuiListener implements Listener {

    private final IllegalScanner plugin;
    private static final Set<Material> DECORATIVE = Set.of(
            Material.BLACK_STAINED_GLASS_PANE, Material.ARROW, Material.PAPER, Material.BARRIER
    );

    public WhitelistGuiListener(IllegalScanner plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WhitelistGuiHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        // Navigation row
        if (rawSlot >= 45 && rawSlot < topSize) {
            event.setCancelled(true);
            if (rawSlot == 45 && holder.page > 1) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getCommandRouter().getWhitelistHandler().openWhitelistGui(player, holder.page - 1));
            } else if (rawSlot == 53 && holder.page < holder.totalPages) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getCommandRouter().getWhitelistHandler().openWhitelistGui(player, holder.page + 1));
            }
            return;
        }

        // Content area
        if (rawSlot >= 0 && rawSlot < 45) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir() || DECORATIVE.contains(current.getType())) return;

            int entryId = holder.entryIds.getOrDefault(rawSlot, -1);
            if (entryId <= 0) return;

            if (event.isShiftClick() && event.getClick().isLeftClick()) {
                // Copy item — use actual snapshot if hash-based entry
                var entries = plugin.getItemWhitelistManager().listEntries();
                for (var e : entries) {
                    if (e.id() == entryId) {
                        ItemStack copy = null;
                        if (e.itemHash() != null && !e.itemHash().isEmpty()) {
                            var snap = plugin.getDatabaseManager().getItemByHash(e.itemHash());
                            if (snap != null) copy = com.illegalscanner.scanner.NbtUtil.itemStackFromJson(snap.itemSnapshot());
                        }
                        if (copy == null || copy.getType().isAir()) {
                            Material mat = Material.getMaterial(e.material());
                            if (mat != null) copy = new ItemStack(mat);
                        }
                        if (copy != null && !copy.getType().isAir()) {
                            var overflow = player.getInventory().addItem(copy);
                            if (!overflow.isEmpty())
                                for (ItemStack drop : overflow.values())
                                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                        break;
                    }
                }
            } else if (event.getClick().isRightClick()) {
                // Remove
                plugin.getItemWhitelistManager().removeEntry(entryId);
                int remaining = plugin.getItemWhitelistManager().listEntries().size();
                int maxPages = Math.max(1, (remaining + 44) / 45);
                int newPage = Math.min(holder.page, maxPages);
                final int reopen = newPage;
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getCommandRouter().getWhitelistHandler().openWhitelistGui(player, reopen));
                player.sendMessage("§c已移除白名单条目 #" + entryId);
            }
            return;
        }

        // Player inventory: Shift+click to add
        if (rawSlot >= topSize && event.isShiftClick()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir() || DECORATIVE.contains(clicked.getType())) return;
            event.setCancelled(true);

            // Repair durability
            ItemStack toRegister = clicked.clone();
            if (clicked.getType().getMaxDurability() > 0
                    && toRegister.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.getDamage() > 0) {
                dmg.setDamage(0);
                toRegister.setItemMeta((org.bukkit.inventory.meta.ItemMeta) dmg);
            }

            var mgr = plugin.getItemWhitelistManager();
            if (mgr.isWhitelisted(toRegister)) { player.sendMessage("§e该物品已在白名单中。"); return; }

            String mat = clicked.getType().name();
            String itemHash = plugin.getItemHashService().resolve(toRegister);
            String eJson = mgr.buildEnchantmentsJson(toRegister);
            String aJson = mgr.buildAttributesJson(toRegister);
            int id = mgr.addEntrySync(mat, itemHash, null, null, eJson, aJson);
            if (id >= 0) {
                player.sendMessage("§a已添加 §f" + mat + " §a到白名单 (#" + id + ")");
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getCommandRouter().getWhitelistHandler().openWhitelistGui(player, holder.page));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WhitelistGuiHolder) event.setCancelled(true);
    }
}
