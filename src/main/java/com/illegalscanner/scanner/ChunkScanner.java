package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.validator.ValidationResult;
import com.illegalscanner.validator.Violation;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Scans chunk containers for illegal items.
 */
public class ChunkScanner {

    private final IllegalScanner plugin;

    /**
     * Lightweight record: a violating block's position and severity.
     * Used by the show command to get results directly (not via async DB).
     */
    public record ViolationBlock(int x, int y, int z, String severity) {}

    public ChunkScanner(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    /**
     * Callback for each violation found during scanning.
     * The caller decides how to record (scan_records, monitor_records, or discard).
     */
    @FunctionalInterface
    public interface ViolationCallback {
        void onViolation(ItemStack item, int slot, String container, Location loc,
                         List<Violation> violations, ValidationResult severity);
    }

    /**
     * Scan a single chunk with a callback for each violation found.
     * Does NOT write to database itself — delegates to the callback.
     *
     * @return number of illegal items found
     */
    public int scanChunk(Chunk chunk, ViolationCallback callback) {
        return scanChunkInternal(chunk, null, callback);
    }

    /**
     * Quick scan — validates items but does NOT write to database.
     * Used by /is check chunk.
     */
    public int scanChunkQuick(Chunk chunk) {
        return scanChunkInternal(chunk, null, null);
    }

    /**
     * Scan a chunk and return violation block data for highlighting.
     * Still uses callback for recording.
     */
    public List<ViolationBlock> scanChunkForShow(Chunk chunk) {
        List<ViolationBlock> blocks = new ArrayList<>();
        scanChunkInternal(chunk, blocks, null);
        return blocks;
    }

    /**
     * Core scanning logic.
     */
    private int scanChunkInternal(Chunk chunk, List<ViolationBlock> blockCollector, ViolationCallback callback) {
        if (chunk == null || !chunk.isLoaded()) {
            return 0;
        }

        boolean scanItemFrames = plugin.getConfigManager().getConfig()
                .getBoolean("scan.scan_item_frames", true);
        boolean scanArmorStands = plugin.getConfigManager().getConfig()
                .getBoolean("scan.scan_armor_stands", true);
        boolean scanMinecarts = plugin.getConfigManager().getConfig()
                .getBoolean("scan.scan_minecart_containers", true);

        List<ContainerUtil.ContainerInfo> containers;
        try {
            containers = ContainerUtil.findContainers(
                    chunk, scanItemFrames, scanArmorStands, scanMinecarts);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Error finding containers in chunk " + chunk.getX() + "," + chunk.getZ(), e);
            return 0;
        }

        if (containers.isEmpty()) {
            return 0;
        }

        int totalFlagged = 0;
        for (ContainerUtil.ContainerInfo container : containers) {
            for (ContainerUtil.SlotItem slotItem : container.items()) {
                // Validate item
                List<Violation> violations = plugin.getValidationEngine().validate(slotItem.item(), container.location());
                if (!violations.isEmpty()) {
                    ValidationResult severity = plugin.getValidationEngine().getOverallSeverity(violations);

                    // Collect block position for highlighting
                    if (blockCollector != null) {
                        blockCollector.add(new ViolationBlock(
                                container.location().getBlockX(),
                                container.location().getBlockY(),
                                container.location().getBlockZ(),
                                severity.name()));
                    }

                    // Delegate to callback (if set) for recording
                    if (callback != null) {
                        callback.onViolation(slotItem.item(), slotItem.slot(),
                                container.containerType(), container.location(),
                                violations, severity);
                    }
                    totalFlagged++;
                }
            }
        }

        return totalFlagged;
    }

    /**
     * Scan all loaded chunks in a world.
     * @param callback receives each violation for recording
     */
    public int scanLoadedChunks(World world, ViolationCallback callback) {
        int totalFlagged = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            totalFlagged += scanChunk(chunk, callback);
        }
        return totalFlagged;
    }
}
