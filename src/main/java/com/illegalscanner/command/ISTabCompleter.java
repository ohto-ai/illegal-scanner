package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ISTabCompleter implements TabCompleter {

    private final IllegalScanner plugin;

    private static final List<String> MAIN = List.of(
            "scan", "check", "view", "report", "history", "monitor", "config",
            "whitelist", "give", "reload", "status"
    );

    private static final List<String> SCAN_SUB = List.of("chunk", "player", "area", "res", "world", "full");
    private static final List<String> CHECK_SUB = List.of("item", "player", "chunk");
    private static final List<String> VIEW_SUB = List.of("chunk", "player", "area", "world", "scan", "record", "item");
    private static final List<String> REPORT_SUB = List.of("chunk", "player", "area", "world", "scan", "record", "item");
    private static final List<String> HISTORY_SUB = List.of("chunk", "player");
    private static final List<String> MONITOR_SUB = List.of("enable", "disable", "status");
    private static final List<String> CONFIG_SUB = List.of("reload", "list", "rules", "monitor", "scan");
    private static final List<String> WL_TYPES = List.of("player", "item", "chunk", "area", "res", "world");
    private static final List<String> WL_ACTIONS = List.of("add", "remove", "list", "clear");

    public ISTabCompleter(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String partial;

        if (args.length == 1) {
            partial = args[0].toLowerCase();
            for (String c : MAIN) if (c.startsWith(partial)) out.add(c);
            return out;
        }

        String cmd = args[0].toLowerCase();
        partial = args.length >= 2 ? args[args.length - 1].toLowerCase() : "";

        if (args.length == 2) {
            return switch (cmd) {
                case "scan"      -> filter(SCAN_SUB, partial);
                case "check"     -> filter(CHECK_SUB, partial);
                case "view"      -> filter(VIEW_SUB, partial);
                case "report"    -> filter(REPORT_SUB, partial);
                case "history"   -> filter(HISTORY_SUB, partial);
                case "monitor"   -> filter(MONITOR_SUB, partial);
                case "config"    -> filter(CONFIG_SUB, partial);
                case "whitelist" -> filter(WL_TYPES, partial);
                default -> out;
            };
        }

        // args.length >= 3
        return switch (cmd) {
            case "scan" -> completeScan(args);
            case "check" -> completeCheck(args);
            case "view" -> completeView(args);
            case "report" -> completeReport(args);
            case "history" -> completeHistory(args);
            case "whitelist" -> completeWhitelist(args, partial);
            default -> out;
        };
    }

    private List<String> completeScan(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        if ((sub.equals("world") || sub.equals("full")) && args.length == 3) return worldNames(partial);
        return List.of();
    }

    private List<String> completeCheck(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        return List.of();
    }

    private List<String> completeView(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        if (sub.equals("world") && args.length == 3) return worldNames(partial);
        if (sub.equals("res") && args.length == 3) return plugin.getRegionWhitelistManager().getAvailablePlugins().stream().filter(p -> p.toLowerCase().startsWith(partial)).toList();
        if (sub.equals("record") && args.length == 3) return List.of("SCAN", "MONITOR");
        return List.of();
    }

    private List<String> completeReport(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        if (sub.equals("record") && args.length == 3) return List.of("SCAN", "MONITOR");
        return List.of();
    }

    private List<String> completeHistory(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        return List.of();
    }

    private List<String> completeWhitelist(String[] args, String partial) {
        String type = args[1].toLowerCase();
        if (args.length == 3) {
            if (type.equals("player")) return filter(WL_ACTIONS, partial);
            if (type.equals("item")) return filter(List.of("add", "gui", "remove", "list", "clear"), partial);
            return filter(List.of("add", "remove", "list", "clear"), partial);
        }
        if (args.length == 4) {
            String action = args[2].toLowerCase();
            if (type.equals("player") && (action.equals("add") || action.equals("remove")))
                return onlinePlayerNames(partial);
            if (type.equals("res") && action.equals("add"))
                return plugin.getRegionWhitelistManager().getAvailablePlugins().stream().filter(p -> p.toLowerCase().startsWith(partial)).toList();
        }
        return List.of();
    }

    private List<String> filter(List<String> source, String partial) {
        return source.stream().filter(s -> s.startsWith(partial)).toList();
    }

    private List<String> onlinePlayerNames(String partial) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(partial)).toList();
    }

    private List<String> worldNames(String partial) {
        return Bukkit.getWorlds().stream()
                .map(w -> w.getName()).filter(n -> n.toLowerCase().startsWith(partial)).toList();
    }
}
