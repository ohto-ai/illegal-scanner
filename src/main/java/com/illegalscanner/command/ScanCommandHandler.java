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
            sender.sendMessage("§e  world: /is scan world [世界名|all_world] [loaded_chunks|unloaded_chunks|all_chunks]");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> handleChunk(sender);
            case "player" -> handlePlayer(sender, args);
            case "area"   -> handleArea(sender, args);
            case "res"    -> handleRes(sender, args);
            case "world"  -> handleWorld(sender, args);
            case "full"   -> handleFull(sender);
            case "pause"  -> handlePause(sender, args);
            case "resume" -> handleResume(sender, args);
            case "stop"   -> handleStop(sender, args);
            case "restart" -> handleRestart(sender, args);
            default -> {
                sender.sendMessage("§e未知子命令: " + args[0] + "。可用: chunk|player|area|res|world|full|pause|resume|stop|restart");
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
        int flagged = scanService.scanChunk(p.getWorld(), cx, cz, System.currentTimeMillis());
        sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
        return true;
    }

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan player [-online|-offline|-all] [<玩家名>]");
            return true;
        }

        String flag = null;
        String name = null;

        // Parse optional flag
        if (args[1].startsWith("-")) {
            flag = args[1].toLowerCase();
            if (!flag.equals("-online") && !flag.equals("-offline") && !flag.equals("-all")) {
                sender.sendMessage("§c未知标志: " + flag + "。可用: -online, -offline, -all");
                return true;
            }
            if (args.length >= 3) name = args[2];
        } else {
            name = args[1]; // backward compatible: just a player name
        }

        // No flag, just a name — backward compatible (online first, then offline)
        if (flag == null) {
            sender.sendMessage("§e正在扫描玩家 " + name + "...");
            int flagged = scanService.scanPlayer(name);
            sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
            return true;
        }

        // Batch: no name given → scan all players of that type
        if (name == null) {
            String modeLabel = flag.substring(1); // "online", "offline", or "all"
            sender.sendMessage("§e正在扫描所有 " + modeLabel + " 玩家...");

            if (flag.equals("-online")) {
                scanService.scanAllOnlinePlayers()
                        .thenAccept(count -> sender.sendMessage("§a所有在线玩家扫描完成: 发现 " + count + " 个违规物品。"));
            } else if (flag.equals("-offline")) {
                scanService.scanAllOfflinePlayers()
                        .thenAccept(count -> sender.sendMessage("§a所有离线玩家扫描完成: 发现 " + count + " 个违规物品。"));
            } else { // -all
                var onlineFuture = scanService.scanAllOnlinePlayers();
                var offlineFuture = scanService.scanAllOfflinePlayers();
                onlineFuture.thenCombine(offlineFuture, (online, offline) -> {
                    sender.sendMessage("§a全量玩家扫描完成: 在线 " + online + " + 离线 " + offline + " = " + (online + offline) + " 个违规物品。");
                    return online + offline;
                });
            }
            return true;
        }

        // Flag + name: scan specific player in that mode
        sender.sendMessage("§e正在扫描玩家 " + name + " (" + flag.substring(1) + ")...");

        if (flag.equals("-online")) {
            int flagged = scanService.scanOnlinePlayerByName(name);
            if (flagged < 0) {
                sender.sendMessage("§c玩家不在线: " + name);
            } else {
                sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
            }
        } else if (flag.equals("-offline")) {
            int flagged = scanService.scanOfflinePlayerByName(name);
            if (flagged < 0) {
                sender.sendMessage("§c未找到离线玩家数据: " + name);
            } else {
                sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
            }
        } else { // -all
            int flagged = scanService.scanPlayer(name); // tries online first, then offline
            sender.sendMessage("§a扫描完成: 发现 " + flagged + " 个违规物品。");
        }
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

    // Map user-facing chunk mode keywords to internal ScanService mode names
    private static String toInternalMode(String keyword) {
        return switch (keyword) {
            case "loaded_chunks" -> "loaded";
            case "unloaded_chunks" -> "unloaded";
            case "all_chunks" -> "all";
            default -> throw new IllegalArgumentException("Unknown mode: " + keyword);
        };
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        // Syntax: /is scan world [世界名|all_world] [loaded_chunks|unloaded_chunks|all_chunks]
        // First arg = world name (required for mode), second arg = chunk mode (optional)
        // Default: current world, loaded_chunks

        String worldName = null;
        String chunkMode = "loaded_chunks"; // default

        // Parse world name (args[1])
        if (args.length >= 2) {
            worldName = args[1];
        }

        // Parse chunk mode (args[2]) — only available when world name is given
        if (worldName != null && args.length >= 3) {
            String arg2 = args[2].toLowerCase();
            if (arg2.equals("loaded_chunks") || arg2.equals("unloaded_chunks") || arg2.equals("all_chunks")) {
                chunkMode = arg2;
            } else {
                sender.sendMessage("§c未知模式: " + arg2 + "。可用: loaded_chunks, unloaded_chunks, all_chunks");
                return true;
            }
        }

        // Resolve world name default
        if (worldName == null) {
            if (sender instanceof Player p) {
                worldName = p.getWorld().getName();
            } else {
                sender.sendMessage("§e用法: /is scan world [世界名|all_world] [loaded_chunks|unloaded_chunks|all_chunks]");
                return true;
            }
        }

        String internalMode = toInternalMode(chunkMode);

        // all_world → scan every world
        if (worldName.equalsIgnoreCase("all_world")) {
            sender.sendMessage("§e正在扫描所有世界 (" + chunkMode + ")...");
            scanAllWorlds(sender, internalMode);
            return true;
        }

        sender.sendMessage("§e正在扫描世界 " + worldName + " (" + chunkMode + ")...");
        scanService.scanWorld(worldName, internalMode)
                .thenAccept(count -> sender.sendMessage("§a世界扫描完成: 发现 " + count + " 个违规物品。"));
        return true;
    }

    /**
     * Scan every loaded world with the given mode.
     * Each world gets its own scan session.
     */
    private void scanAllWorlds(CommandSender sender, String mode) {
        var worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            sender.sendMessage("§c没有已加载的世界。");
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(worlds.size());
        java.util.concurrent.atomic.AtomicInteger totalFlagged = new java.util.concurrent.atomic.AtomicInteger(0);
        for (org.bukkit.World world : worlds) {
            String wname = world.getName();
            sender.sendMessage("§7  → 开始扫描世界: " + wname);
            scanService.scanWorld(wname, mode)
                    .thenAccept(count -> {
                        totalFlagged.addAndGet(count);
                        sender.sendMessage("§7  → " + wname + " 扫描完成: " + count + " 违规");
                        if (remaining.decrementAndGet() == 0) {
                            sender.sendMessage("§a所有世界扫描完成: 共发现 " + totalFlagged.get() + " 个违规物品。");
                        }
                    });
        }
    }

    private boolean handleFull(CommandSender sender) {
        sender.sendMessage("§e正在排列全服扫描任务（所有世界的已加载区块）...");
        scanService.scanFull()
                .thenAccept(count -> sender.sendMessage("§a全服扫描完成: 发现 " + count + " 个违规物品。"));
        return true;
    }

    private boolean handlePause(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan pause <sessionId>");
            return true;
        }
        try {
            int sessionId = Integer.parseInt(args[1]);
            String status = scanService.getSessionStatus(sessionId);
            if (status == null) {
                sender.sendMessage("§c未找到扫描会话 #" + sessionId);
                return true;
            }
            if (!"RUNNING".equals(status)) {
                sender.sendMessage("§c扫描会话 #" + sessionId + " 当前状态为 " + status + "，无法暂停。");
                return true;
            }
            scanService.pauseScan(sessionId);
            sender.sendMessage("§e扫描会话 #" + sessionId + " 已暂停。");
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效的会话ID。");
        }
        return true;
    }

    private boolean handleResume(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan resume <sessionId>");
            return true;
        }
        try {
            int sessionId = Integer.parseInt(args[1]);
            String status = scanService.getSessionStatus(sessionId);
            if (status == null) {
                sender.sendMessage("§c未找到扫描会话 #" + sessionId);
                return true;
            }
            if (!"PAUSED".equals(status)) {
                sender.sendMessage("§c扫描会话 #" + sessionId + " 当前状态为 " + status + "，无法继续。");
                return true;
            }
            scanService.resumeScan(sessionId);
            sender.sendMessage("§a扫描会话 #" + sessionId + " 已继续。"
                    + " (如暂停状态因重启丢失，将自动重新开始扫描)");
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效的会话ID。");
        }
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan stop <sessionId>");
            return true;
        }
        try {
            int sessionId = Integer.parseInt(args[1]);
            String status = scanService.getSessionStatus(sessionId);
            if (status == null) {
                sender.sendMessage("§c未找到扫描会话 #" + sessionId);
                return true;
            }
            if (!"RUNNING".equals(status) && !"PAUSED".equals(status)) {
                sender.sendMessage("§c扫描会话 #" + sessionId + " 当前状态为 " + status + "，无法停止。");
                return true;
            }
            scanService.stopScan(sessionId);
            sender.sendMessage("§c扫描会话 #" + sessionId + " 已停止。");
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效的会话ID。");
        }
        return true;
    }

    private boolean handleRestart(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is scan restart <sessionId>");
            return true;
        }
        try {
            int sessionId = Integer.parseInt(args[1]);
            String status = scanService.getSessionStatus(sessionId);
            if (status == null) {
                sender.sendMessage("§c未找到扫描会话 #" + sessionId);
                return true;
            }
            if (!"STOPPED".equals(status) && !"COMPLETED".equals(status) && !"PAUSED".equals(status)) {
                sender.sendMessage("§c扫描会话 #" + sessionId + " 当前状态为 " + status + "，无法重新开始。");
                return true;
            }
            if (scanService.isSessionRunning(sessionId)) {
                sender.sendMessage("§c扫描会话 #" + sessionId + " 正在运行中，请先暂停或停止。");
                return true;
            }
            scanService.restartScan(sessionId);
            sender.sendMessage("§a扫描会话 #" + sessionId + " 已重新开始。");
        } catch (NumberFormatException e) {
            sender.sendMessage("§c无效的会话ID。");
        }
        return true;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.scan");
    }
}
