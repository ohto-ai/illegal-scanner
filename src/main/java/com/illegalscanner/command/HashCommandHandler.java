package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.MessageDigest;
import java.util.ArrayList;

/**
 * Debug command: /is hash — displays the SHA-256 hash of the held item
 * using the same method as the whitelist, plus direct (cache-bypassed) comparison.
 */
public class HashCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public HashCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayer only.");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            sender.sendMessage("§c请手持一个物品。");
            return true;
        }

        String mat = item.getType().name();

        // Hash via the same method the whitelist uses (cached)
        String cachedHash = plugin.getItemHashService().computeHash(item);

        // Hash computed directly (bypasses cache entirely)
        String directHash = computeHashDirect(item);

        sender.sendMessage("§6===== 物品 Hash 信息 =====");
        sender.sendMessage("§7材料: §f" + mat);

        if (cachedHash != null) {
            sender.sendMessage("§7Hash (SHA-256): §f" + cachedHash);
            sender.sendMessage("§7     前 8 位: §f" + cachedHash.substring(0, 8) + "...");
        }

        if (directHash != null && cachedHash != null && !directHash.equals(cachedHash)) {
            sender.sendMessage("§c⚠ 缓存与直接计算不一致! 缓存可能碰撞。");
            sender.sendMessage("§c  直接计算: §f" + directHash);
        } else if (directHash != null && cachedHash != null) {
            sender.sendMessage("§7缓存一致性: §a一致");
        }

        // Check whether this hash already exists in the item whitelist
        var wlEntries = plugin.getItemWhitelistManager().listEntries();
        var matchingIds = new ArrayList<Integer>();
        for (var e : wlEntries) {
            if (cachedHash != null && cachedHash.equals(e.itemHash())) {
                matchingIds.add(e.id());
            }
        }

        if (!matchingIds.isEmpty()) {
            sender.sendMessage("§e⚠ 该 hash 已存在于物品白名单: 条目 ID " + matchingIds);
        } else {
            sender.sendMessage("§a该 hash 不在物品白名单中。");
        }

        return true;
    }

    /**
     * Compute the SHA-256 hash directly, bypassing the ItemHashService cache.
     * Uses the identical normalization: amount=1, damage=0.
     */
    private String computeHashDirect(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            ItemStack copy = item.clone();
            copy.setAmount(1);
            if (copy.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                dmg.setDamage(0);
                copy.setItemMeta((org.bukkit.inventory.meta.ItemMeta) dmg);
            }
            byte[] nbtBytes = copy.serializeAsBytes();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(nbtBytes);

            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to compute direct hash: " + e.getMessage());
            return null;
        }
    }
}
