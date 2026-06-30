package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ScanService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles /is scan <sub> commands.
 * Phase 2 will fully implement area/res/world/full.
 */
public class ScanCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;
    private final ScanService scanService;

    public ScanCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
        this.scanService = plugin.getScanService();
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§e/is scan <chunk|player|area|res|world|full>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> handleChunk(sender);
            case "player" -> handlePlayer(sender, args);
            case "area"   -> handleArea(sender, args);
            case "res"    -> handleRes(sender, args);
            case "world"  -> handleWorld(sender, args);
            case "full"   -> handleFull(sender, args);
            default -> {
                sender.sendMessage("§e未知子命令: " + args[0] + "。可用: chunk|player|area|res|world|full");
                yield true;
            }
        };
    }

    private boolean handleChunk(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c此命令仅玩家可用。"); return true;
        }
        int cx = p.getLocation().getChunk().getX();
        int cz = p.getLocation().getChunk().getZ();
        sender.sendMessage("§e正在扫描当前区块 (" + cx + "," + cz + ")...");
        int flagged = scanService.scanChunk(p.getWorld(), cx, cz);
        sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
        return true;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan player <玩家名>"); return true;
        }
        String name = args[1];
        sender.sendMessage("§e正在扫描玩家 " + name + "...");
        int flagged = scanService.scanPlayer(name);
        sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
        return true;
    }

    private boolean handleArea(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§e用法: /is scan area <x1> <z1> <x2> <z2> [world]"); return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), z1 = Integer.parseInt(args[2]);
            int x2 = Integer.parseInt(args[3]), z2 = Integer.parseInt(args[4]);
            String world = args.length >= 6 ? args[5]
                    : (sender instanceof Player p ? p.getWorld().getName() : "world");
            sender.sendMessage("§e正在排列区域扫描任务...");
            scanService.scanArea(world, x1, z1, x2, z2)
                    .thenAccept(count -> sender.sendMessage("§a区域扫描完成: 发现 " + count + " 个违规物品。"));
        } catch (NumberFormatException e) {
            sender.sendMessage("§c坐标必须是整数。");
        }
        return true;
    }

    private boolean handleRes(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan res <领地名>"); return true;
        }
        String resName = args[1];
        sender.sendMessage("§e正在扫描领地 " + resName + "...");
        scanService.scanRes(resName)
                .thenAccept(count -> sender.sendMessage("§a领地扫描完成: 发现 " + count + " 个违规物品。"));
        return true;
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        String world = args.length >= 2 ? args[1]
                : (sender instanceof Player p ? p.getWorld().getName() : null);
        if (world == null) {
            sender.sendMessage("§e用法: /is scan world <世界名>"); return true;
        }
        sender.sendMessage("§e正在扫描世界 " + world + "...");
        scanService.scanWorld(world)
                .thenAccept(count -> sender.sendMessage("§a世界扫描完成: 发现 " + count + " 个违规物品。"));
        return true;
    }

    private boolean handleFull(CommandSender sender, String[] args) {
        String world = args.length >= 2 ? args[1] : null;
        sender.sendMessage("§e正在启动全盘扫描" + (world != null ? " (世界: " + world + ")" : "") + "...");
        scanService.scanFull()
                .thenAccept(count -> sender.sendMessage("§a全盘扫描完成: 发现 " + count + " 个违规物品。"));
        return true;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.scan");
    }
}
