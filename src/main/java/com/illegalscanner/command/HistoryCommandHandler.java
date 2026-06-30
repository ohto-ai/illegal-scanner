package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Handles /is history <chunk|player> — historical records from both tables.
 * Phase 3 full implementation.
 */
public class HistoryCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public HistoryCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) {
            sender.sendMessage("§e/is history <chunk|player>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> handleChunk(sender, args);
            case "player" -> handlePlayer(sender, args);
            default -> { sender.sendMessage("§e未知子命令: " + args[0] + "。可用: chunk|player"); yield true; }
        };
    }

    private boolean handleChunk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayer only."); return true; }
        int page = args.length >= 2 ? Math.max(1, Integer.parseInt(args[1])) : 1;
        int cx = p.getLocation().getChunk().getX();
        int cz = p.getLocation().getChunk().getZ();
        var records = plugin.getDatabaseManager().getRecordsByChunk(p.getWorld().getName(), cx, cz, page, 10);
        records.removeIf(r -> "CLEAN".equals(r.severity()));
        int total = plugin.getDatabaseManager().countViolationsByChunk(p.getWorld().getName(), cx, cz);
        int totalPages = Math.max(1, (total + 9) / 10);

        p.sendMessage("§6===== 区块(" + cx + "," + cz + ") 历史记录 =====");
        if (records.isEmpty()) {
            p.sendMessage("§a暂无该区块的历史记录。");
        } else {
            for (var r : records) {
                var entry = plugin.getDatabaseManager().getItemByHash(r.itemHash());
                String type = entry != null ? entry.itemType() : "?";
                String sev = r.severity().equals("ILLEGAL") ? "§c" : "§e";
                p.sendMessage(sev + "[" + r.source() + " #" + r.id() + "] §f" + type
                        + " §8| " + DATE_FMT.format(new Date(r.scanTime())));
            }
        }
        p.sendMessage("§7<< 第 " + page + " / " + totalPages + " 页 >>");
        return true;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is history player <玩家名> [页码]"); return true;
        }
        String name = args[1];
        int page = args.length >= 3 ? Math.max(1, Integer.parseInt(args[2])) : 1;
        Player target = Bukkit.getPlayer(name);
        if (target == null) { sender.sendMessage("§c玩家不在线: " + name); return true; }

        var records = plugin.getDatabaseManager().getRecordsByPlayer(target.getUniqueId().toString(), page, 10);
        int total = plugin.getDatabaseManager().countRecordsByPlayer(target.getUniqueId().toString());
        int totalPages = Math.max(1, (total + 9) / 10);

        sender.sendMessage("§6===== " + target.getName() + " 历史记录 =====");
        if (records.isEmpty()) {
            sender.sendMessage("§a暂无该玩家的历史记录。");
        } else {
            for (var r : records) {
                var entry = plugin.getDatabaseManager().getItemByHash(r.itemHash());
                String type = entry != null ? entry.itemType() : "?";
                String sev = r.severity().equals("ILLEGAL") ? "§c" : "§e";
                sender.sendMessage(sev + "[" + r.source() + " #" + r.id() + "] §f" + type
                        + " §7@ " + r.world() + "(" + r.chunkX() + "," + r.chunkZ() + ")"
                        + " §8| " + DATE_FMT.format(new Date(r.scanTime())));
            }
        }
        sender.sendMessage("§7<< 第 " + page + " / " + totalPages + " 页 >>");
        return true;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.report");
    }
}
