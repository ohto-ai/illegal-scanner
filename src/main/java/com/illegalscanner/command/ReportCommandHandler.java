package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager.ItemIndexEntry;
import com.illegalscanner.database.DatabaseManager.UnifiedRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Handles /is report <chunk|player|area|res|world|scan|record|item> (text output).
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
            sender.sendMessage("§e/is report <chunk|player|area|res|world|scan|record|item>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> reportChunk(sender, args);
            case "player" -> reportPlayer(sender, args);
            case "item"   -> reportItem(sender, args);
            case "scan"   -> reportScan(sender, args);
            case "record" -> reportRecord(sender, args);
            case "area"  -> reportArea(sender, args);
            case "res"   -> reportRes(sender, args);
            case "world" -> reportWorld(sender, args);
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
        records.removeIf(r -> "CLEAN".equals(r.severity()));
        int total = db.countViolationsByChunk(world, cx, cz);
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

        String playerUuid;
        String playerName;

        // Exact match only — Bukkit.getPlayer(name) does prefix matching
        Player target = resolveExactPlayer(name);
        if (target != null) {
            playerUuid = target.getUniqueId().toString();
            playerName = target.getName();
        } else {
            // Try offline player — verify exact name match
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline.getName() == null || !offline.getName().equalsIgnoreCase(name)) {
                sender.sendMessage("§c玩家未找到: " + name + " (使用精确名称)"); return true;
            }
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage("§c玩家未找到: " + name); return true;
            }
            playerUuid = offline.getUniqueId().toString();
            playerName = offline.getName();
        }

        var db = plugin.getDatabaseManager();
        var records = db.getRecordsByPlayer(playerUuid, page, PAGE_SIZE);
        int total = db.countRecordsByPlayer(playerUuid);
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);

        sender.sendMessage("§6===== 玩家 " + playerName + " 违禁报告 =====");
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

    private boolean reportArea(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§e用法: /is report area <x1> <z1> <x2> <z2> [world] [page]"); return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), z1 = Integer.parseInt(args[2]);
            int x2 = Integer.parseInt(args[3]), z2 = Integer.parseInt(args[4]);
            String world = args.length >= 6 && !args[5].matches("\\d+") ? args[5]
                    : (sender instanceof Player p ? p.getWorld().getName() : "world");
            int page = parsePage(args, args.length >= 6 && !args[5].matches("\\d+") ? 6 : 5);

            int minCX = Math.min(x1, x2) >> 4, maxCX = Math.max(x1, x2) >> 4;
            int minCZ = Math.min(z1, z2) >> 4, maxCZ = Math.max(z1, z2) >> 4;

            // Collect all records in the area
            var db = plugin.getDatabaseManager();
            List<UnifiedRecord> allRecords = new ArrayList<>();
            for (int cx = minCX; cx <= maxCX; cx++)
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    allRecords.addAll(db.getRecordsByChunk(world, cx, cz, 1, Integer.MAX_VALUE));
                }
            allRecords.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));

            int total = allRecords.size();
            int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > totalPages) page = totalPages;
            int start = (page - 1) * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, total);

            sender.sendMessage("§6===== 区域 [" + x1 + "," + z1 + " ~ " + x2 + "," + z2 + "] 违禁报告 =====");
            if (allRecords.isEmpty()) {
                sender.sendMessage("§a该区域无违规记录。");
            } else {
                for (int i = start; i < end; i++) printRecord(sender, allRecords.get(i));
            }
            printPageFooter(sender, page, totalPages);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c坐标必须是整数。");
        }
        return true;
    }

    private boolean reportRes(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§e用法: /is report res <插件名> <区域名> [世界] [page]"); return true;
        }
        String pluginName = args[1], regionName = args[2];
        String worldName = args.length >= 4 && !args[3].matches("\\d+") ? args[3]
                : (sender instanceof Player p ? p.getWorld().getName() : "world");
        int page = parsePage(args, args.length >= 4 && !args[3].matches("\\d+") ? 4 : 3);

        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§c未找到世界: " + worldName); return true;
        }

        var bounds = plugin.getRegionWhitelistManager().getRegionBounds(pluginName, regionName, world);
        if (bounds == null) {
            sender.sendMessage("§c未找到区域: " + pluginName + "/" + regionName); return true;
        }

        int minCX = bounds.minX() >> 4, maxCX = bounds.maxX() >> 4;
        int minCZ = bounds.minZ() >> 4, maxCZ = bounds.maxZ() >> 4;

        var db = plugin.getDatabaseManager();
        List<UnifiedRecord> allRecords = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                allRecords.addAll(db.getRecordsByChunk(worldName, cx, cz, 1, Integer.MAX_VALUE));
            }
        allRecords.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));

        int total = allRecords.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        sender.sendMessage("§6===== 领地 " + pluginName + "/" + regionName + " 违禁报告 =====");
        if (allRecords.isEmpty()) {
            sender.sendMessage("§a该领地无违规记录。");
        } else {
            for (int i = start; i < end; i++) printRecord(sender, allRecords.get(i));
        }
        printPageFooter(sender, page, totalPages);
        return true;
    }

    private boolean reportWorld(CommandSender sender, String[] args) {
        String world = args.length >= 2 && !args[1].matches("\\d+") ? args[1]
                : (sender instanceof Player p ? p.getWorld().getName() : "world");
        int page = parsePage(args, args.length >= 2 && !args[1].matches("\\d+") ? 2 : 1);

        org.bukkit.World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            sender.sendMessage("§c未找到世界: " + world); return true;
        }

        var db = plugin.getDatabaseManager();
        List<UnifiedRecord> allRecords = new ArrayList<>();
        for (org.bukkit.Chunk chunk : bukkitWorld.getLoadedChunks()) {
            allRecords.addAll(db.getRecordsByChunk(world, chunk.getX(), chunk.getZ(), 1, Integer.MAX_VALUE));
        }
        // Dedup by item_hash (keep latest), then filter CLEAN markers
        java.util.Map<String, UnifiedRecord> deduped = new java.util.LinkedHashMap<>();
        for (UnifiedRecord r : allRecords) {
            String k = r.itemHash();
            UnifiedRecord existing = deduped.get(k);
            if (existing == null || r.scanTime() > existing.scanTime()) {
                deduped.put(k, r);
            }
        }
        allRecords = new ArrayList<>(deduped.values());
        allRecords.removeIf(r -> "CLEAN".equals(r.severity()));
        allRecords.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));

        int total = allRecords.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        sender.sendMessage("§6===== 世界 " + world + " 违禁报告 =====");
        if (allRecords.isEmpty()) {
            sender.sendMessage("§a该世界无违规记录（仅检查已加载区块）。");
        } else {
            sender.sendMessage("§7§o仅显示已加载区块的记录。");
            for (int i = start; i < end; i++) printRecord(sender, allRecords.get(i));
        }
        printPageFooter(sender, page, totalPages);
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

    /** Resolve a player by exact name (case-insensitive). Unlike Bukkit.getPlayer() this does NOT do prefix matching. */
    private Player resolveExactPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.report");
    }
}
