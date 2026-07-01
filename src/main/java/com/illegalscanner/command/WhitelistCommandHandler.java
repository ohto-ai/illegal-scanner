package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles /is whitelist <player|item|chunk|area|res|world>.
 * Phase 6 will complete full whitelist CRUD.
 */
public class WhitelistCommandHandler implements SubCommandHandler {

    private final IllegalScanner plugin;

    public WhitelistCommandHandler(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length < 1) {
            sender.sendMessage("§e/is whitelist <player|item|chunk|area|res|world>");
            sender.sendMessage("§e每个类型后加 <add|remove|list|clear>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "player" -> handlePlayer(sender, shift(args));
            case "item"   -> handleItem(sender, shift(args));
            case "chunk"  -> handleChunk(sender, shift(args));
            case "area"   -> handleArea(sender, shift(args));
            case "res"    -> handleRes(sender, shift(args));
            case "world"  -> handleWorld(sender, shift(args));
            default -> { sender.sendMessage("§e未知类型: " + args[0]); yield true; }
        };
    }

    // --- Player ---

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist player <add|remove|list|clear>"); return true; }
        return switch (args[0].toLowerCase()) {
            case "add" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist player add <玩家名>"); yield true; }
                String name = args[1];
                var target = plugin.getServer().getOfflinePlayer(name);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage("§c未找到玩家: " + name); yield true;
                }
                plugin.getPlayerWhitelistManager().addEntry(target.getUniqueId(), target.getName() != null ? target.getName() : name);
                sender.sendMessage("§a已将玩家加入白名单: " + name);
                yield true;
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist player remove <玩家名>"); yield true; }
                var target = plugin.getServer().getOfflinePlayer(args[1]);
                plugin.getPlayerWhitelistManager().removeEntry(target.getUniqueId());
                sender.sendMessage("§a已从白名单移除: " + args[1]);
                yield true;
            }
            case "list" -> {
                var entries = plugin.getPlayerWhitelistManager().listVisibleEntries();
                if (entries.isEmpty()) sender.sendMessage("§a玩家白名单为空。");
                else {
                    sender.sendMessage("§6===== 玩家白名单 (" + entries.size() + ") =====");
                    for (var e : entries) sender.sendMessage("§f" + e.playerName() + " §8(" + e.playerUuid() + ")");
                }
                yield true;
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearPlayerWhitelist().thenAccept(count -> {
                    plugin.getPlayerWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空玩家白名单 (" + count + " 条)。");
                });
                yield true;
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); yield true; }
        };
    }

    // --- Item ---

    private boolean handleItem(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist item <add|gui|remove|list|clear|import>"); return true; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayer only."); return true; }
                var held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) { sender.sendMessage("§c请手持一个物品。"); return true; }
                var mgr = plugin.getItemWhitelistManager();
                String mat = held.getType().name();
                String itemHash = plugin.getItemHashService().resolve(held);
                String eJson = mgr.buildEnchantmentsJson(held);
                String aJson = mgr.buildAttributesJson(held);
                int id = mgr.addEntrySync(mat, itemHash, null, null, eJson, aJson);
                if (id >= 0) sender.sendMessage("§a已添加物品白名单 (#" + id + "): " + mat
                        + " §8[" + itemHash.substring(0, 8) + "]");
                else sender.sendMessage("§c添加失败。");
            }
            case "gui" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayer only."); return true; }
                int pg = args.length >= 2 ? Math.max(1, Integer.parseInt(args[1])) : 1;
                openWhitelistGui(player, pg);
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist item remove <id>"); return true; }
                try {
                    int id = Integer.parseInt(args[1]);
                    plugin.getItemWhitelistManager().removeEntry(id);
                    sender.sendMessage("§a已移除物品白名单 #" + id);
                } catch (NumberFormatException e) { sender.sendMessage("§c无效ID。"); }
            }
            case "list" -> {
                var entries = plugin.getItemWhitelistManager().listEntries();
                if (entries.isEmpty()) sender.sendMessage("§a物品白名单为空。");
                else {
                    sender.sendMessage("§6===== 物品白名单 (" + entries.size() + ") =====");
                    for (var e : entries)
                        sender.sendMessage("§f#" + e.id() + " §7" + e.material()
                                + (e.customNamePattern() != null ? " 名称:" + e.customNamePattern() : ""));
                }
            }
            case "import" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayer only."); return true; }
                return handleItemImport(player, shift(args));
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearItemWhitelist().thenAccept(count -> {
                    plugin.getItemWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空物品白名单 (" + count + " 条)。");
                });
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); }
        }
        return true;
    }

    // --- Item Import ---

    /**
     * /is whitelist item import from container &lt;x&gt; &lt;y&gt; &lt;z&gt;
     * Bulk-imports every item in the target container into the item whitelist.
     */
    private boolean handleItemImport(Player player, String[] args) {
        if (args.length < 5 || !"from".equalsIgnoreCase(args[0]) || !"container".equalsIgnoreCase(args[1])) {
            player.sendMessage("§e用法: /is whitelist item import from container <x> <y> <z>");
            return true;
        }
        try {
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            var world = player.getWorld();
            var block = world.getBlockAt(x, y, z);

            if (!(block.getState() instanceof org.bukkit.inventory.InventoryHolder holder)) {
                player.sendMessage("§c目标方块不是容器 (箱子/木桶/潜影盒 等)。");
                return true;
            }

            var inv = holder.getInventory();
            var contents = inv.getContents();
            var mgr = plugin.getItemWhitelistManager();
            var db = plugin.getDatabaseManager();

            // Track hashes we're inserting to avoid adding duplicates within the same batch
            var seenHashes = new java.util.HashSet<String>();
            int added = 0, skipped = 0;
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();

            for (var stack : contents) {
                if (stack == null || stack.getType().isAir()) continue;

                String mat = stack.getType().name();
                String itemHash = plugin.getItemHashService().resolve(stack);

                // Skip if already in whitelist or already added in this batch
                if (itemHash != null && (mgr.isWhitelisted(stack) || !seenHashes.add(itemHash))) {
                    skipped++;
                    continue;
                }

                // Also skip if material-only match (hash is null but material already whitelisted generically)
                if (itemHash == null && mgr.isWhitelisted(stack)) {
                    skipped++;
                    continue;
                }

                String eJson = mgr.buildEnchantmentsJson(stack);
                String aJson = mgr.buildAttributesJson(stack);

                var entry = new com.illegalscanner.database.DatabaseManager.ItemWhitelistEntry(
                        -1, mat, itemHash, null, null,
                        eJson, aJson, System.currentTimeMillis());

                futures.add(db.addItemWhitelistEntry(entry));
                added++;
            }

            if (added == 0 && skipped == 0) {
                player.sendMessage("§e容器为空。");
                return true;
            }

            // Wait for all inserts, then reload cache once
            final int finalAdded = added;
            final int finalSkipped = skipped;
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .thenRun(() -> {
                        mgr.loadCache();
                        player.sendMessage("§a已从容器导入 §f" + finalAdded + " §a个物品"
                                + (finalSkipped > 0 ? "，跳过 §f" + finalSkipped + " §a个已存在" : ""));
                    });

        } catch (NumberFormatException e) {
            player.sendMessage("§c坐标必须是整数。");
        }
        return true;
    }

    // --- Chunk ---

    private boolean handleChunk(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist chunk <add|remove|list|clear>"); return true; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayer only."); return true; }
                var loc = p.getLocation();
                int id = plugin.getRegionWhitelistManager().addChunkEntrySync(
                        loc.getWorld().getName(), loc.getChunk().getX(), loc.getChunk().getZ());
                if (id >= 0) sender.sendMessage("§a已添加区块白名单 (#" + id + ")");
                else sender.sendMessage("§c添加失败。");
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist chunk remove <id>"); return true; }
                try { plugin.getRegionWhitelistManager().removeAreaEntry(Integer.parseInt(args[1])); sender.sendMessage("§a已移除 #" + args[1]); }
                catch (NumberFormatException e) { sender.sendMessage("§c无效ID。"); }
            }
            case "list" -> {
                var entries = plugin.getRegionWhitelistManager().listAreaEntries().stream()
                        .filter(e -> "CHUNK".equals(e.areaType())).toList();
                if (entries.isEmpty()) sender.sendMessage("§a区块白名单为空。");
                else {
                    sender.sendMessage("§6===== 区块白名单 (" + entries.size() + ") =====");
                    for (var e : entries) sender.sendMessage("§f#" + e.id() + " §7" + e.world() + " 区块(" + (e.minX()/16) + "," + (e.minZ()/16) + ")");
                }
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearAreaWhitelistByType("CHUNK").thenAccept(count -> {
                    plugin.getRegionWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空区块白名单 (" + count + " 条)。");
                });
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); }
        }
        return true;
    }

    // --- Area ---

    private boolean handleArea(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist area <add|remove|list|clear>"); return true; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (args.length < 5) { sender.sendMessage("§e用法: /is whitelist area add <x1> <z1> <x2> <z2> [world]"); return true; }
                try {
                    int x1 = Integer.parseInt(args[1]), z1 = Integer.parseInt(args[2]);
                    int x2 = Integer.parseInt(args[3]), z2 = Integer.parseInt(args[4]);
                    String world = args.length >= 6 ? args[5] : (sender instanceof Player p ? p.getWorld().getName() : "world");
                    int id = plugin.getRegionWhitelistManager().addAreaEntrySync(world, x1, z1, x2, z2);
                    if (id >= 0) sender.sendMessage("§a已添加区域白名单 (#" + id + ")");
                    else sender.sendMessage("§c添加失败。");
                } catch (NumberFormatException e) { sender.sendMessage("§c坐标必须是整数。"); }
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist area remove <id>"); return true; }
                try { plugin.getRegionWhitelistManager().removeAreaEntry(Integer.parseInt(args[1])); sender.sendMessage("§a已移除 #" + args[1]); }
                catch (NumberFormatException e) { sender.sendMessage("§c无效ID。"); }
            }
            case "list" -> {
                var entries = plugin.getRegionWhitelistManager().listAreaEntries().stream()
                        .filter(e -> "AREA".equals(e.areaType())).toList();
                if (entries.isEmpty()) sender.sendMessage("§a区域白名单为空。");
                else {
                    sender.sendMessage("§6===== 区域白名单 (" + entries.size() + ") =====");
                    for (var e : entries) sender.sendMessage("§f#" + e.id() + " §7" + e.world() + " [" + e.minX() + "," + e.minZ() + " ~ " + e.maxX() + "," + e.maxZ() + "]");
                }
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearAreaWhitelistByType("AREA").thenAccept(count -> {
                    plugin.getRegionWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空区域白名单 (" + count + " 条)。");
                });
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); }
        }
        return true;
    }

    // --- Res (Region) ---

    private boolean handleRes(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist res <add|remove|list|clear>"); return true; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (args.length < 4) { sender.sendMessage("§e用法: /is whitelist res add <插件名> <区域名> [世界]"); return true; }
                String pluginName = args[1], region = args[2];
                String world = args.length >= 4 ? args[3] : null;
                int id = this.plugin.getRegionWhitelistManager().addRegionEntrySync(pluginName, region, world);
                if (id >= 0) sender.sendMessage("§a已添加领地白名单 (#" + id + "): " + pluginName + "/" + region);
                else sender.sendMessage("§c添加失败。");
            }
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§e用法: /is whitelist res remove <id>"); return true; }
                try { plugin.getRegionWhitelistManager().removeRegionEntry(Integer.parseInt(args[1])); sender.sendMessage("§a已移除 #" + args[1]); }
                catch (NumberFormatException e) { sender.sendMessage("§c无效ID。"); }
            }
            case "list" -> {
                var entries = plugin.getRegionWhitelistManager().listRegionEntries();
                if (entries.isEmpty()) sender.sendMessage("§a领地白名单为空。");
                else {
                    sender.sendMessage("§6===== 领地白名单 (" + entries.size() + ") =====");
                    for (var e : entries) sender.sendMessage("§f#" + e.id() + " §7" + e.pluginName() + "/" + e.regionName() + (e.worldName() != null ? " 世界:" + e.worldName() : ""));
                }
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearRegionWhitelist().thenAccept(count -> {
                    plugin.getRegionWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空领地区域白名单 (" + count + " 条)。");
                });
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); }
        }
        return true;
    }

    // --- World ---

    private boolean handleWorld(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§e/is whitelist world <add|remove|list|clear>"); return true; }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                String world = args.length >= 2 ? args[1] : (sender instanceof Player p ? p.getWorld().getName() : null);
                if (world == null) { sender.sendMessage("§e用法: /is whitelist world add <世界名>"); return true; }
                // Verify world exists
                if (Bukkit.getWorld(world) == null) {
                    sender.sendMessage("§c未找到世界: " + world + " (世界名区分大小写)");
                    return true;
                }
                int id = plugin.getRegionWhitelistManager().addWorldEntrySync(world);
                if (id >= 0) sender.sendMessage("§a已将世界加入白名单 (#" + id + "): " + world);
                else sender.sendMessage("§c添加失败（可能已存在）。");
            }
            case "remove" -> {
                String world = args.length >= 2 ? args[1] : (sender instanceof Player p ? p.getWorld().getName() : null);
                if (world == null) { sender.sendMessage("§e用法: /is whitelist world remove <世界名>"); return true; }
                plugin.getRegionWhitelistManager().removeWorldEntry(world);
                sender.sendMessage("§a已从白名单移除世界: " + world);
            }
            case "list" -> {
                var entries = plugin.getRegionWhitelistManager().listWorldEntries();
                if (entries.isEmpty()) sender.sendMessage("§a世界白名单为空。");
                else {
                    sender.sendMessage("§6===== 世界白名单 (" + entries.size() + ") =====");
                    for (var e : entries) sender.sendMessage("§f#" + e.id() + " §7" + e.worldName());
                }
            }
            case "clear" -> {
                var db = plugin.getDatabaseManager();
                db.clearWorldWhitelist().thenAccept(count -> {
                    plugin.getRegionWhitelistManager().loadCache();
                    sender.sendMessage("§a已清空世界白名单 (" + count + " 条)。");
                });
            }
            default -> { sender.sendMessage("§e未知操作: " + args[0]); }
        }
        return true;
    }

    // --- Helpers ---

    private boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null
                && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.admin");
    }

    // ==================== Whitelist GUI ====================

    public void openWhitelistGui(Player player, int page) {
        var mgr = plugin.getItemWhitelistManager();
        var entries = mgr.listEntries();
        int total = entries.size();
        int totalPages = Math.max(1, (total + 45 - 1) / 45);
        if (page > totalPages) page = totalPages;

        String title = ("§8白名单 " + page + "/" + totalPages).substring(0, Math.min(32, ("§8白名单 " + page + "/" + totalPages).length()));
        var holder = new WhitelistGuiHolder(page, totalPages);
        var gui = org.bukkit.Bukkit.createInventory(holder, 54, title);

        int start = (page - 1) * 45;
        int end = Math.min(start + 45, total);
        for (int i = start; i < end; i++) {
            var e = entries.get(i);
            org.bukkit.inventory.ItemStack display;
            // Use actual snapshot if hash-based entry
            if (e.itemHash() != null && !e.itemHash().isEmpty()) {
                var snap = plugin.getDatabaseManager().getItemByHash(e.itemHash());
                if (snap != null) {
                    display = com.illegalscanner.scanner.NbtUtil.itemStackFromJson(snap.itemSnapshot());
                } else {
                    display = new org.bukkit.inventory.ItemStack(org.bukkit.Material.getMaterial(e.material()) != null
                            ? org.bukkit.Material.getMaterial(e.material()) : org.bukkit.Material.BARRIER);
                }
            } else {
                var mat = org.bukkit.Material.getMaterial(e.material());
                display = new org.bukkit.inventory.ItemStack(mat != null ? mat : org.bukkit.Material.BARRIER);
            }
            if (display == null || display.getType().isAir()) {
                display = new org.bukkit.inventory.ItemStack(org.bukkit.Material.getMaterial(e.material()) != null
                        ? org.bukkit.Material.getMaterial(e.material()) : org.bukkit.Material.BARRIER);
            }
            var meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§f#" + e.id() + " §e" + e.material());
                var lore = new java.util.ArrayList<String>();
                if (e.itemHash() != null) lore.add("§7Hash: §f" + e.itemHash().substring(0, 12) + "...");
                if (e.customNamePattern() != null) lore.add("§7名称: §f" + e.customNamePattern());
                if (e.enchantmentsJson() != null) lore.add("§7附魔: §f" + e.enchantmentsJson());
                lore.add("§c▶ 右键移除  §a▶ Shift+左键获取");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            holder.entryIds.put(i - start, e.id());
            gui.setItem(i - start, display);
        }

        // Navigation
        var border = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BLACK_STAINED_GLASS_PANE);
        var bm = border.getItemMeta(); if (bm != null) { bm.setDisplayName("§7"); border.setItemMeta(bm); }
        for (int i = 45; i <= 53; i++) gui.setItem(i, border.clone());

        if (page > 1) {
            var prev = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW);
            var pm = prev.getItemMeta(); if (pm != null) { pm.setDisplayName("§a◀ 上一页"); prev.setItemMeta(pm); }
            gui.setItem(45, prev);
        }
        if (page < totalPages) {
            var next = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW);
            var nm = next.getItemMeta(); if (nm != null) { nm.setDisplayName("§a下一页 ▶"); next.setItemMeta(nm); }
            gui.setItem(53, next);
        }
        var info = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName("§e白名单 §8[第" + page + "/" + totalPages + "页]");
            im.setLore(java.util.List.of("§7共 §f" + total + " §7条", "", "§c右键条目 → 移除", "§aShift+左键条目 → 获取副本", "§aShift+左键背包物品 → 添加"));
            info.setItemMeta(im);
        }
        gui.setItem(49, info);

        player.openInventory(gui);
    }

    public static class WhitelistGuiHolder implements org.bukkit.inventory.InventoryHolder {
        public final int page, totalPages;
        public final java.util.Map<Integer, Integer> entryIds = new java.util.HashMap<>();
        public WhitelistGuiHolder(int page, int totalPages) { this.page = page; this.totalPages = totalPages; }
        @Override public org.bukkit.inventory.Inventory getInventory() { return null; }
    }

    private static String[] shift(String[] args) {
        if (args.length <= 1) return new String[0];
        String[] s = new String[args.length - 1];
        System.arraycopy(args, 1, s, 0, s.length);
        return s;
    }
}
