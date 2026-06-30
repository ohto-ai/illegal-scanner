package com.illegalscanner.show;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager.UnifiedRecord;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;
import java.util.Objects;

/**
 * Manages per-player chunk block visual highlights using colored dust particles.
 * Each active highlight has a repeating particle task and an auto-cleanup task.
 */
public class ChunkHighlightManager {

    private static final float DUST_SIZE = 1.5f;
    /** Step size along block edges for particle placement (blocks). */
    private static final double EDGE_STEP = 0.2;

    private final IllegalScanner plugin;
    private final Map<UUID, HighlightSession> activeHighlights = new HashMap<>();

    record HighlightSession(UUID playerUuid, int cleanupTaskId, int particleTaskId, World world) {}

    record BlockHighlight(int x, int y, int z, String severity) {}

    public ChunkHighlightManager(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    /**
     * Start a visual highlight of violating blocks for a player.
     * Cancels any existing highlight for this player first.
     */
    public void startHighlight(Player player, World world, int chunkX, int chunkZ,
                                List<UnifiedRecord> items, int durationSeconds) {
        // Cancel any existing highlight for this player
        if (activeHighlights.containsKey(player.getUniqueId())) {
            stopHighlight(player);
        }

        UUID uuid = player.getUniqueId();

        // Filter to records with container locations, convert to BlockHighlight
        List<BlockHighlight> blocks = items.stream()
                .filter(item -> item.containerLoc() != null)
                .map(item -> {
                    String[] parts = item.containerLoc().split(",");
                    if (parts.length >= 4) {
                        try {
                            int x = Integer.parseInt(parts[1]);
                            int y = Integer.parseInt(parts[2]);
                            int z = Integer.parseInt(parts[3]);
                            return new BlockHighlight(x, y, z, item.severity());
                        } catch (NumberFormatException ignored) {}
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();

        if (blocks.isEmpty()) return;

        // Cap blocks at configured maximum
        int maxBlocks = plugin.getConfigManager().getConfig()
                .getInt("visualization.max_highlight_blocks", 100);
        List<BlockHighlight> displayBlocks = blocks.size() > maxBlocks
                ? new ArrayList<>(blocks.subList(0, maxBlocks))
                : new ArrayList<>(blocks);

        // Notify if truncated
        if (blocks.size() > maxBlocks) {
            player.sendMessage(plugin.getMessages().get("show.too_many_blocks",
                    "{count}", String.valueOf(maxBlocks)));
        }

        // Cache DustOptions for the two severity colors
        final DustOptions redDust = new DustOptions(Color.RED, DUST_SIZE);
        final DustOptions yellowDust = new DustOptions(Color.YELLOW, DUST_SIZE);

        // Particle refresh interval (ticks)
        int refreshTicks = plugin.getConfigManager().getConfig()
                .getInt("visualization.particle_refresh_ticks", 10);

        plugin.getLogger().info("[Show] Starting highlight for " + player.getName()
                + " in world " + world.getName() + " chunk (" + chunkX + "," + chunkZ + ")"
                + " — " + displayBlocks.size() + " blocks, duration=" + durationSeconds + "s");

        // Start repeating particle task
        int particleTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                () -> {
                    // Verify player is still online
                    if (!player.isOnline()) {
                        cancelAllForPlayer(uuid);
                        return;
                    }
                    try {
                        spawnParticles(world, displayBlocks, redDust, yellowDust);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "[Show] Particle spawn error for " + player.getName(), e);
                    }
                },
                0L,             // initial delay
                refreshTicks    // repeat interval
        );

        if (particleTaskId < 0) {
            plugin.getLogger().warning("[Show] Failed to schedule particle task!");
            return;
        }

        // Schedule auto-cleanup after duration
        int cleanupTaskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(
                plugin,
                () -> {
                    plugin.getLogger().info("[Show] Highlight expired for " + player.getName());
                    stopHighlight(player);
                },
                durationSeconds * 20L
        );

        activeHighlights.put(uuid, new HighlightSession(uuid, cleanupTaskId, particleTaskId, world));
        plugin.getLogger().info("[Show] Particle task #" + particleTaskId
                + " scheduled, cleanup in " + (durationSeconds * 20L) + " ticks");
    }

    /**
     * Spawn particles along all 12 edges of each block's bounding box to form a visible
     * wireframe outline. Uses World-level spawning with force=true.
     */
    private void spawnParticles(World world, List<BlockHighlight> blocks,
                                 DustOptions redDust, DustOptions yellowDust) {
        for (BlockHighlight block : blocks) {
            DustOptions options = "ILLEGAL".equals(block.severity()) ? redDust : yellowDust;
            double x0 = block.x();
            double y0 = block.y();
            double z0 = block.z();
            double x1 = x0 + 1.0;
            double y1 = y0 + 1.0;
            double z1 = z0 + 1.0;

            // 12 edges of a unit cube. Each edge is drawn as a line of particles
            // with spacing EDGE_STEP.

            // Bottom face edges (y = y0)
            drawEdge(world, x0, y0, z0, x1, y0, z0, options);
            drawEdge(world, x1, y0, z0, x1, y0, z1, options);
            drawEdge(world, x1, y0, z1, x0, y0, z1, options);
            drawEdge(world, x0, y0, z1, x0, y0, z0, options);

            // Top face edges (y = y1)
            drawEdge(world, x0, y1, z0, x1, y1, z0, options);
            drawEdge(world, x1, y1, z0, x1, y1, z1, options);
            drawEdge(world, x1, y1, z1, x0, y1, z1, options);
            drawEdge(world, x0, y1, z1, x0, y1, z0, options);

            // Vertical edges
            drawEdge(world, x0, y0, z0, x0, y1, z0, options);
            drawEdge(world, x1, y0, z0, x1, y1, z0, options);
            drawEdge(world, x1, y0, z1, x1, y1, z1, options);
            drawEdge(world, x0, y0, z1, x0, y1, z1, options);
        }
    }

    /**
     * Draw a line of particles from (ax, ay, az) to (bx, by, bz) with the given step size.
     */
    private void drawEdge(World world, double ax, double ay, double az,
                           double bx, double by, double bz, DustOptions options) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / EDGE_STEP));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = ax + dx * t;
            double py = ay + dy * t;
            double pz = az + dz * t;
            world.spawnParticle(
                    Particle.DUST,
                    px, py, pz,
                    1,              // count
                    0.0, 0.0, 0.0, // offsets (exact position)
                    0.0,            // extra
                    options,
                    true            // force render
            );
        }
    }

    /**
     * Stop and clean up any active highlight for a player.
     */
    public void stopHighlight(Player player) {
        HighlightSession session = activeHighlights.remove(player.getUniqueId());
        if (session != null) {
            plugin.getServer().getScheduler().cancelTask(session.particleTaskId());
            plugin.getServer().getScheduler().cancelTask(session.cleanupTaskId());
            plugin.getLogger().info("[Show] Stopped highlight for " + player.getName());
        }
    }

    /**
     * Cancel a highlight by UUID without requiring the Player object.
     * Used on player quit to prevent orphaned tasks.
     */
    public void cancelAllForPlayer(UUID playerUuid) {
        HighlightSession session = activeHighlights.remove(playerUuid);
        if (session != null) {
            plugin.getServer().getScheduler().cancelTask(session.particleTaskId());
            plugin.getServer().getScheduler().cancelTask(session.cleanupTaskId());
        }
    }

    /**
     * Stop all active highlights (called on plugin disable).
     */
    public void stopAll() {
        // Defensive copy to avoid concurrent modification
        for (UUID uuid : new HashSet<>(activeHighlights.keySet())) {
            cancelAllForPlayer(uuid);
        }
        activeHighlights.clear();
    }

    /**
     * Check if a player has an active highlight.
     */
    public boolean hasActiveHighlight(Player player) {
        return activeHighlights.containsKey(player.getUniqueId());
    }
}
