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
        Player target = plugin.getServer().getPlayer(name);
        if (target == null) {
            sender.sendMessage("§c玩家不在线: " + name); return true;
        }
        sender.sendMessage("§e正在检测玩家 " + target.getName() + "...");
        int flagged = 0;
        for (ItemStack item : target.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                if (!plugin.getValidationEngine().validate(item).isEmpty()) flagged++;
            }
        }
        sender.sendMessage("§a检测完成: " + target.getName() + " 背包中有 " + flagged + " 个可疑物品。（未记录）");
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

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.inspect");
    }
}
