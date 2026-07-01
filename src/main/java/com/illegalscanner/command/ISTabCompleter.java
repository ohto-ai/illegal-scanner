package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.monitor.MonitorEventType;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ISTabCompleter implements TabCompleter {

    private final IllegalScanner plugin;

    private static final List<String> MAIN = List.of(
            "scan", "check", "view", "report", "history", "monitor", "config",
            "whitelist", "watchlist", "hash", "give", "reload", "status"
    );

    private static final List<String> SCAN_SUB = List.of("chunk", "player", "area", "res", "world", "full", "pause", "resume", "stop", "restart");
    private static final List<String> CHECK_SUB = List.of("item", "player", "chunk");
    private static final List<String> VIEW_SUB = List.of("chunk", "player", "area", "res", "world", "full", "scan", "record", "item");
    private static final List<String> REPORT_SUB = List.of("chunk", "player", "area", "res", "world", "scan", "record", "item");
    private static final List<String> HISTORY_SUB = List.of("chunk", "player");
    private static final List<String> MONITOR_SUB = List.of("enable", "disable", "status");
    private static final List<String> CONFIG_SUB = List.of("reload", "list", "rules", "monitor", "scan");
    private static final List<String> WL_TYPES = List.of("player", "item", "chunk", "area", "res", "world");
    private static final List<String> WL_ACTIONS = List.of("add", "remove", "list", "clear");

    // --- Config sub-tree constants ---
    private static final List<String> CONFIG_RULES = List.of("enchant", "potion", "stack", "attribute", "unbreakable");
    private static final List<String> CONFIG_ENCHANT_SUB = List.of("conflict", "level", "compatibility");
    private static final List<String> CONFIG_POTION_SUB = List.of("enable", "disable", "status", "level", "effects");
    private static final List<String> CONFIG_POTION_LEVEL_SUB = List.of("set", "reset", "list");
    private static final List<String> CONFIG_STACK_SUB = List.of("enable", "disable", "status", "auto_fix", "set", "reset", "default", "list");
    private static final List<String> CONFIG_ATTRIBUTE_SUB = List.of("enable", "disable", "status", "mode", "set", "reset", "list");
    private static final List<String> CONFIG_UNBREAKABLE_SUB = List.of("enable", "disable", "status", "action", "restore");
    private static final List<String> CONFIG_MONITOR_SUB = List.of("enable", "disable", "status", "interval", "flush", "retention", "events");
    private static final List<String> CONFIG_MONITOR_EVENTS_SUB = List.of("list", "enable", "disable");
    private static final List<String> CONFIG_SCAN_SUB = List.of("max_area", "thread_pool", "console_only");
    private static final List<String> TOGGLE_ACTIONS = List.of("enable", "disable", "status");

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
                case "watchlist" -> filter(List.of("add", "remove", "list", "clear"), partial);
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
            case "config" -> completeConfig(args);
            case "whitelist" -> completeWhitelist(sender, args, partial);
            case "watchlist" -> completeWatchlist(args, partial);
            default -> out;
        };
    }

    private List<String> completeScan(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";

        if (sub.equals("player")) {
            if (args.length == 3) {
                // Suggest flags AND online player names
                List<String> suggestions = new java.util.ArrayList<>();
                suggestions.add("-online");
                suggestions.add("-offline");
                suggestions.add("-all");
                suggestions.addAll(onlinePlayerNames(partial));
                return suggestions.stream().filter(s -> s.toLowerCase().startsWith(partial)).toList();
            }
            if (args.length == 4) {
                // After a flag or player name — suggest player names
                return onlinePlayerNames(partial);
            }
        }
        if (sub.equals("world")) {
            if (args.length == 3) {
                // Only suggest world names + all_world (no modes — must specify world first)
                List<String> suggestions = new java.util.ArrayList<>();
                suggestions.add("all_world");
                suggestions.addAll(worldNames(partial));
                return suggestions.stream().filter(s -> s.toLowerCase().startsWith(partial)).toList();
            }
            if (args.length == 4) {
                // After world name → suggest chunk modes
                return filter(List.of("loaded_chunks", "unloaded_chunks", "all_chunks"), partial);
            }
        }
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
        if (sub.equals("res") && args.length == 4) return worldNames(partial);
        if (sub.equals("record") && args.length == 3) return List.of("SCAN", "MONITOR");
        if (sub.equals("area") && args.length == 6) return worldNames(partial);
        return List.of();
    }

    private List<String> completeReport(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        if (sub.equals("world") && args.length == 3) return worldNames(partial);
        if (sub.equals("record") && args.length == 3) return List.of("SCAN", "MONITOR");
        if (sub.equals("area") && args.length == 6) return worldNames(partial);
        if (sub.equals("res") && args.length == 5) return worldNames(partial);
        return List.of();
    }

    private List<String> completeHistory(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";
        if (sub.equals("player") && args.length == 3) return onlinePlayerNames(partial);
        return List.of();
    }

    private List<String> completeWhitelist(CommandSender sender, String[] args, String partial) {
        String type = args[1].toLowerCase();
        if (args.length == 3) {
            if (type.equals("player")) return filter(WL_ACTIONS, partial);
            if (type.equals("item")) return filter(List.of("add", "gui", "remove", "list", "clear", "import"), partial);
            return filter(List.of("add", "remove", "list", "clear"), partial);
        }

        String action = args[2].toLowerCase();

        // item import: /is whitelist item import from container <x> <y> <z>
        if (type.equals("item") && action.equals("import")) {
            return completeItemImport(sender, args, partial);
        }

        if (args.length == 4) {
            if (type.equals("player") && (action.equals("add") || action.equals("remove")))
                return onlinePlayerNames(partial);
            if (type.equals("world") && (action.equals("add") || action.equals("remove")))
                return worldNames(partial);
            if (type.equals("res") && action.equals("add"))
                return plugin.getRegionWhitelistManager().getAvailablePlugins().stream().filter(p -> p.toLowerCase().startsWith(partial)).toList();
        }
        return List.of();
    }

    /** Tab-complete /is whitelist item import from container [x] [y] [z]. */
    private List<String> completeItemImport(CommandSender sender, String[] args, String partial) {
        if (args.length == 4) return filter(List.of("from"), partial);
        if (args.length == 5) return filter(List.of("container"), partial);
        // args.length 6-8: container coordinates — suggest nearby containers
        return nearbyContainerCoords(sender, args, partial);
    }

    /**
     * Scans blocks within 6 blocks of the sender for containers (InventoryHolder)
     * and returns the coordinate matching the current argument position.
     */
    private List<String> nearbyContainerCoords(CommandSender sender, String[] args, String partial) {
        if (!(sender instanceof Player player)) return List.of();
        var loc = player.getLocation();
        var world = player.getWorld();
        int radius = 6;

        java.util.Set<Integer> xs = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> ys = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> zs = new java.util.LinkedHashSet<>();

        // Single scan over the 6-block radius to collect container coordinates
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var block = world.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
                    if (block.getState() instanceof org.bukkit.inventory.InventoryHolder) {
                        xs.add(block.getX());
                        ys.add(block.getY());
                        zs.add(block.getZ());
                    }
                }
            }
        }

        // args[5] = x, args[6] = y, args[7] = z
        if (args.length == 6) {
            return xs.stream().map(String::valueOf).filter(s -> s.startsWith(partial)).limit(20).toList();
        }
        if (args.length == 7) {
            int enteredX;
            try { enteredX = Integer.parseInt(args[5]); } catch (NumberFormatException e) { return List.of(); }
            // Re-scan to collect Y values for containers whose X matches
            java.util.Set<Integer> filteredY = new java.util.LinkedHashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        var b = world.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
                        if (b.getX() == enteredX && b.getState() instanceof org.bukkit.inventory.InventoryHolder)
                            filteredY.add(b.getY());
                    }
                }
            }
            return filteredY.stream().map(String::valueOf).filter(s -> s.startsWith(partial)).limit(20).toList();
        }
        if (args.length == 8) {
            int enteredX, enteredY;
            try {
                enteredX = Integer.parseInt(args[5]);
                enteredY = Integer.parseInt(args[6]);
            } catch (NumberFormatException e) { return List.of(); }
            java.util.Set<Integer> filteredZ = new java.util.LinkedHashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        var b = world.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
                        if (b.getX() == enteredX && b.getY() == enteredY
                                && b.getState() instanceof org.bukkit.inventory.InventoryHolder)
                            filteredZ.add(b.getZ());
                    }
                }
            }
            return filteredZ.stream().map(String::valueOf).filter(s -> s.startsWith(partial)).limit(20).toList();
        }
        return List.of();
    }

    private List<String> completeConfig(String[] args) {
        String sub = args[1].toLowerCase();
        String partial = args.length >= 3 ? args[args.length - 1].toLowerCase() : "";

        // config <reload|list|rules|monitor|scan> — level 2 is handled by CONFIG_SUB
        if (args.length == 2) return filter(CONFIG_SUB, partial);

        // Level 3+: route by sub
        if (args.length == 3) {
            return switch (sub) {
                case "rules"   -> filter(CONFIG_RULES, partial);
                case "monitor" -> filter(CONFIG_MONITOR_SUB, partial);
                case "scan"    -> filter(CONFIG_SCAN_SUB, partial);
                default -> List.of();
            };
        }

        // Level 4+: depends on rules/monitor/scan sub
        String subSub = args[2].toLowerCase();
        String partial4 = args.length >= 4 ? args[args.length - 1].toLowerCase() : "";

        if (sub.equals("rules")) {
            if (args.length == 4) {
                return switch (subSub) {
                    case "enchant"     -> filter(CONFIG_ENCHANT_SUB, partial4);
                    case "potion"      -> filter(CONFIG_POTION_SUB, partial4);
                    case "stack"       -> filter(CONFIG_STACK_SUB, partial4);
                    case "attribute"   -> filter(CONFIG_ATTRIBUTE_SUB, partial4);
                    case "unbreakable" -> filter(CONFIG_UNBREAKABLE_SUB, partial4);
                    default -> List.of();
                };
            }

            // Level 5+: route rules → enchant/potion/stack/attribute/unbreakable
            if (args.length == 5) {
                String enchantSub = args[3].toLowerCase();
                return switch (subSub) {
                    case "enchant" -> {
                        if (enchantSub.equals("level") || enchantSub.equals("compatibility"))
                            yield filter(TOGGLE_ACTIONS, partial4);
                        yield List.of();
                    }
                    case "potion" -> {
                        if (enchantSub.equals("level"))
                            yield filter(CONFIG_POTION_LEVEL_SUB, partial4);
                        yield List.of();
                    }
                    case "stack" -> {
                        if (enchantSub.equals("auto_fix"))
                            yield filter(List.of("enable", "disable"), partial4);
                        yield List.of();
                    }
                    case "attribute" -> {
                        if (enchantSub.equals("mode"))
                            yield filter(List.of("all", "threshold"), partial4);
                        yield List.of();
                    }
                    case "unbreakable" -> {
                        if (enchantSub.equals("action"))
                            yield filter(List.of("remove", "flag"), partial4);
                        if (enchantSub.equals("restore"))
                            yield filter(List.of("enable", "disable"), partial4);
                        yield List.of();
                    }
                    default -> List.of();
                };
            }

            // Level 6+: deeper arg suggestions (effects max, set/reset values, etc.)
            if (args.length >= 6) {
                String enchantSub = args[3].toLowerCase();
                if (subSub.equals("enchant")) {
                    if (enchantSub.equals("level")) {
                        if (args[4].equalsIgnoreCase("set") && args.length == 6)
                            return enchantNames(partial4);
                        if (args[4].equalsIgnoreCase("reset") && args.length == 6)
                            return enchantNames(partial4);
                    }
                    if (enchantSub.equals("compatibility")) {
                        if ((args[4].equalsIgnoreCase("add") || args[4].equalsIgnoreCase("remove")) && args.length == 6)
                            return enchantNames(partial4);
                        if ((args[4].equalsIgnoreCase("add") || args[4].equalsIgnoreCase("remove")) && args.length == 7)
                            return materialNames(partial4);
                    }
                }
                if (subSub.equals("potion") && enchantSub.equals("level")) {
                    if (args[4].equalsIgnoreCase("set") && args.length == 6)
                        return potionEffectNames(partial4);
                    if (args[4].equalsIgnoreCase("reset") && args.length == 6)
                        return potionEffectNames(partial4);
                }
                if (subSub.equals("stack")) {
                    if (args[4].equalsIgnoreCase("set") && args.length == 6)
                        return materialNames(partial4);
                    if (args[4].equalsIgnoreCase("reset") && args.length == 6)
                        return materialNames(partial4);
                }
                if (subSub.equals("attribute")) {
                    if (args[4].equalsIgnoreCase("set") && args.length == 6)
                        return attributeNames(partial4);
                    if (args[4].equalsIgnoreCase("reset") && args.length == 6)
                        return attributeNames(partial4);
                }
                return List.of();
            }
            return List.of();
        }

        if (sub.equals("monitor")) {
            if (args.length == 4 && subSub.equals("events"))
                return filter(CONFIG_MONITOR_EVENTS_SUB, partial4);
            if (args.length == 5 && subSub.equals("events")) {
                String eventAction = args[3].toLowerCase();
                if (eventAction.equals("enable") || eventAction.equals("disable"))
                    return monitorEventNames(partial4);
            }
            return List.of();
        }

        if (sub.equals("scan")) {
            if (args.length == 4 && subSub.equals("console_only"))
                return filter(List.of("enable", "disable"), partial4);
            return List.of();
        }

        return List.of();
    }

    private List<String> filter(List<String> source, String partial) {
        return source.stream().filter(s -> s.startsWith(partial)).toList();
    }

    private List<String> completeWatchlist(String[] args, String partial) {
        // args[1] is the action (add/remove/list/clear), args[2] is the material being typed
        String action = args.length >= 2 ? args[1].toLowerCase() : "";
        if (args.length == 3 && (action.equals("add") || action.equals("remove"))) {
            return materialNames(partial);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames(String partial) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(partial)).toList();
    }

    private List<String> worldNames(String partial) {
        return Bukkit.getWorlds().stream()
                .map(w -> w.getName()).filter(n -> n.toLowerCase().startsWith(partial)).toList();
    }

    /**
     * Tab-complete Material names (items only), limited to 50 matches.
     */
    private List<String> materialNames(String partial) {
        String upper = partial.toUpperCase();
        return java.util.Arrays.stream(org.bukkit.Material.values())
                .filter(m -> m.isItem() && m.name().startsWith(upper))
                .limit(50)
                .map(org.bukkit.Material::name)
                .toList();
    }

    /** Tab-complete Enchantment names, limited to 50 matches. */
    private List<String> enchantNames(String partial) {
        String upper = partial.toUpperCase();
        return Arrays.stream(org.bukkit.enchantments.Enchantment.values())
                .map(e -> e.getKey().getKey())
                .filter(k -> k.toUpperCase().startsWith(upper))
                .limit(50)
                .toList();
    }

    /** Tab-complete PotionEffectType names, limited to 50 matches. */
    private List<String> potionEffectNames(String partial) {
        String upper = partial.toUpperCase();
        return Arrays.stream(org.bukkit.potion.PotionEffectType.values())
                .map(org.bukkit.potion.PotionEffectType::getName)
                .filter(n -> n != null && n.toUpperCase().startsWith(upper))
                .limit(50)
                .toList();
    }

    /** Tab-complete Attribute names, limited to 50 matches. */
    private List<String> attributeNames(String partial) {
        String upper = partial.toUpperCase();
        return Arrays.stream(org.bukkit.attribute.Attribute.values())
                .map(org.bukkit.attribute.Attribute::name)
                .filter(n -> n.startsWith(upper))
                .limit(50)
                .toList();
    }

    /** Tab-complete MonitorEventType names. */
    private List<String> monitorEventNames(String partial) {
        String upper = partial.toUpperCase();
        return Arrays.stream(MonitorEventType.values())
                .map(MonitorEventType::name)
                .filter(n -> n.startsWith(upper))
                .toList();
    }
}
