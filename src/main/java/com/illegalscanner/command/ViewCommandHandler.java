package com.illegalscanner.command;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import com.illegalscanner.database.DatabaseManager.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handles /is view. Views render actual item snapshots from item_index,
 * support back navigation, clean copy, TP, and drill-down.
 *
 * View hierarchy:
 *   world/area → chunk list → chunk (deduped items) → chunk_item (all records with TP)
 *   scan list → scan session → [chunk summary | item summary] → ...
 */
public class ViewCommandHandler implements SubCommandHandler {

    final IllegalScanner plugin;
    static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static final int GUI_SIZE = 54;
    public static final int PAGE_SIZE = 45;

    public ViewCommandHandler(IllegalScanner plugin) { this.plugin = plugin; }

    @Override
    public boolean handle(CommandSender sender, String[] args) {
        if (!hasAccess(sender)) { sender.sendMessage("§cNo permission."); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayer only."); return true; }
        if (args.length < 1) { sendUsage(p); return true; }
        return switch (args[0].toLowerCase()) {
            case "chunk"  -> { openChunkView(p, p.getWorld().getName(), p.getLocation().getChunk().getX(), p.getLocation().getChunk().getZ(), 1, null); yield true; }
            case "player" -> { if(args.length<2){p.sendMessage("§e/is view player <name>");yield true;} openPlayerView(p,args[1],1,null); yield true; }
            case "world"  -> { String w=args.length>=2?args[1]:p.getWorld().getName(); openWorldView(p,w,1,null); yield true; }
            case "area"   -> { if(args.length<5){p.sendMessage("§e/is view area <x1> <z1> <x2> <z2> [world]");yield true;}
                                String w2=args.length>=6?args[5]:p.getWorld().getName();
                                openAreaView(p,w2,Integer.parseInt(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),1,null); yield true; }
            case "scan"   -> { if(args.length<2)openScanListView(p,1,null); else openScanView(p,Integer.parseInt(args[1]),1,null); yield true; }
            case "record" -> { if(args.length<3){p.sendMessage("§e/is view record <SCAN|MONITOR> <id>");yield true;} openRecordView(p,args[1],Long.parseLong(args[2]),null); yield true; }
            case "item"   -> { if(args.length<2)openItemListView(p,1,null); else openItemDetailView(p,args[1],parsePage(args,2),null); yield true; }
            default -> { sendUsage(p); yield true; }
        };
    }

    private void sendUsage(Player p) {
        p.sendMessage("§e/is view <chunk|player|area|world|scan|record|item>");
    }

    // ==================== Chunk View (latest scan + subsequent monitor) ====================

    public void openChunkView(Player p, String world, int cx, int cz, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        // Get all records, find latest scan and subsequent monitors
        List<UnifiedRecord> all = db.getRecordsByChunk(world, cx, cz, 1, Integer.MAX_VALUE);

        // Find the latest SCAN record for this chunk
        final long latestScanTime;
        long maxScan = 0;
        for (UnifiedRecord r : all) {
            if ("SCAN".equals(r.source()) && r.scanTime() > maxScan) maxScan = r.scanTime();
        }
        latestScanTime = maxScan;

        // Keep only: records from the latest scan + monitor records after that scan
        List<UnifiedRecord> relevant = new ArrayList<>();
        if (latestScanTime > 0) {
            for (UnifiedRecord r : all) {
                if (("SCAN".equals(r.source()) && r.scanTime() >= latestScanTime - 1000)
                        || ("MONITOR".equals(r.source()) && r.scanTime() >= latestScanTime)) {
                    relevant.add(r);
                }
            }
        } else {
            relevant = all.stream().filter(r -> "MONITOR".equals(r.source())).toList();
        }

        // Check if the latest scan found ANY violations. If not, chunk is clean.
        boolean latestScanClean = latestScanTime > 0 && all.stream()
                .noneMatch(r -> "SCAN".equals(r.source()) && r.scanTime() >= latestScanTime - 1000);
        if (latestScanClean && relevant.stream().noneMatch(r -> "MONITOR".equals(r.source()))) {
            relevant = List.of();
        }

        // Dedup by item_hash, keep latest
        Map<String, UnifiedRecord> deduped = new LinkedHashMap<>();
        for (UnifiedRecord r : relevant) {
            String k = r.itemHash();
            if (!deduped.containsKey(k) || r.scanTime() > deduped.get(k).scanTime()) deduped.put(k, r);
        }
        List<UnifiedRecord> records = new ArrayList<>(deduped.values());
        records.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));

        int total = records.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        List<UnifiedRecord> pageItems = records.subList(start, end);

        String title = buildTitle("§8区块(" + cx + "," + cz + ")", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.CHUNK, world + "/" + cx + "/" + cz, page, totalPages, back);
        holder.extraData = new int[]{cx, cz}; // for TP

        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (UnifiedRecord r : pageItems) {
            ItemStack clean = getCleanSnapshot(r.itemHash());
            if (clean == null) clean = new ItemStack(Material.BARRIER);
            holder.cleanItems.put(slot, clean.clone());
            holder.slotRecords.put(slot, r); // needed for drill-down
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add("§8[" + r.source() + " #" + r.id() + "] " + (r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑"));
                lore.add("§7" + DATE_FMT.format(new Date(r.scanTime())));
                if (r.playerName() != null) lore.add("§7玩家: §f" + r.playerName());
                if (r.container() != null) lore.add("§7" + r.container() + " 槽" + (r.itemSlot() != null ? r.itemSlot() : "?"));
                lore.add("§7▶ 点击展开所有记录");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot++, display);
        }
        addChunkNavigation(gui, page, totalPages, holder, world, cx, cz);
        p.openInventory(gui);
    }

    // ==================== Chunk Item Detail (all records, undeduped, with TP) ====================

    public void openChunkItemView(Player p, String world, int cx, int cz, String itemHash, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        List<UnifiedRecord> all = db.getRecordsByChunk(world, cx, cz, 1, Integer.MAX_VALUE);
        List<UnifiedRecord> filtered = all.stream()
                .filter(r -> r.itemHash().equals(itemHash))
                .sorted((a, b) -> Long.compare(b.scanTime(), a.scanTime())).toList();

        int total = filtered.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        List<UnifiedRecord> pageItems = filtered.subList(start, end);

        var entry = db.getItemByHash(itemHash);
        String title = buildTitle("§8" + (entry != null ? entry.itemType() : "?"), page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.CHUNK_ITEM, world + "/" + cx + "/" + cz + "/" + itemHash, page, totalPages, back);
        holder.extraData = new int[]{cx, cz};

        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (UnifiedRecord r : pageItems) {
            ItemStack clean = getCleanSnapshot(r.itemHash());
            if (clean == null) clean = new ItemStack(Material.BARRIER);
            holder.cleanItems.put(slot, clean.clone());
            holder.slotRecords.put(slot, r);
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add((r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑") + " §8[" + r.source() + " #" + r.id() + "]");
                lore.add("§7时间: §f" + DATE_FMT.format(new Date(r.scanTime())));
                if (r.playerName() != null) lore.add("§7玩家: §f" + r.playerName());
                if (r.container() != null) lore.add("§7容器: §f" + r.container() + " 槽" + (r.itemSlot() != null ? r.itemSlot() : "?"));
                if (r.containerLoc() != null) {
                    String[] parts = r.containerLoc().split(",");
                    if (parts.length >= 4) {
                        lore.add("§7坐标: §f" + parts[1] + ", " + parts[2] + ", " + parts[3]);
                        holder.slotLocations.put(slot, new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
                    }
                }
                lore.add("§7▶ 点击传送到此位置");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== Player View ====================

    public void openPlayerView(Player p, String name, int page, ViewGuiHolder back) {
        Player target = plugin.getServer().getPlayer(name);
        if (target == null) { p.sendMessage("§cPlayer not online: " + name); return; }
        var db = plugin.getDatabaseManager();
        List<UnifiedRecord> all = db.getRecordsByPlayer(target.getUniqueId().toString(), 1, Integer.MAX_VALUE);
        Map<String, UnifiedRecord> deduped = new LinkedHashMap<>();
        for (UnifiedRecord r : all) {
            String k = r.itemHash();
            if (!deduped.containsKey(k) || r.scanTime() > deduped.get(k).scanTime()) deduped.put(k, r);
        }
        List<UnifiedRecord> records = new ArrayList<>(deduped.values());
        records.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));
        int total = records.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        String title = buildTitle("§8" + target.getName(), page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.PLAYER, target.getUniqueId().toString(), page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        populateItems(gui, holder, records.subList(start, end), false);
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== World View ====================

    public void openWorldView(Player p, String worldName, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        Map<String, Long> chunkLatest = new LinkedHashMap<>();
        for (var chunk : p.getWorld().getLoadedChunks()) {
            int count = db.countRecordsByChunk(worldName, chunk.getX(), chunk.getZ());
            if (count > 0) {
                String key = chunk.getX() + "," + chunk.getZ();
                chunkCounts.put(key, count);
                var recs = db.getRecordsByChunk(worldName, chunk.getX(), chunk.getZ(), 1, 1);
                if (!recs.isEmpty()) chunkLatest.put(key, recs.get(0).scanTime());
            }
        }
        List<String> keys = new ArrayList<>(chunkCounts.keySet());
        keys.sort((a, b) -> Long.compare(chunkLatest.getOrDefault(b, 0L), chunkLatest.getOrDefault(a, 0L)));
        int total = keys.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        String title = buildTitle("§8世界:" + worldName, page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.WORLD, worldName, page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (int i = start; i < end; i++) {
            String[] xy = keys.get(i).split(",");
            int cx = Integer.parseInt(xy[0]), cz = Integer.parseInt(xy[1]);
            ItemStack display = new ItemStack(Material.MAP);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e区块 (" + cx + "," + cz + ")");
                meta.setLore(List.of("§7违规数: §c" + chunkCounts.get(keys.get(i)),
                        "§7坐标: §f" + (cx * 16) + "~" + (cx * 16 + 15) + ", " + (cz * 16) + "~" + (cz * 16 + 15),
                        "§7▶ 点击进入区块详情"));
                display.setItemMeta(meta);
            }
            holder.slotChunks.put(slot, new int[]{cx, cz});
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== Area View ====================

    public void openAreaView(Player p, String worldName, int x1, int z1, int x2, int z2, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        int minCX = Math.min(x1, x2) >> 4, maxCX = Math.max(x1, x2) >> 4;
        int minCZ = Math.min(z1, z2) >> 4, maxCZ = Math.max(z1, z2) >> 4;
        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                int c = db.countRecordsByChunk(worldName, cx, cz);
                if (c > 0) chunkCounts.put(cx + "," + cz, c);
            }
        List<String> keys = new ArrayList<>(chunkCounts.keySet());
        keys.sort(Comparator.comparingInt(k -> -chunkCounts.get(k)));
        int total = keys.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        String title = buildTitle("§8区域", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.AREA, worldName + "/" + x1 + "/" + z1 + "/" + x2 + "/" + z2, page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (int i = start; i < end; i++) {
            String[] xy = keys.get(i).split(",");
            int cx = Integer.parseInt(xy[0]), cz = Integer.parseInt(xy[1]);
            ItemStack display = new ItemStack(Material.MAP);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e区块 (" + cx + "," + cz + ")");
                meta.setLore(List.of("§7违规数: §c" + chunkCounts.get(keys.get(i)), "§7▶ 点击进入区块详情"));
                display.setItemMeta(meta);
            }
            holder.slotChunks.put(slot, new int[]{cx, cz});
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== Item Views ====================

    public void openItemListView(Player p, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        List<ItemIndexEntry> items = db.getDistinctViolationItems();
        int total = items.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        String title = buildTitle("§8违规物品类型", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.ITEM_LIST, "", page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        for (int i = start; i < end; i++) {
            ItemIndexEntry entry = items.get(i);
            ItemStack clean = getCleanSnapshot(entry.itemHash());
            if (clean == null || clean.getType().isAir())
                clean = new ItemStack(Material.getMaterial(entry.itemType()) != null ? Material.getMaterial(entry.itemType()) : Material.BARRIER);
            holder.cleanItems.put(i - start, clean.clone());
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
                lore.add("§7Hash: §f" + entry.itemHash().substring(0, 12) + "...");
                lore.add("§7违规: §c" + db.getItemRecordCount(entry.itemHash()) + " 条");
                lore.add("§7▶ 点击查看详情");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(i - start, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    /** Undeduped item detail — shows every record with coordinates + TP. */
    public void openItemDetailView(Player p, String hash, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        var allRecords = db.getRecordsByItem(hash, 1, Integer.MAX_VALUE);
        // Sort by time descending
        List<UnifiedRecord> sorted = allRecords.stream()
                .sorted((a, b) -> Long.compare(b.scanTime(), a.scanTime())).toList();

        int total = sorted.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        List<UnifiedRecord> pageItems = sorted.subList(start, end);

        var entry = db.getItemByHash(hash);
        String title = buildTitle("§8" + (entry != null ? entry.itemType() : "?"), page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.ITEM, hash, page, totalPages, back);

        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (UnifiedRecord r : pageItems) {
            ItemStack clean = getCleanSnapshot(r.itemHash());
            if (clean == null || clean.getType().isAir()) clean = new ItemStack(Material.BARRIER);
            holder.cleanItems.put(slot, clean.clone());
            holder.slotRecords.put(slot, r);
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add((r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑") + " §8[" + r.source() + " #" + r.id() + "]");
                lore.add("§7时间: §f" + DATE_FMT.format(new Date(r.scanTime())));
                if (r.playerName() != null) lore.add("§7玩家: §f" + r.playerName());
                lore.add("§7世界: §f" + r.world() + " §7区块: (" + r.chunkX() + "," + r.chunkZ() + ")");
                if (r.container() != null) lore.add("§7" + r.container() + " 槽" + (r.itemSlot() != null ? r.itemSlot() : "?"));
                if (r.containerLoc() != null) {
                    String[] parts = r.containerLoc().split(",");
                    if (parts.length >= 4) {
                        lore.add("§7坐标: §f" + parts[1] + ", " + parts[2] + ", " + parts[3]);
                        holder.slotLocations.put(slot, new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
                    }
                }
                lore.add("§7▶ 点击传送到此位置");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== Scan Session (two views) ====================

    public void openScanListView(Player p, int page, ViewGuiHolder back) {
        var sessions = plugin.getDatabaseManager().getScanSessions(page, PAGE_SIZE);
        int totalPages = Math.max(1, (sessions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        String title = buildTitle("§8扫描会话", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.SCAN_LIST, "", page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        for (int i = 0; i < Math.min(sessions.size(), PAGE_SIZE); i++) {
            var s = sessions.get(i);
            ItemStack display = new ItemStack(Material.BOOK);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e会话 #" + s.id() + " §7[" + s.scanType() + "]");
                meta.setLore(List.of("§7目标: §f" + s.target(), "§7状态: §f" + s.status(),
                        "§7扫描: §f" + s.itemsScanned() + " chunks", "§7违规: §c" + s.itemsFlagged(),
                        "§7时间: §f" + DATE_FMT.format(new Date(s.startedAt())), "§7▶ 点击查看详情"));
                display.setItemMeta(meta);
            }
            gui.setItem(i, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    /** Entry point for a scan session — shows progress, two summary options, and refresh. */
    public void openScanView(Player p, int sessionId, int page, ViewGuiHolder back) {
        var session = plugin.getDatabaseManager().getScanSession(sessionId);
        if (session == null) { p.sendMessage("§cSession not found."); return; }

        String title = buildTitleRaw("§8会话 #" + sessionId);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.SCAN, String.valueOf(sessionId), 1, 1, back);
        Inventory gui = Bukkit.createInventory(holder, 27, title);

        ItemStack border = borderPane();
        for (int i = 0; i < 27; i++) { if (i < 9 || i >= 18) gui.setItem(i, border.clone()); }

        // Chunk summary option
        ItemStack chunkOpt = new ItemStack(Material.MAP);
        ItemMeta cm = chunkOpt.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("§e▶ 区块汇总");
            cm.setLore(List.of("§7查看本次扫描各区块的违规情况", "§7点击进入"));
            chunkOpt.setItemMeta(cm);
        }
        gui.setItem(11, chunkOpt);

        // Item summary option
        ItemStack itemOpt = new ItemStack(Material.CHEST);
        ItemMeta im = itemOpt.getItemMeta();
        if (im != null) {
            im.setDisplayName("§e▶ 物品汇总");
            im.setLore(List.of("§7查看本次扫描的违规物品汇总(去重)", "§7点击进入"));
            itemOpt.setItemMeta(im);
        }
        gui.setItem(15, itemOpt);

        // Progress info
        String progressLabel = switch (session.scanType()) {
            case "chunk" -> "区块";
            case "player" -> "玩家";
            case "area", "world", "full" -> "区块";
            default -> "单位";
        };
        String progress = session.status().equals("COMPLETED")
                ? "§a已完成"
                : "§e" + session.itemsScanned() + " / " + (session.totalItems() > 0 ? session.totalItems() : "?") + " " + progressLabel;
        String eta = !session.status().equals("COMPLETED") && session.startedAt() > 0
                ? "\n§7已运行: §f" + ((System.currentTimeMillis() - session.startedAt()) / 1000) + "s"
                : "";

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta mi = info.getItemMeta();
        if (mi != null) {
            mi.setDisplayName("§6会话 #" + sessionId + " §7[" + session.scanType() + "]");
            mi.setLore(List.of("§7目标: §f" + session.target(),
                    "§7进度: " + progress + eta,
                    "§7违规: §c" + session.itemsFlagged(),
                    "§7状态: §f" + session.status(),
                    "§7时间: §f" + DATE_FMT.format(new Date(session.startedAt()))));
            info.setItemMeta(mi);
        }
        gui.setItem(13, info);

        // Refresh button (slot 16)
        if (!session.status().equals("COMPLETED")) {
            ItemStack refresh = new ItemStack(Material.CLOCK);
            ItemMeta rm = refresh.getItemMeta();
            if (rm != null) { rm.setDisplayName("§e⟳ 刷新进度"); refresh.setItemMeta(rm); }
            gui.setItem(16, refresh);
        }

        p.openInventory(gui);
    }

    /** Chunk summary: show chunks in this scan session (like world view). */
    public void openScanChunksView(Player p, int sessionId, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        var allRecords = db.getRecordsBySession(sessionId, 1, Integer.MAX_VALUE);
        // Group by chunk
        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        for (UnifiedRecord r : allRecords) {
            String key = r.world() + "/" + r.chunkX() + "/" + r.chunkZ();
            chunkCounts.merge(key, 1, Integer::sum);
        }
        List<String> keys = new ArrayList<>(chunkCounts.keySet());
        keys.sort((a, b) -> chunkCounts.get(b) - chunkCounts.get(a));
        int total = keys.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);

        String title = buildTitle("§8会话#" + sessionId + " 区块", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.SCAN_CHUNKS, String.valueOf(sessionId), page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (int i = start; i < end; i++) {
            String[] parts = keys.get(i).split("/");
            String w = parts[0]; int cx = Integer.parseInt(parts[1]), cz = Integer.parseInt(parts[2]);
            ItemStack display = new ItemStack(Material.MAP);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + w + " 区块(" + cx + "," + cz + ")");
                meta.setLore(List.of("§7违规数: §c" + chunkCounts.get(keys.get(i)), "§7▶ 点击进入区块详情"));
                display.setItemMeta(meta);
            }
            holder.slotChunks.put(slot, new int[]{cx, cz});
            holder.slotWorld.put(slot, w);
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    /** Item summary: deduped items in this scan session (like chunk view). */
    public void openScanItemsView(Player p, int sessionId, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        var allRecords = db.getRecordsBySession(sessionId, 1, Integer.MAX_VALUE);
        Map<String, UnifiedRecord> deduped = new LinkedHashMap<>();
        for (UnifiedRecord r : allRecords) {
            String k = r.itemHash();
            if (!deduped.containsKey(k) || r.scanTime() > deduped.get(k).scanTime()) deduped.put(k, r);
        }
        List<UnifiedRecord> records = new ArrayList<>(deduped.values());
        records.sort((a, b) -> Long.compare(b.scanTime(), a.scanTime()));
        int total = records.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);

        String title = buildTitle("§8会话#" + sessionId + " 物品", page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.SCAN_ITEMS, String.valueOf(sessionId), page, totalPages, back);
        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        populateItems(gui, holder, records.subList(start, end), false);
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    /** Item detail within a scan session — undeduped records with coordinates + TP. */
    public void openScanItemDetailView(Player p, int sessionId, String itemHash, int page, ViewGuiHolder back) {
        var db = plugin.getDatabaseManager();
        var allRecords = db.getRecordsBySession(sessionId, 1, Integer.MAX_VALUE);
        List<UnifiedRecord> filtered = allRecords.stream()
                .filter(r -> r.itemHash().equals(itemHash))
                .sorted((a, b) -> Long.compare(b.scanTime(), a.scanTime())).toList();

        int total = filtered.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, total);
        List<UnifiedRecord> pageItems = filtered.subList(start, end);

        var entry = db.getItemByHash(itemHash);
        String title = buildTitle("§8" + (entry != null ? entry.itemType() : "?"), page, totalPages);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.SCAN_ITEM_DETAIL, sessionId + "/" + itemHash, page, totalPages, back);

        Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, title);
        int slot = 0;
        for (UnifiedRecord r : pageItems) {
            ItemStack clean = getCleanSnapshot(r.itemHash());
            if (clean == null) clean = new ItemStack(Material.BARRIER);
            holder.cleanItems.put(slot, clean.clone());
            holder.slotRecords.put(slot, r);
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add((r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑") + " §8[" + r.source() + " #" + r.id() + "]");
                lore.add("§7时间: §f" + DATE_FMT.format(new Date(r.scanTime())));
                if (r.playerName() != null) lore.add("§7玩家: §f" + r.playerName());
                if (r.container() != null) lore.add("§7" + r.container() + " 槽" + (r.itemSlot() != null ? r.itemSlot() : "?"));
                if (r.containerLoc() != null) {
                    String[] parts = r.containerLoc().split(",");
                    if (parts.length >= 4) {
                        lore.add("§7坐标: §f" + parts[1] + ", " + parts[2] + ", " + parts[3]);
                        holder.slotLocations.put(slot, new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
                    }
                }
                lore.add("§7▶ 点击传送到此位置");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot++, display);
        }
        addNavigation(gui, page, totalPages, holder);
        p.openInventory(gui);
    }

    // ==================== Record View ====================

    public void openRecordView(Player p, String source, long id, ViewGuiHolder back) {
        var record = plugin.getDatabaseManager().getRecordById(source, id);
        if (record == null) { p.sendMessage("§cRecord not found."); return; }
        var entry = plugin.getDatabaseManager().getItemByHash(record.itemHash());
        ItemStack item = entry != null ? getCleanSnapshot(record.itemHash()) : null;
        String title = buildTitleRaw("§8记录 " + source + " #" + id);
        ViewGuiHolder holder = new ViewGuiHolder(ViewType.RECORD, source + "/" + id, 1, 1, back);
        if (record.containerLoc() != null) {
            String[] parts = record.containerLoc().split(",");
            if (parts.length >= 4)
                holder.slotLocations.put(13, new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
        }
        Inventory gui = Bukkit.createInventory(holder, 27, title);
        ItemStack border = borderPane();
        for (int i = 0; i < 27; i++) { if (i < 9 || i >= 18) gui.setItem(i, border.clone()); }
        gui.setItem(9, border.clone()); gui.setItem(17, border.clone());
        if (item != null && !item.getType().isAir()) { holder.cleanItems.put(13, item.clone()); gui.setItem(13, item.clone()); }
        // Info — show full hash for /is give
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName("§e记录信息");
            im.setLore(List.of("§7来源: §f" + source + " / " + record.scanType(),
                    "§7类型: §f" + (entry != null ? entry.itemType() : "?"),
                    "§7严重级别: " + (record.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑"),
                    "§7世界: §f" + record.world() + " §7区块: (" + record.chunkX() + "," + record.chunkZ() + ")",
                    "§7玩家: §f" + (record.playerName() != null ? record.playerName() : "无"),
                    "§7时间: §f" + DATE_FMT.format(new Date(record.scanTime())),
                    "",
                    "§6▶ /is give §f" + record.itemHash(),
                    "§7Shift+左键中间物品可复制"));
            info.setItemMeta(im);
        }
        gui.setItem(11, info);
        // TP button if location available
        if (holder.slotLocations.containsKey(13)) {
            ItemStack tpBtn = new ItemStack(Material.ENDER_PEARL);
            ItemMeta tm = tpBtn.getItemMeta();
            if (tm != null) { tm.setDisplayName("§a✦ 传送到此位置"); tpBtn.setItemMeta(tm); }
            gui.setItem(15, tpBtn);
        }
        p.openInventory(gui);
    }

    // ==================== Rendering ====================

    private void populateItems(Inventory gui, ViewGuiHolder holder, List<UnifiedRecord> records, boolean showCoords) {
        int slot = 0;
        for (UnifiedRecord r : records) {
            if (slot >= PAGE_SIZE) break;
            ItemStack clean = getCleanSnapshot(r.itemHash());
            if (clean == null || clean.getType().isAir()) clean = new ItemStack(Material.BARRIER);
            holder.cleanItems.put(slot, clean.clone());
            holder.slotRecords.put(slot, r);
            ItemStack display = clean.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add((r.severity().equals("ILLEGAL") ? "§c非法" : "§e可疑") + " §8[" + r.source() + " #" + r.id() + "]");
                lore.add("§7时间: §f" + DATE_FMT.format(new Date(r.scanTime())));
                if (r.playerName() != null) lore.add("§7玩家: §f" + r.playerName());
                if (r.container() != null) lore.add("§7" + r.container() + " 槽" + (r.itemSlot() != null ? r.itemSlot() : "?"));
                if (showCoords && r.containerLoc() != null) {
                    String[] parts = r.containerLoc().split(",");
                    if (parts.length >= 4) lore.add("§7坐标: §f" + parts[1] + ", " + parts[2] + ", " + parts[3]);
                }
                lore.add("§7▶ 点击查看详情");
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot++, display);
        }
    }

    private ItemStack getCleanSnapshot(String itemHash) {
        var entry = plugin.getDatabaseManager().getItemByHash(itemHash);
        if (entry == null) return null;
        return com.illegalscanner.scanner.NbtUtil.itemStackFromJson(entry.itemSnapshot());
    }

    // ==================== Navigation ====================

    void addChunkNavigation(Inventory gui, int page, int totalPages, ViewGuiHolder holder,
                             String world, int cx, int cz) {
        addNavigation(gui, page, totalPages, holder);
        // TP button in slot 47
        ItemStack tp = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tm = tp.getItemMeta();
        if (tm != null) { tm.setDisplayName("§a✦ 传送到区块中心"); tp.setItemMeta(tm); }
        gui.setItem(47, tp);
        // Rescan button in slot 48
        ItemStack scan = new ItemStack(Material.COMPASS);
        ItemMeta sm = scan.getItemMeta();
        if (sm != null) { sm.setDisplayName("§e⟳ 重新扫描此区块"); sm.setLore(List.of("§7点击立即扫描并刷新结果")); scan.setItemMeta(sm); }
        gui.setItem(48, scan);
    }

    void addNavigation(Inventory gui, int page, int totalPages, ViewGuiHolder holder) {
        ItemStack border = borderPane();
        for (int i = 45; i <= 53; i++) gui.setItem(i, border.clone());
        if (holder.hasBack()) {
            ItemStack backBtn = new ItemStack(Material.BARRIER);
            ItemMeta bm = backBtn.getItemMeta();
            if (bm != null) { bm.setDisplayName("§c◀ 返回"); backBtn.setItemMeta(bm); }
            gui.setItem(46, backBtn);
        }
        if (page > 1) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pm = prev.getItemMeta();
            if (pm != null) { pm.setDisplayName("§a◀ 上一页 (" + (page - 1) + "/" + totalPages + ")"); prev.setItemMeta(pm); }
            gui.setItem(45, prev);
        }
        if (page < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nm = next.getItemMeta();
            if (nm != null) { nm.setDisplayName("§a下一页 (" + (page + 1) + "/" + totalPages + ") ▶"); next.setItemMeta(nm); }
            gui.setItem(53, next);
        }
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        if (im != null) { im.setDisplayName("§e第 " + page + " / " + totalPages + " 页"); info.setItemMeta(im); }
        gui.setItem(49, info);
    }

    ItemStack borderPane() {
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta bm = border.getItemMeta(); if (bm != null) { bm.setDisplayName("§7"); border.setItemMeta(bm); }
        return border;
    }

    String buildTitle(String prefix, int page, int totalPages) {
        return buildTitleRaw(prefix + " §8[" + page + "/" + totalPages + "]");
    }
    String buildTitleRaw(String title) { return title.length() > 32 ? title.substring(0, 32) : title; }
    int parsePage(String[] args, int idx) {
        if (args.length > idx) { try { return Math.max(1, Integer.parseInt(args[idx])); } catch (NumberFormatException ignored) {} }
        return 1;
    }
    boolean hasAccess(CommandSender sender) {
        if (plugin.getPlayerWhitelistManager() != null && plugin.getPlayerWhitelistManager().shouldSuppressLogging(sender)) return true;
        return sender.hasPermission("illegalscanner.report");
    }

    // ==================== View GUI Holder ====================

    public enum ViewType { CHUNK, CHUNK_ITEM, PLAYER, WORLD, AREA, ITEM_LIST, ITEM, SCAN_LIST, SCAN, SCAN_CHUNKS, SCAN_ITEMS, SCAN_ITEM_DETAIL, RECORD }

    public static class ViewGuiHolder implements org.bukkit.inventory.InventoryHolder {
        public final ViewType type;
        public final String context;
        public final int page, totalPages;
        public final ViewGuiHolder back;
        public final Map<Integer, ItemStack> cleanItems = new HashMap<>();
        public final Map<Integer, UnifiedRecord> slotRecords = new HashMap<>();
        public final Map<Integer, int[]> slotChunks = new HashMap<>();
        public final Map<Integer, String> slotWorld = new HashMap<>();
        public final Map<Integer, int[]> slotLocations = new HashMap<>();
        public int[] extraData; // for TP target [cx, cz] or other metadata

        public ViewGuiHolder(ViewType type, String context, int page, int totalPages, ViewGuiHolder back) {
            this.type = type; this.context = context; this.page = page; this.totalPages = totalPages; this.back = back;
        }
        public boolean hasBack() { return back != null; }
        @Override public Inventory getInventory() { return null; }
    }
}
