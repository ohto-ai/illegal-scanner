package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.monitor.MonitorEngine;
import org.bukkit.command.CommandSender;

/**
 * Handles /is monitor <enable|disable|status>.
 */
public class MonitorCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public MonitorCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) {
            sender.sendMessage("§e/is monitor <enable|disable|status>"); return true;
        }
        MonitorEngine engine = plugin.getMonitorEngine();
        if (engine == null) { sender.sendMessage("§cMonitor引擎未初始化。"); return true; }

        return switch (args[0].toLowerCase()) {
            case "enable" -> {
                engine.setEnabled(true);
                sender.sendMessage("§aMonitor 已启用。");
                yield true;
            }
            case "disable" -> {
                engine.setEnabled(false);
                sender.sendMessage("§eMonitor 已禁用。");
                yield true;
            }
            case "status" -> {
                sender.sendMessage("§6===== Monitor 状态 =====");
                sender.sendMessage("§7状态: " + (engine.isEnabled() ? "§a启用" : "§c禁用"));
                sender.sendMessage("§7活跃事件: §f" + engine.getActiveEvents());
                sender.sendMessage("§7去重窗口: §f" + engine.getFlushIntervalMs() / 1000 + "s");
                sender.sendMessage("§7保留天数: §f" + engine.getRetentionDays() + " days");
                yield true;
            }
            default -> { sender.sendMessage("§e未知子命令: " + args[0] + "。可用: enable|disable|status"); yield true; }
        };
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.admin");
    }
}
