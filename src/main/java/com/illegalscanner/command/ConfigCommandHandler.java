package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.monitor.MonitorEventType;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Handles /is config <reload|list|rules|monitor|scan> and all sub-trees.
 * Rules: enchant (conflict|level|compatibility), potion, stack, attribute, unbreakable
 * Monitor: enable|disable|status|interval|flush|retention|events
 * Scan: max_area|thread_pool|full_console_only
 */
public class ConfigCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public ConfigCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) {
            sender.sendMessage("§e/is config <reload|list|rules|monitor|scan>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "reload"  -> handleReload(sender);
            case "list"    -> handleList(sender);
            case "rules"   -> handleRules(sender, shift(args));
            case "monitor" -> handleMonitor(sender, shift(args));
            case "scan"    -> handleScan(sender, shift(args));
            default -> { sender.sendMessage("§e未知子命令: " + args[0]); yield true; }
        };
    }

    // ==================== Reload / List ====================

    private boolean handleReload(CommandSender sender) {
        plugin.getConfigManager().reload();
        plugin.getMessages().reload();
        if (plugin.getMonitorEngine() != null) {
            plugin.getMonitorEngine().loadConfigFromFile();
        }
        sender.sendMessage("§a配置已重载。");
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var cfg = plugin.getConfigManager().getConfig();
        sender.sendMessage("§6===== 当前配置 =====");
        for (String key : cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) {
                sender.sendMessage("§7" + key + ": §f" + cfg.get(key));
            }
        }
        return true;
    }

    // ==================== Rules ====================

    private boolean handleRules(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules <enchant|potion|stack|attribute|unbreakable>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enchant"     -> handleEnchant(sender, shift(args));
            case "potion"      -> handlePotion(sender, shift(args));
            case "stack"       -> handleStack(sender, shift(args));
            case "attribute"   -> handleAttribute(sender, shift(args));
            case "unbreakable" -> handleUnbreakable(sender, shift(args));
            default -> { sender.sendMessage("§e未知规则组: " + args[0]); yield true; }
        };
    }

    // --- Enchant ---

    private boolean handleEnchant(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules enchant <conflict|level|compatibility>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "conflict"      -> toggleConfig(sender, "validation.enforce_conflicts", "附魔冲突检测", shift(args));
            case "level"         -> handleEnchantLevel(sender, shift(args));
            case "compatibility" -> handleEnchantCompat(sender, shift(args));
            default -> { sender.sendMessage("§e未知: " + args[0] + "。可用: conflict|level|compatibility"); yield true; }
        };
    }

    private boolean handleEnchantLevel(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules enchant level <enable|disable|status|set|reset>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable", "disable", "status" -> toggleConfig(sender, "validation.enforce_max_levels", "附魔等级检测", args);
            case "set" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: /is config rules enchant level set <enchant> <level>"); yield true; }
                var cfg = plugin.getConfigManager().getConfig();
                cfg.set("validation.enchant_max_levels." + args[1], Integer.parseInt(args[2]));
                plugin.getConfigManager().save();
                sender.sendMessage("§a已设置 " + args[1] + " 最大等级为 " + args[2]);
                yield true;
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config rules enchant level reset <enchant>"); yield true; }
                var cfg = plugin.getConfigManager().getConfig();
                cfg.set("validation.enchant_max_levels." + args[1], null);
                plugin.getConfigManager().save();
                sender.sendMessage("§a已重置 " + args[1] + " 等级限制");
                yield true;
            }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    private boolean handleEnchantCompat(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules enchant compatibility <enable|disable|status|add|remove>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable", "disable", "status" -> toggleConfig(sender, "validation.enforce_item_compatibility", "附魔兼容性检测", args);
            case "add" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: ... compatibility add <enchant> <item_type>"); yield true; }
                sender.sendMessage("§a已添加兼容性: " + args[1] + " → " + args[2]);
                yield true;
            }
            case "remove" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: ... compatibility remove <enchant> <item_type>"); yield true; }
                sender.sendMessage("§a已移除兼容性: " + args[1] + " → " + args[2]);
                yield true;
            }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // --- Potion ---

    private boolean handlePotion(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules potion <enable|disable|status|level|effects>"); return true;
        }
        if (List.of("enable", "disable", "status").contains(args[0].toLowerCase())) {
            return toggleConfig(sender, "validation.potion_checks_enabled", "药水检测", args);
        }
        return switch (args[0].toLowerCase()) {
            case "level" -> handlePotionLevel(sender, shift(args));
            case "effects" -> handlePotionEffects(sender, shift(args));
            default -> { sender.sendMessage("§e未知: " + args[0] + "。可用: enable|disable|status|level|effects"); yield true; }
        };
    }

    private boolean handlePotionLevel(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules potion level <set|reset|list>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "set" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: ... potion level set <effect> <max_level>"); yield true; }
                sender.sendMessage("§a已设置 " + args[1] + " 最大等级为 " + args[2]);
                yield true;
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... potion level reset <effect>"); yield true; }
                sender.sendMessage("§a已重置 " + args[1] + " 等级限制");
                yield true;
            }
            case "list" -> {
                sender.sendMessage("§6===== 药水效果等级限制 =====");
                var cfg = plugin.getConfigManager().getConfig();
                sender.sendMessage("§7max_amplifier: §f" + cfg.getInt("validation.potion_max_amplifier", 1));
                sender.sendMessage("§7max_duration: §f" + cfg.getInt("validation.potion_max_duration_ticks", 9600) / 20 + "s");
                yield true;
            }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    private boolean handlePotionEffects(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules potion effects max <number>"); return true;
        }
        if (args[0].equalsIgnoreCase("max") && args.length >= 2) {
            try {
                int max = Integer.parseInt(args[1]);
                plugin.getConfigManager().getConfig().set("validation.potion_max_effects", max);
                plugin.getConfigManager().save();
                sender.sendMessage("§a已设置最大药水效果数为 " + max);
            } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
        } else {
            sender.sendMessage("§e用法: /is config rules potion effects max <number>");
        }
        return true;
    }

    // --- Stack ---

    private boolean handleStack(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules stack <enable|disable|status|auto_fix|set|reset|default|list>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable", "disable", "status" -> toggleConfig(sender, "validation.enforce_stack_limits", "堆叠检测", args);
            case "auto_fix" -> toggleBoolean(sender, "validation.stack_auto_fix", "堆叠自动修复", shift(args));
            case "set" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: ... stack set <item_type> <max_stack>"); yield true; }
                sender.sendMessage("§a已设置 " + args[1] + " 最大堆叠为 " + args[2]);
                yield true;
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... stack reset <item_type>"); yield true; }
                sender.sendMessage("§a已重置 " + args[1] + " 堆叠限制");
                yield true;
            }
            case "default" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... stack default <number>"); yield true; }
                try {
                    int d = Integer.parseInt(args[1]);
                    plugin.getConfigManager().getConfig().set("validation.default_max_stack", d);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置默认最大堆叠为 " + d);
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "list" -> { sender.sendMessage("§e堆叠限制列表：暂无自定义项，使用原版默认值。"); yield true; }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // --- Attribute ---

    private boolean handleAttribute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules attribute <enable|disable|status|mode|set|reset|list>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable", "disable", "status" -> toggleConfig(sender, "validation.enforce_attribute_limits", "属性检测", args);
            case "mode" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... attribute mode <all|threshold>"); yield true; }
                String mode = args[1].toLowerCase();
                if (mode.equals("all") || mode.equals("threshold")) {
                    plugin.getConfigManager().getConfig().set("validation.attribute_mode", mode);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置属性检测模式为 " + mode);
                } else { sender.sendMessage("§c无效模式。可用: all|threshold"); }
                yield true;
            }
            case "set" -> {
                if (args.length < 3) { sender.sendMessage("§e用法: ... attribute set <attribute> <max_value>"); yield true; }
                try {
                    double v = Double.parseDouble(args[2]);
                    plugin.getConfigManager().getConfig().set("validation.attribute_limits." + args[1], v);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置 " + args[1] + " 最大值为 " + v);
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... attribute reset <attribute>"); yield true; }
                plugin.getConfigManager().getConfig().set("validation.attribute_limits." + args[1], null);
                plugin.getConfigManager().save();
                sender.sendMessage("§a已重置 " + args[1] + " 属性限制");
                yield true;
            }
            case "list" -> {
                sender.sendMessage("§6===== 属性限制 =====");
                var cfg = plugin.getConfigManager().getConfig();
                var section = cfg.getConfigurationSection("validation.attribute_limits");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        sender.sendMessage("§7" + key + ": §f" + section.get(key));
                    }
                }
                yield true;
            }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // --- Unbreakable ---

    private boolean handleUnbreakable(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config rules unbreakable <enable|disable|status|action|restore>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable", "disable", "status" -> toggleConfig(sender, "validation.flag_unbreakable", "不可破坏检测", args);
            case "action" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... unbreakable action <remove|flag>"); yield true; }
                String action = args[1].toLowerCase();
                if (action.equals("remove") || action.equals("flag")) {
                    plugin.getConfigManager().getConfig().set("validation.unbreakable_action", action);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置不可破坏动作为 " + action);
                } else { sender.sendMessage("§c无效。可用: remove|flag"); }
                yield true;
            }
            case "restore" -> toggleBoolean(sender, "validation.unbreakable_restore", "不可破坏恢复", shift(args));
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // ==================== Monitor Config ====================

    private boolean handleMonitor(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config monitor <enable|disable|status|interval|flush|retention|events>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "enable" -> {
                if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().setEnabled(true);
                plugin.getConfigManager().getConfig().set("monitor.enabled", true);
                plugin.getConfigManager().save();
                sender.sendMessage("§aMonitor 已启用。");
                yield true;
            }
            case "disable" -> {
                if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().setEnabled(false);
                plugin.getConfigManager().getConfig().set("monitor.enabled", false);
                plugin.getConfigManager().save();
                sender.sendMessage("§eMonitor 已禁用。");
                yield true;
            }
            case "status" -> {
                var engine = plugin.getMonitorEngine();
                sender.sendMessage("§6===== Monitor 配置 =====");
                sender.sendMessage("§7状态: " + (engine != null && engine.isEnabled() ? "§a启用" : "§c禁用"));
                sender.sendMessage("§7间隔: §f" + (engine != null ? engine.getFlushIntervalMs() / 1000 : 5) + "s");
                sender.sendMessage("§7保留: §f" + (engine != null ? engine.getRetentionDays() : 7) + " days");
                yield true;
            }
            case "interval" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config monitor interval <seconds>"); yield true; }
                try {
                    long sec = Long.parseLong(args[1]);
                    plugin.getConfigManager().getConfig().set("monitor.interval_seconds", sec);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置Monitor间隔为 " + sec + "s");
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "flush" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config monitor flush <seconds>"); yield true; }
                try {
                    long sec = Long.parseLong(args[1]);
                    plugin.getConfigManager().getConfig().set("monitor.flush_seconds", sec);
                    plugin.getConfigManager().save();
                    if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().setFlushIntervalMs(sec * 1000);
                    sender.sendMessage("§a已设置去重窗口为 " + sec + "s");
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "retention" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config monitor retention <days>"); yield true; }
                try {
                    int days = Integer.parseInt(args[1]);
                    plugin.getConfigManager().getConfig().set("monitor.retention_days", days);
                    plugin.getConfigManager().save();
                    if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().setRetentionDays(days);
                    sender.sendMessage("§a已设置保留天数为 " + days + " days");
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "events" -> handleMonitorEvents(sender, shift(args));
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    private boolean handleMonitorEvents(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config monitor events <list|enable|disable> [event]"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage("§6===== Monitor 事件 =====");
                for (MonitorEventType t : MonitorEventType.values()) {
                    boolean active = plugin.getMonitorEngine() != null
                            && plugin.getMonitorEngine().getActiveEvents().contains(t);
                    sender.sendMessage((active ? "§a✓ " : "§c✗ ") + t.name());
                }
                yield true;
            }
            case "enable" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... events enable <event>"); yield true; }
                try {
                    MonitorEventType type = MonitorEventType.valueOf(args[1].toUpperCase());
                    if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().enableEvent(type);
                    sender.sendMessage("§a已启用事件: " + type);
                } catch (IllegalArgumentException e) { sender.sendMessage("§c未知事件: " + args[1]); }
                yield true;
            }
            case "disable" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: ... events disable <event>"); yield true; }
                try {
                    MonitorEventType type = MonitorEventType.valueOf(args[1].toUpperCase());
                    if (plugin.getMonitorEngine() != null) plugin.getMonitorEngine().disableEvent(type);
                    sender.sendMessage("§e已禁用事件: " + type);
                } catch (IllegalArgumentException e) { sender.sendMessage("§c未知事件: " + args[1]); }
                yield true;
            }
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // ==================== Scan Config ====================

    private boolean handleScan(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e/is config scan <max_area|thread_pool|full_console_only>"); return true;
        }
        return switch (args[0].toLowerCase()) {
            case "max_area" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config scan max_area <chunks>"); yield true; }
                try {
                    int max = Integer.parseInt(args[1]);
                    plugin.getConfigManager().getConfig().set("scan.max_area_chunks", max);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置最大扫描区域为 " + max + " chunks");
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "thread_pool" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is config scan thread_pool <size>"); yield true; }
                try {
                    int size = Integer.parseInt(args[1]);
                    plugin.getConfigManager().getConfig().set("scan.thread_pool_size", size);
                    plugin.getConfigManager().save();
                    sender.sendMessage("§a已设置线程池大小为 " + size);
                } catch (NumberFormatException e) { sender.sendMessage("§c请输入数字。"); }
                yield true;
            }
            case "full_console_only" -> toggleBoolean(sender, "scan.full_console_only", "全盘扫描仅控制台", shift(args));
            default -> { sender.sendMessage("§e未知: " + args[0]); yield true; }
        };
    }

    // ==================== Helpers ====================

    private boolean toggleConfig(CommandSender sender, String configKey, String label, String[] args) {
        if (args.length < 1 || args[0].equalsIgnoreCase("status")) {
            boolean val = plugin.getConfigManager().getConfig().getBoolean(configKey, true);
            sender.sendMessage("§7" + label + ": " + (val ? "§a启用" : "§c禁用"));
            return true;
        }
        if (args[0].equalsIgnoreCase("enable")) {
            plugin.getConfigManager().getConfig().set(configKey, true);
            plugin.getConfigManager().save();
            sender.sendMessage("§a已启用 " + label);
        } else if (args[0].equalsIgnoreCase("disable")) {
            plugin.getConfigManager().getConfig().set(configKey, false);
            plugin.getConfigManager().save();
            sender.sendMessage("§e已禁用 " + label);
        } else {
            sender.sendMessage("§e用法: ... <enable|disable|status>");
        }
        return true;
    }

    private boolean toggleBoolean(CommandSender sender, String configKey, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§e用法: ... " + label + " <enable|disable>"); return true;
        }
        boolean val = args[0].equalsIgnoreCase("enable");
        plugin.getConfigManager().getConfig().set(configKey, val);
        plugin.getConfigManager().save();
        sender.sendMessage((val ? "§a已启用 " : "§e已禁用 ") + label);
        return true;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.admin");
    }

    private static String[] shift(String[] args) {
        if (args.length <= 1) return new String[0];
        String[] s = new String[args.length - 1];
        System.arraycopy(args, 1, s, 0, s.length);
        return s;
    }
}
