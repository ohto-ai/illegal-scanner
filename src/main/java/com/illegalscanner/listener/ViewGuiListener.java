package com.illegalscanner.listener;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.command.ViewCommandHandler;
import com.illegalscanner.command.ViewCommandHandler.ViewGuiHolder;
import com.illegalscanner.command.ViewCommandHandler.ViewType;
import com.illegalscanner.database.DatabaseManager.UnifiedRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Protects View GUIs: blocks decorative items, Shift+F gives clean copy,
 * handles back/page navigation, TP, and drill-down.
 */
public class ViewGuiListener implements Listener {

    private final IllegalScanner plugin;
    private final Set<String> inProgressRescans = new HashSet<>();

    public ViewGuiListener(IllegalScanner plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ViewGuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        ViewCommandHandler vh = plugin.getCommandRouter().getViewHandler();
        ClickType click = event.getClick();

        // --- Navigation row (slots 45-53) ---
        if (rawSlot >= 45 && rawSlot < topSize) {
            if (rawSlot == 45 && holder.page > 1) { reopenSame(player, holder, holder.page - 1); return; }
            if (rawSlot == 46 && holder.hasBack()) { navigateBack(player, holder); return; }
            if (rawSlot == 47) { handleSpecialSlot47(player, holder, click); return; }
            if (rawSlot == 48 && isChunkType(holder.type)) { handleRescan(player, holder); return; }
            if (rawSlot == 48 && holder.type == ViewType.PLAYER) { handlePlayerRescan(player, holder); return; }
            if (rawSlot == 53 && holder.page < holder.totalPages) { reopenSame(player, holder, holder.page + 1); return; }
            return;
        }

        // --- SCAN view: control buttons (slots 10, 14, 16) — SCAN uses 27-slot GUI ---
        if (holder.type == ViewType.SCAN && rawSlot == 14) {
            // Refresh button — reopen with latest data from DB
            int sessionId = Integer.parseInt(holder.context);
            vh.openScanView(player, sessionId, 1, holder.back);
            return;
        }
        if (holder.type == ViewType.SCAN && (rawSlot == 10 || rawSlot == 16)) {
            handleScanControl(player, holder, rawSlot);
            return;
        }

        // --- RECORD view: slot 13 center item click → copyable hash, slot 15 TP ---
        if (holder.type == ViewType.RECORD) {
            if (rawSlot == 13) {
                // Click center item → output copyable /is give command
                var dbRec = plugin.getDatabaseManager().getRecordById(
                        holder.context.split("/")[0],
                        Long.parseLong(holder.context.split("/")[1]));
                if (dbRec != null) {
                    sendCopyableHash(player, dbRec.itemHash());
                }
                return;
            }
            if (rawSlot == 15 && holder.slotLocations.containsKey(13)) {
                tpToLocation(player, holder, holder.slotLocations.get(13));
                return;
            }
        }

        // --- Content area (slots 0-44) ---
        if (rawSlot < 0 || rawSlot >= 45) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;
        if (current.getType() == Material.BLACK_STAINED_GLASS_PANE || current.getType() == Material.ARROW
                || current.getType() == Material.PAPER || current.getType() == Material.BARRIER) return;

        // Shift+Left → give clean copy
        if (click.isShiftClick() && click.isLeftClick()) {
            ItemStack clean = holder.cleanItems.get(rawSlot);
            if (clean != null) giveCleanCopy(player, clean);
            return;
        }

        // Left click → drill down / TP
        if (click.isLeftClick() && !click.isShiftClick()) {
            handleDrillDown(player, holder, rawSlot, vh, click);
        }
        // Right click → record detail for CHUNK_ITEM / SCAN_ITEM_DETAIL, or force TP for CHUNK
        if (click.isRightClick()) {
            if (holder.type == ViewType.CHUNK) {
                forceTpToChunk(player, holder);
            } // right-click TP for CHUNK_ITEM/SCAN_ITEM_DETAIL/ITEM handled in handleDrillDown
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ViewGuiHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ViewGuiHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        // Stop auto-refresh for any view type (idempotent — no-op if no active task)
        plugin.getCommandRouter().getViewHandler().stopAutoRefresh(player);
    }

    // ==================== Rescan ====================

    private boolean isChunkType(ViewType type) {
        return type == ViewType.CHUNK || type == ViewType.CHUNK_ITEM;
    }

    private void handlePlayerRescan(Player player, ViewGuiHolder holder) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(holder.context);
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c无效的玩家信息");
            return;
        }

        org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(uuid);
        String playerName = offline.getName() != null ? offline.getName() : holder.context;

        player.sendMessage("§e正在重新扫描玩家 " + playerName + "...");

        ViewCommandHandler vh = plugin.getCommandRouter().getViewHandler();

        // Run scan on main thread (scanPlayer is synchronous)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int flagged = plugin.getScanService().forceScanPlayer(uuid, playerName);

            if (flagged >= 0) {
                player.sendMessage("§a玩家扫描完成: 发现 " + flagged + " 个违规物品。");
            } else {
                player.sendMessage("§c玩家 " + playerName + " 扫描失败（未找到玩家数据）");
                return; // Do not reopen on failure
            }

            // Reopen the player view preserving current page
            plugin.getServer().getScheduler().runTask(plugin, () ->
                vh.openPlayerView(player, playerName, holder.page, holder.back));
        });
    }

    private void handleRescan(Player player, ViewGuiHolder holder) {
        // Parse chunk coordinates from context (format: "world/cx/cz")
        String[] parts = holder.context.split("/");
        if (parts.length < 3) {
            player.sendMessage("§c无效的区块信息");
            return;
        }
        String worldName = parts[0];
        int cx, cz;
        try {
            cx = Integer.parseInt(parts[1]);
            cz = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c无效的区块坐标");
            return;
        }

        // Guard against rapid double-clicks on the same chunk
        String scanKey = worldName + "/" + cx + "/" + cz;
        if (!inProgressRescans.add(scanKey)) {
            player.sendMessage("§e该区块正在扫描中，请稍后再试");
            return;
        }

        World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage("§c世界 " + worldName + " 未找到");
            inProgressRescans.remove(scanKey);
            return;
        }

        player.sendMessage("§e正在重新扫描区块 (" + cx + "," + cz + ")...");

        ViewCommandHandler vh = plugin.getCommandRouter().getViewHandler();

        // Run scan on main thread (scanChunk is synchronous)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int flagged = plugin.getScanService().forceScanChunk(world, cx, cz);

            if (flagged >= 0) {
                player.sendMessage("§a区块扫描完成: 发现 " + flagged + " 个违规物品。");
            } else {
                player.sendMessage("§c区块 (" + cx + "," + cz + ") 无法加载，扫描失败");
                inProgressRescans.remove(scanKey);
                return; // Do not reopen on failure
            }

            // Reopen chunk view preserving current page and navigation history
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                vh.openChunkView(player, worldName, cx, cz, holder.page, holder.back);
                inProgressRescans.remove(scanKey);
            });
        });
    }

    // ==================== Scan Control (Pause / Resume / Stop / Restart) ====================

    private void handleScanControl(Player player, ViewGuiHolder holder, int slot) {
        int sessionId;
        try {
            sessionId = Integer.parseInt(holder.context);
        } catch (NumberFormatException e) {
            return;
        }

        String status = plugin.getScanService().getSessionStatus(sessionId);
        if (status == null) return;

        ViewCommandHandler vh = plugin.getCommandRouter().getViewHandler();

        // Helper to reopen the scan view (must run on main thread)
        Runnable reopen = () -> plugin.getServer().getScheduler().runTask(plugin, () ->
            vh.openScanView(player, sessionId, 1, holder.back));

        if (slot == 10) {
            // Pause (RUNNING) or Resume (PAUSED)
            if ("RUNNING".equals(status)) {
                plugin.getScanService().pauseScan(sessionId)
                        .thenRun(reopen);
                player.sendMessage("§e扫描已暂停。");
            } else if ("PAUSED".equals(status)) {
                plugin.getScanService().resumeScan(sessionId)
                        .thenRun(reopen);
                player.sendMessage("§a扫描已继续。");
            }
        } else if (slot == 16) {
            // Stop (RUNNING/PAUSED) or Restart (STOPPED)
            if ("RUNNING".equals(status) || "PAUSED".equals(status)) {
                plugin.getScanService().stopScan(sessionId)
                        .thenRun(reopen);
                player.sendMessage("§c扫描已停止。");
            } else if ("STOPPED".equals(status)) {
                player.sendMessage("§e正在重新启动扫描...");
                plugin.getScanService().restartScan(sessionId);
                player.sendMessage("§a扫描已重新启动。使用 /is view scan 查看进度。");
                return; // Don't reopen — new session was created
            }
        }
    }

    // ==================== Slot 47 (TP / special) ====================

    private void handleSpecialSlot47(Player player, ViewGuiHolder holder, ClickType click) {
        switch (holder.type) {
            case CHUNK, CHUNK_ITEM -> {
                if (click.isLeftClick() && !click.isShiftClick()) tpToChunk(player, holder);
                else if (click.isShiftClick()) forceTpToChunk(player, holder);
            }
            // SCAN view slot 47 is unused (info area)
            default -> {}
        }
    }

    // ==================== Drill Down ====================

    private void handleDrillDown(Player player, ViewGuiHolder holder, int slot, ViewCommandHandler vh, ClickType click) {
        UnifiedRecord record = holder.slotRecords.get(slot);
        int[] chunkCoords = holder.slotChunks.get(slot);
        String world = holder.slotWorld.get(slot);
        int[] loc = holder.slotLocations.get(slot);

        // CHUNK_ITEM / SCAN_ITEM_DETAIL / ITEM — right click → TP, left click falls through to switch
        if (holder.type == ViewType.CHUNK_ITEM || holder.type == ViewType.SCAN_ITEM_DETAIL || holder.type == ViewType.ITEM) {
            if (click.isRightClick()) {
                if (loc != null) tpToLocation(player, holder, loc);
                if (record != null) sendCopyableHash(player, record.itemHash());
                return;
            }
            // left click: fall through to switch below → record detail
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            switch (holder.type) {
                case CHUNK -> {
                    if (record != null) {
                        String[] p = holder.context.split("/");
                        vh.openChunkItemView(player, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), record.itemHash(), 1, holder);
                    }
                }
                case CHUNK_ITEM, SCAN_ITEM_DETAIL, ITEM -> {
                    if (record != null) vh.openRecordView(player, record.source(), record.id(), holder);
                }
                case WORLD -> {
                    if (chunkCoords != null) vh.openChunkView(player, holder.context, chunkCoords[0], chunkCoords[1], 1, holder);
                }
                case AREA -> {
                    if (chunkCoords != null) vh.openChunkView(player, holder.context.split("/")[0], chunkCoords[0], chunkCoords[1], 1, holder);
                }
                case RES -> {
                    if (chunkCoords != null && world != null) vh.openChunkView(player, world, chunkCoords[0], chunkCoords[1], 1, holder);
                }
                case FULL -> {
                    if (chunkCoords != null && world != null) vh.openChunkView(player, world, chunkCoords[0], chunkCoords[1], 1, holder);
                }
                case PLAYER -> {
                    if (record != null) vh.openRecordView(player, record.source(), record.id(), holder);
                }
                case PLAYER_LIST -> {
                    String playerName = holder.slotContext.get(slot);
                    if (playerName != null) vh.openPlayerView(player, playerName, 1, holder);
                }
                case ITEM_LIST -> {
                    var items = plugin.getDatabaseManager().getDistinctViolationItems();
                    int idx = (holder.page - 1) * ViewCommandHandler.PAGE_SIZE + slot;
                    if (idx < items.size()) vh.openItemDetailView(player, items.get(idx).itemHash(), 1, holder);
                }
                case SCAN_LIST -> {
                    var sessions = plugin.getDatabaseManager().getScanSessions(1, 100);
                    int idx2 = (holder.page - 1) * ViewCommandHandler.PAGE_SIZE + slot;
                    if (idx2 < sessions.size()) vh.openScanView(player, sessions.get(idx2).id(), 1, holder);
                }
                case SCAN -> {
                    if (slot == 11) {
                        var session = plugin.getDatabaseManager().getScanSession(Integer.parseInt(holder.context));
                        if (session != null && "player".equals(session.scanType())) {
                            vh.openScanPlayerView(player, Integer.parseInt(holder.context), 1, holder);
                        } else {
                            vh.openScanChunksView(player, Integer.parseInt(holder.context), 1, holder);
                        }
                    }
                    else if (slot == 15) vh.openScanItemsView(player, Integer.parseInt(holder.context), 1, holder);
                }
                case SCAN_CHUNKS -> {
                    if (chunkCoords != null && world != null)
                        vh.openChunkView(player, world, chunkCoords[0], chunkCoords[1], 1, holder);
                }
                case SCAN_PLAYER -> {
                    // Two sub-modes:
                    // 1) Player list (context = sessionId, slotContext = playerUuid) → drill to player items
                    // 2) Player items (context = sessionId/playerUuid, slotRecords = record) → drill to item detail
                    String playerUuid = holder.slotContext.get(slot);
                    if (playerUuid != null) {
                        // Player list → open that player's items within this session
                        int sid = Integer.parseInt(holder.context);
                        String playerName = plugin.getServer().getOfflinePlayer(java.util.UUID.fromString(playerUuid)).getName();
                        if (playerName == null) playerName = playerUuid;
                        vh.openScanPlayerItemsView(player, sid, playerUuid, playerName, 1, holder);
                    } else if (record != null) {
                        // Player items → item detail
                        String[] ctx = holder.context.split("/");
                        vh.openScanItemDetailView(player, Integer.parseInt(ctx[0]), record.itemHash(), 1, holder);
                    }
                }
                case SCAN_ITEMS -> {
                    if (record != null) vh.openScanItemDetailView(player, Integer.parseInt(holder.context), record.itemHash(), 1, holder);
                }
                case RECORD -> { /* leaf node */ }
            }
        });
    }

    // ==================== TP ====================

    private void tpToChunk(Player player, ViewGuiHolder holder) {
        int[] data = holder.extraData;
        if (data == null || data.length < 2) return;
        int cx = data[0], cz = data[1];
        World world = player.getWorld();
        int bx = cx * 16 + 8, bz = cz * 16 + 8;
        Location safe = findHighestSafe(world, bx, bz);
        if (safe != null) {
            player.teleport(safe);
            player.sendMessage("§a已传送到区块(" + cx + "," + cz + ") 中心 Y=" + safe.getBlockY());
        } else {
            player.sendMessage("§c未找到安全落脚点！使用 §eShift+左键TP按钮 §c强制传送");
        }
    }

    private void forceTpToChunk(Player player, ViewGuiHolder holder) {
        int[] data = holder.extraData;
        if (data == null || data.length < 2) return;
        int cx = data[0], cz = data[1];
        Location loc = new Location(player.getWorld(), cx * 16 + 8.5, player.getY(), cz * 16 + 8.5);
        player.teleport(loc);
        player.sendMessage("§e已强制传送到区块(" + cx + "," + cz + ") 中心");
    }

    private void tpToLocation(Player player, ViewGuiHolder holder, int[] loc) {
        if (loc == null || loc.length < 3) return;
        World world = player.getWorld();
        Location safe = findHighestSafe(world, loc[0], loc[2]);
        if (safe != null) {
            player.teleport(safe);
            player.sendMessage("§a已传送到 " + loc[0] + ", " + safe.getBlockY() + ", " + loc[2]);
        } else {
            Location forced = new Location(world, loc[0] + 0.5, loc[1] + 1, loc[2] + 0.5);
            player.teleport(forced);
            player.sendMessage("§e已强制传送");
        }
    }

    /** Use world's highest block, then check 2 air blocks above it. */
    private Location findHighestSafe(World world, int bx, int bz) {
        int surfaceY = world.getHighestBlockYAt(bx, bz);
        // Check the surface + a few blocks above for safety
        for (int y = Math.min(surfaceY + 4, world.getMaxHeight() - 2); y >= surfaceY; y--) {
            Block ground = world.getBlockAt(bx, y - 1, bz);
            Block feet = world.getBlockAt(bx, y, bz);
            Block head = world.getBlockAt(bx, y + 1, bz);
            if (ground.getType().isSolid() && !ground.isLiquid()
                    && !ground.getType().name().contains("LEAVES")
                    && (feet.isEmpty() || feet.isLiquid())
                    && (head.isEmpty() || head.isLiquid())) {
                return new Location(world, bx + 0.5, y, bz + 0.5);
            }
        }
        // Fallback: force TP at surface + 1
        return new Location(world, bx + 0.5, surfaceY + 1, bz + 0.5);
    }

    // ==================== Navigation ====================

    private void navigateBack(Player player, ViewGuiHolder holder) {
        if (holder.back == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> reopenSame(player, holder.back, holder.back.page));
    }

    private void reopenSame(Player player, ViewGuiHolder holder, int newPage) {
        ViewCommandHandler vh = plugin.getCommandRouter().getViewHandler();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            switch (holder.type) {
                case CHUNK -> {
                    String[] p = holder.context.split("/");
                    vh.openChunkView(player, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), newPage, holder.back);
                }
                case CHUNK_ITEM -> {
                    String[] p = holder.context.split("/");
                    vh.openChunkItemView(player, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3], newPage, holder.back);
                }
                case PLAYER -> vh.openPlayerView(player, plugin.getServer().getPlayer(java.util.UUID.fromString(holder.context)).getName(), newPage, holder.back);
                case WORLD -> vh.openWorldView(player, holder.context, newPage, holder.back);
                case AREA -> {
                    String[] p = holder.context.split("/");
                    vh.openAreaView(player, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), newPage, holder.back);
                }
                case RES -> {
                    String[] p = holder.context.split("/");
                    vh.openResView(player, p[0], p[1], p[2], newPage, holder.back);
                }
                case FULL -> vh.openFullView(player, newPage, holder.back);
                case PLAYER_LIST -> vh.openPlayerListView(player, newPage, holder.back);
                case ITEM_LIST -> vh.openItemListView(player, newPage, holder.back);
                case ITEM -> vh.openItemDetailView(player, holder.context, newPage, holder.back);
                case SCAN_LIST -> vh.openScanListView(player, newPage, holder.back);
                case SCAN -> vh.openScanView(player, Integer.parseInt(holder.context), newPage, holder.back);
                case SCAN_CHUNKS -> vh.openScanChunksView(player, Integer.parseInt(holder.context), newPage, holder.back);
                case SCAN_PLAYER -> {
                    if (holder.context.contains("/")) {
                        String[] sp = holder.context.split("/", 2);
                        int sid = Integer.parseInt(sp[0]);
                        String puid = sp[1];
                        String pname = plugin.getServer().getOfflinePlayer(java.util.UUID.fromString(puid)).getName();
                        if (pname == null) pname = puid;
                        vh.openScanPlayerItemsView(player, sid, puid, pname, newPage, holder.back);
                    } else {
                        vh.openScanPlayerView(player, Integer.parseInt(holder.context), newPage, holder.back);
                    }
                }
                case SCAN_ITEMS -> vh.openScanItemsView(player, Integer.parseInt(holder.context), newPage, holder.back);
                case SCAN_ITEM_DETAIL -> {
                    String[] sp = holder.context.split("/", 2);
                    vh.openScanItemDetailView(player, Integer.parseInt(sp[0]), sp[1], newPage, holder.back);
                }
                case RECORD -> {}
            }
        });
    }

    /** Send a clickable /is give command to chat that copies the hash when clicked. */
    private void sendCopyableHash(Player player, String hash) {
        Component msg = Component.text("▶ ", NamedTextColor.GRAY)
                .append(Component.text("/is give ", NamedTextColor.GOLD))
                .append(Component.text(hash, NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, hash)))
                .append(Component.text("  (点击复制hash)", NamedTextColor.GRAY));
        player.sendMessage(msg);
    }

    private void giveCleanCopy(Player player, ItemStack clean) {
        ItemStack copy = clean.clone();
        var overflow = player.getInventory().addItem(copy);
        if (!overflow.isEmpty())
            for (ItemStack drop : overflow.values())
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
    }
}
