package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

import java.util.Set;

/**
 * Handles /is watchlist <add|remove|list|clear> commands.
 * Manages the watch list of material IDs that are always flagged during scanning.
 */
public class WatchListCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public WatchListCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§e/is watchlist <add|remove|list|clear>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "add"    -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list"   -> handleList(sender);
            case "clear"  -> handleClear(sender);
            default -> {
                sender.sendMessage("§e未知操作: " + args[0] + "。可用: add|remove|list|clear");
                yield true;
            }
        };
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is watchlist add <物品ID>");
            return true;
        }
        String materialName = args[1].toUpperCase();
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) {
            sender.sendMessage("§c无效的物品ID: " + materialName);
            return true;
        }
        if (!mat.isItem()) {
            sender.sendMessage("§c" + materialName + " 不是有效的物品。");
            return true;
        }
        boolean added = plugin.getConfigManager().addWatchMaterial(mat.name());
        if (added) {
            sender.sendMessage("§a已将 " + mat.name() + " 加入关注列表。");
        } else {
            sender.sendMessage("§e" + mat.name() + " 已在关注列表中。");
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /is watchlist remove <物品ID>");
            return true;
        }
        String materialName = args[1].toUpperCase();
        boolean removed = plugin.getConfigManager().removeWatchMaterial(materialName);
        if (removed) {
            sender.sendMessage("§a已从关注列表移除 " + materialName + "。");
        } else {
            sender.sendMessage("§c" + materialName + " 不在关注列表中。");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        Set<Material> watched = plugin.getConfigManager().getWatchMaterials();
        if (watched.isEmpty()) {
            sender.sendMessage("§a关注列表为空。");
        } else {
            sender.sendMessage("§6===== 关注列表 (" + watched.size() + " 个) =====");
            for (Material mat : watched) {
                sender.sendMessage("§f- " + mat.name());
            }
        }
        return true;
    }

    private boolean handleClear(CommandSender sender) {
        int count = plugin.getConfigManager().clearWatchMaterials();
        sender.sendMessage("§a已清空关注列表 (" + count + " 个)。");
        return true;
    }

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) {
            return true;
        }
        return sender.hasPermission("illegalscanner.admin");
    }
}
