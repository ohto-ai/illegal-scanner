package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Routes /is commands to sub-handlers based on the first argument.
 * Each sub-command group has its own handler class.
 */
public class CommandRouter implements CommandExecutor {

    private final IllegalScanner plugin;

    // Sub-command handlers (lazily initialized per group)
    private ScanCommandHandler scanHandler;
    private CheckCommandHandler checkHandler;
    private ViewCommandHandler viewHandler;
    private ReportCommandHandler reportHandler;
    private HistoryCommandHandler historyHandler;
    private MonitorCommandHandler monitorHandler;
    private ConfigCommandHandler configHandler;
    private WhitelistCommandHandler whitelistHandler;
    private WatchListCommandHandler watchlistHandler;

    public CommandRouter(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Quiet mode log suppression
        boolean wasLogSuppressed = false;
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) {
            wasLogSuppressed = IllegalScanner.setSuppressLog(true);
        }
        try {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            return switch (args[0].toLowerCase()) {
                case "scan"      -> getScanHandler().handle(sender, shift(args));
                case "check"     -> getCheckHandler().handle(sender, shift(args));
                case "view"      -> getViewHandlerInternal().handle(sender, shift(args));
                case "report"    -> getReportHandler().handle(sender, shift(args));
                case "history"   -> getHistoryHandler().handle(sender, shift(args));
                case "monitor"   -> getMonitorHandler().handle(sender, shift(args));
                case "config"    -> getConfigHandler().handle(sender, shift(args));
                case "whitelist" -> getWhitelistHandlerInternal().handle(sender, shift(args));
                case "watchlist" -> getWatchlistHandler().handle(sender, shift(args));
                // Legacy compatibility + convenience
                case "give"      -> handleGive(sender, shift(args));
                case "reload"    -> handleReload(sender);
                case "status"    -> handleStatus(sender);
                default -> { sendHelp(sender); yield true; }
            };
        } finally {
            if (wasLogSuppressed) {
                IllegalScanner.setSuppressLog(false);
            }
        }
    }

    // --- Handler accessors (lazy init) ---

    private ScanCommandHandler getScanHandler() {
        if (scanHandler == null) scanHandler = new ScanCommandHandler(plugin);
        return scanHandler;
    }

    private CheckCommandHandler getCheckHandler() {
        if (checkHandler == null) checkHandler = new CheckCommandHandler(plugin);
        return checkHandler;
    }

    private ViewCommandHandler getViewHandlerInternal() {
        if (viewHandler == null) viewHandler = new ViewCommandHandler(plugin);
        return viewHandler;
    }

    private ReportCommandHandler getReportHandler() {
        if (reportHandler == null) reportHandler = new ReportCommandHandler(plugin);
        return reportHandler;
    }

    private HistoryCommandHandler getHistoryHandler() {
        if (historyHandler == null) historyHandler = new HistoryCommandHandler(plugin);
        return historyHandler;
    }

    private MonitorCommandHandler getMonitorHandler() {
        if (monitorHandler == null) monitorHandler = new MonitorCommandHandler(plugin);
        return monitorHandler;
    }

    private ConfigCommandHandler getConfigHandler() {
        if (configHandler == null) configHandler = new ConfigCommandHandler(plugin);
        return configHandler;
    }

    private WhitelistCommandHandler getWhitelistHandlerInternal() {
        if (whitelistHandler == null) whitelistHandler = new WhitelistCommandHandler(plugin);
        return whitelistHandler;
    }

    private WatchListCommandHandler getWatchlistHandler() {
        if (watchlistHandler == null) watchlistHandler = new WatchListCommandHandler(plugin);
        return watchlistHandler;
    }

    /** Expose ViewCommandHandler for GUI navigation callbacks. */
    public ViewCommandHandler getViewHandler() {
        return getViewHandlerInternal();
    }

    public WhitelistCommandHandler getWhitelistHandler() {
        return getWhitelistHandlerInternal();
    }

    // --- Legacy helpers ---

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!hasAccess(sender, "illegalscanner.report")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("§cPlayer only."); return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§e用法: /is give <item_hash>"); return true;
        }
        String hash = args[0];
        var entry = plugin.getDatabaseManager().getItemByHash(hash);
        if (entry == null) {
            sender.sendMessage("§c未找到物品: " + hash); return true;
        }
        org.bukkit.inventory.ItemStack item = com.illegalscanner.scanner.NbtUtil.itemStackFromJson(entry.itemSnapshot());
        if (item == null || item.getType().isAir()) {
            sender.sendMessage("§c无法解析物品快照。"); return true;
        }
        var overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            for (var drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            sender.sendMessage("§a已给予物品 §f" + entry.itemType() + " §7(背包已满，多余掉落)");
        } else {
            sender.sendMessage("§a已给予物品 §f" + entry.itemType() + " §7— " + hash.substring(0, 8) + "...");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasAccess(sender, "illegalscanner.admin")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        plugin.getConfigManager().reload();
        plugin.getMessages().reload();
        sender.sendMessage("§a配置已重载。");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage("§6===== illegal-scanner Status =====");
        sender.sendMessage("§7扫描服务: §a运行中");
        sender.sendMessage("§7数据库: §a已连接");
        sender.sendMessage("§7验证器: §f" + plugin.getValidationEngine().getValidatorCount() + " 个");
        return true;
    }

    // --- Help ---

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== illegal-scanner v" + plugin.getPluginMeta().getVersion() + " =====");
        sender.sendMessage("§e/is scan <chunk|player|area|res|world|full> §7— 执行扫描并记录");
        sender.sendMessage("§e  world: /is scan world [世界名|all_world] [loaded_chunks|unloaded_chunks|all_chunks] §7(默认: 当前世界, loaded_chunks)");
        sender.sendMessage("§e/is check <item|player|chunk> §7— 快速检测不记录");
        sender.sendMessage("§e/is view <chunk|player|area|res|world|full|scan|record|item> §7— GUI 查看");
        sender.sendMessage("§e/is report <chunk|player|area|res|world|full|scan|record|item> §7— 文本查看");
        sender.sendMessage("§e/is history <chunk|player> §7— 历史记录");
        sender.sendMessage("§e/is monitor <enable|disable|status> §7— 实时监测");
        sender.sendMessage("§e/is config <reload|list|rules|monitor|scan> §7— 配置管理");
        sender.sendMessage("§e/is whitelist <player|item|chunk|area|res|world> §7— 白名单");
        sender.sendMessage("§e/is give <item_hash> §7— 获取物品");
        sender.sendMessage("§e/is reload §7— 重载配置");
        sender.sendMessage("§e/is status §7— 查看状态");
    }

    private boolean hasAccess(CommandSender sender, String permission) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) {
            return true;
        }
        return sender.hasPermission(permission);
    }

    /** Shift args array by 1 (remove first element). */
    private static String[] shift(String[] args) {
        if (args.length <= 1) return new String[0];
        String[] shifted = new String[args.length - 1];
        System.arraycopy(args, 1, shifted, 0, shifted.length);
        return shifted;
    }
}
