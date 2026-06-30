package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager.ItemIndexEntry;
import com.illegalscanner.database.DatabaseManager.UnifiedRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Handles /is report <chunk|player|area|res|world|full|scan|record|item> (text output).
 * All reports use pagination format: << Page N / M >>
 */
public class ReportCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final int PAGE_SIZE = 10;

    public ReportCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§e/is report <chunk|player|area|res|world|full|scan|record|item>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> reportChunk(sender, args);
            case "player" -> reportPlayer(sender, args);
            case "item"   -> reportItem(sender, args);
            case "scan"   -> reportScan(sender, args);
            case "record" -> reportRecord(sender, args);
            case "area", "res", "world", "full" -> {
                sender.sendMessage("§e" + args[0] + " 报告将在 Phase 3 实现。");
                yield true;
            }
            default -> {
                sender.sendMessage("§e未知子命令: " + args[0]);
                yield true;
            }
        };
    }

    private boolean reportChunk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c此命令仅玩家可用。"); return true;
        }
        int page = parsePage(args, 1);
        int cx = p.getLocation().getChunk().getX();
        int cz = p.getLocation().getChunk().getZ();
        String world = p.getWorld().getName();

        var db = plugin.getDatabaseManager();
        var records = db.getRecordsByChunk(world, cx, cz, page, PAGE_SIZE);
        int total = db.countRecordsByChunk(world, cx, cz);
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);

        sender.sendMessage("§6===== 区块(" + cx + "," + cz + ") 违禁报告 =====");
        if (records.isEmpty()) {
            sender.sendMessage("§a该区块无违规记录。");
        } else {
            for (var r : records) {
                printRecord(sender, r);
            }
        }
        printPageFooter(sender, page, totalPages);
        return true;
    }

    private boolean reportPlayer(CommandSender sender, String[] args) {
        String name;
        if (args.length < 2) {
            if (sender instanceof Player p) name = p.getName();
            else { sender.sendMessage("§e用法: /is report player <玩家名>"); return true; }
        } else {
            name = args[1];
        }
        int page = args.length >= 3 ? parsePage(args, 2) : 1;

        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            sender.sendMessage("§c玩家不在线: " + name); return true;
        }
        var db = plugin.getDatabaseManager();
        var records = db.getRecordsByPlayer(target.getUniqueId().toString(), page, PAGE_SIZE);
        int total = db.countRecordsByPlayer(target.getUniqueId().toString());
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);

        sender.sendMessage("§6===== 玩家 " + target.getName() + " 违禁报告 =====");
        if (records.isEmpty()) {
            sender.sendMessage("§a该玩家无违规记录。");
        } else {
            for (var r : records) {
                printRecord(sender, r);
            }
        }
        printPageFooter(sender, page, totalPages);
        return true;
    }

    private boolean reportItem(CommandSender sender, String[] args) {
        var db = plugin.getDatabaseManager();
        int page = parsePage(args, 1);

        // No hash: list distinct item types
        if (args.length < 2) {
            List<ItemIndexEntry> items = db.getDistinctViolationItems();
            int total = items.size();
            int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > totalPages) page = totalPages;
            int start = (page - 1) * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, total);

            sender.sendMessage("§6===== 违规物品类型列表 =====");
            for (int i = start; i < end; i++) {
                var entry = items.get(i);
                int count = db.getItemRecordCount(entry.itemHash());
                sender.sendMessage("§e" + entry.itemType() + " §7Hash: §f" + entry.itemHash().substring(0, 12)
                        + "... §7违规: §c" + count + " 条");
            }
            printPageFooter(sender, page, totalPages);
            return true;
        }

        // With hash: list all records
        String hash = args[1];
        page = args.length >= 3 ? parsePage(args, 2) : 1;
        var records = db.getRecordsByItem(hash, page, PAGE_SIZE);
        int total = db.getItemRecordCount(hash);
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);

        var entry = db.getItemByHash(hash);
        String itemName = entry != null ? entry.itemType() : "未知";

        sender.sendMessage("§6===== 物品 " + itemName + " 违禁报告 =====");
        sender.sendMessage("§7Hash: §f" + hash);
        for (var r : records) {
            printRecord(sender, r);
        }
        printPageFooter(sender, page, totalPages);
        return true;
    }

    private boolean reportScan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // List sessions
            var sessions = plugin.getDatabaseManager().getScanSessions(1, 20);
            sender.sendMessage("§6===== 扫描会话列表 =====");
            if (sessions.isEmpty()) {
                sender.sendMessage("§a暂无扫描会话。");
            } else {
                for (var s : sessions) {
                    String dateStr = DATE_FMT.format(new Date(s.startedAt()));
                    sender.sendMessage("§e#" + s.id() + " §7[" + s.scanType() + "] §f" + s.target()
                            + " §7状态: §f" + s.status() + " §7违规: §c" + s.itemsFlagged()
                            + " §7@" + dateStr);
                }
            }
            return true;
        }
        try {
            int sessionId = Integer.parseInt(args[1]);
            int page = args.length >= 3 ? parsePage(args, 2) : 1;
            var records = plugin.getDatabaseManager().getRecordsBySession(sessionId, page, PAGE_SIZE);
            int total = plugin.getDatabaseManager().countRecordsBySession(sessionId);
            int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);

            sender.sendMessage("§6===== 会话 #" + sessionId + " 违禁报告 =====");
            for (var r : records) {
                printRecord(sender, r);
            }
            printPageFooter(sender, page, totalPages);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效的会话ID。");
        }
        return true;
    }

    private boolean reportRecord(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§e用法: /is report record <SCAN|MONITOR> <id>"); return true;
        }
        String source = args[1].toUpperCase();
        try {
            long id = Long.parseLong(args[2]);
            var r = plugin.getDatabaseManager().getRecordById(source, id);
            if (r == null) {
                sender.sendMessage("§c未找到记录: " + source + " #" + id); return true;
            }
            sender.sendMessage("§6===== 记录 " + source + " #" + id + " 详情 =====");
            var entry = plugin.getDatabaseManager().getItemByHash(r.itemHash());
            sender.sendMessage("§7来源: §f" + r.source() + " / " + r.scanType());
            sender.sendMessage("§7物品: §f" + (entry != null ? entry.itemType() : "?") + " §7Hash: §f" + r.itemHash());
            sender.sendMessage("§7严重级别: " + (r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑"));
            sender.sendMessage("§7世界: §f" + r.world() + " §7区块: §f(" + r.chunkX() + "," + r.chunkZ() + ")");
            if (r.playerName() != null) sender.sendMessage("§7玩家: §f" + r.playerName());
            if (r.container() != null) sender.sendMessage("§7容器: §f" + r.container() + " 槽位: " + (r.itemSlot() != null ? r.itemSlot() : "?"));
            sender.sendMessage("§7时间: §f" + DATE_FMT.format(new Date(r.scanTime())));
            // Print violations
            String violJson = r.violations();
            if (violJson != null && violJson.length() > 2) {
                sender.sendMessage("§7违规项:");
                String inner = violJson.substring(1, violJson.length() - 1);
                for (String part : inner.split("\\},\\{")) {
                    sender.sendMessage("  §8- §7" + part.replace("{","").replace("}","").replace("\"","").trim());
                }
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效ID。");
        }
        return true;
    }

    // ==================== Helpers ====================

    private void printRecord(CommandSender sender, UnifiedRecord r) {
        String sev = r.severity().equals("ILLEGAL") ? "§c" : "§e";
        var entry = plugin.getDatabaseManager().getItemByHash(r.itemHash());
        String type = entry != null ? entry.itemType() : "?";
        String player = r.playerName() != null ? r.playerName() : "-";
        String loc = r.containerLoc() != null ? r.containerLoc() : r.world() + " (" + r.chunkX() + "," + r.chunkZ() + ")";
        String time = DATE_FMT.format(new Date(r.scanTime()));
        sender.sendMessage(sev + "[" + r.source().charAt(0) + " #" + r.id() + "] §f" + type
                + " §7@ " + loc + " §8| " + player + " §8| " + time);
    }

    private void printPageFooter(CommandSender sender, int page, int totalPages) {
        sender.sendMessage("§7§m--------------------§r §7<< 第 §f" + page + " §7/ 共 §f" + totalPages + " §7页 >>");
    }

    private int parsePage(String[] args, int idx) {
        if (args.length > idx) {
            try { return Math.max(1, Integer.parseInt(args[idx])); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.report");
    }
}
