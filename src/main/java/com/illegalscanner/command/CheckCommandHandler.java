package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.validator.ValidationResult;
import com.illegalscanner.validator.Violation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Handles /is check <item|player|chunk> — quick check without database recording.
 */
public class CheckCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public CheckCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§e/is check <item|player|chunk>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "item"   -> handleItem(sender);
            case "player" -> handlePlayer(sender, args);
            case "chunk"  -> handleChunk(sender);
            default -> {
                sender.sendMessage("§e未知子命令: " + args[0] + "。可用: item|player|chunk");
                yield true;
            }
        };
    }

    private boolean handleItem(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c此命令仅玩家可用。"); return true;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage("§c请手持一个物品。"); return true;
        }
        List<Violation> violations = plugin.getValidationEngine().validate(held);
        if (violations.isEmpty()) {
            sender.sendMessage("§a物品检测通过，无违规。");
        } else {
            ValidationResult sev = plugin.getValidationEngine().getOverallSeverity(violations);
            String color = sev == ValidationResult.ILLEGAL ? "§c" : "§e";
            sender.sendMessage(color + "===== 检测结果: " + sev.name() + " =====");
            for (Violation v : violations) {
                sender.sendMessage(color + "[" + v.severity() + "] " + v.type() + ": " + v.message());
            }
            sender.sendMessage(color + "共 " + violations.size() + " 项违规。（未记录到数据库）");
        }
        return true;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) && args.length < 2) {
            sender.sendMessage("§e用法: /is check player <玩家名>"); return true;
        }
        String name = args.length >= 2 ? args[1] : ((Player) sender).getName();

        // Try online player first — exact match only (no prefix matching)
        Player target = resolveExactPlayer(name);
        if (target != null) {
            sender.sendMessage("§e正在检测在线玩家 " + target.getName() + "...");
            int flagged = 0;
            for (ItemStack item : target.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    if (!plugin.getValidationEngine().validate(item).isEmpty()) flagged++;
                }
            }
            // Also check ender chest
            if (plugin.getConfigManager().getConfig().getBoolean("scan.scan_player_enderchests", true)) {
                for (ItemStack item : target.getEnderChest().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        if (!plugin.getValidationEngine().validate(item).isEmpty()) flagged++;
                    }
                }
            }
            sender.sendMessage("§a检测完成: " + target.getName() + " 背包+末影箱中有 " + flagged + " 个可疑物品。（未记录）");
            return true;
        }

        // Try offline player via .dat file
        org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(name);
        if (!offline.hasPlayedBefore()) {
            sender.sendMessage("§c未找到玩家: " + name); return true;
        }

        sender.sendMessage("§e正在检测离线玩家 " + offline.getName() + " (读取存档)...");
        java.io.File playerDataFolder = new java.io.File(
                plugin.getServer().getWorlds().get(0).getWorldFolder(), "playerdata");
        java.io.File playerFile = new java.io.File(playerDataFolder, offline.getUniqueId() + ".dat");

        if (!playerFile.exists()) {
            sender.sendMessage("§c未找到离线玩家数据文件: " + name); return true;
        }

        java.util.List<ItemStack> items = com.illegalscanner.scanner.NbtUtil.readPlayerInventory(playerFile, plugin);
        if (items.isEmpty()) {
            sender.sendMessage("§a检测完成: " + offline.getName() + " 离线存档中无物品或读取失败。");
            return true;
        }

        int flagged = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                if (!plugin.getValidationEngine().validate(item).isEmpty()) flagged++;
            }
        }
        sender.sendMessage("§a检测完成: " + offline.getName() + " 离线存档中有 " + flagged + " 个可疑物品（共 " + items.size() + " 件）。（未记录）");
        return true;
    }

    private boolean handleChunk(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c此命令仅玩家可用。"); return true;
        }
        int cx = p.getLocation().getChunk().getX();
        int cz = p.getLocation().getChunk().getZ();
        sender.sendMessage("§e正在检测当前区块 (" + cx + "," + cz + ")...");
        // Use chunk scanner but don't record
        int flagged = plugin.getScanService().getChunkScanner().scanChunkQuick(p.getWorld().getChunkAt(cx, cz));
        sender.sendMessage("§a检测完成: 发现 " + flagged + " 个违规物品。（未记录）");
        return true;
    }

    /** Resolve a player by exact name (case-insensitive). Unlike Bukkit.getPlayer() this does NOT do prefix matching. */
    private Player resolveExactPlayer(String name) {
        Player exact = plugin.getServer().getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.inspect");
    }
}
