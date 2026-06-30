package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * In-memory deduplication cache for chunk scans.
 *
 * Key = "world:chunkX:chunkZ" → last scan timestamp (epoch ms).
 * Avoids scanning the same chunk multiple times when overlapping scan commands
 * are issued in quick succession (e.g., overlapping area scans, or a world scan
 * right after another world scan).
 *
 * Thread-safe: ConcurrentHashMap for reads from main thread (scan loop),
 * async writes on DB thread for lazy population.
 */
public class ChunkScanDedupCache {

    private final IllegalScanner plugin;
    private final ConcurrentHashMap<String, Long> lastScanTime = new ConcurrentHashMap<>();

    public ChunkScanDedupCache(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    // --------------- key encoding ---------------

    /**
     * Encode (world, chunkX, chunkZ) into a single key.
     * Compact representation avoids per-lookup string concatenation overhead
     * in hot paths — callers should pre-compute and reuse.
     */
    public static String key(String world, int cx, int cz) {
        return world + ':' + cx + ':' + cz;
    }

    // --------------- read path (main thread) ---------------

    /**
     * Returns true if this chunk was last scanned AFTER the given commandTime,
     * meaning a newer scan already covered it — this scan should skip it.
     *
     * @param commandTime the timestamp (epoch ms) when the current scan command was issued
     */
    public boolean shouldSkip(String world, int cx, int cz, long commandTime) {
        String k = key(world, cx, cz);
        Long last = lastScanTime.get(k);
        if (last != null) {
            return last > commandTime;
        }
        // Not in cache — trigger async load, but don't skip (assume not scanned)
        loadAsync(world, cx, cz);
        return false;
    }

    /**
     * Lookup-only: get last scan time for a chunk (0 if unknown).
     */
    public long getLastScanTime(String world, int cx, int cz) {
        Long v = lastScanTime.get(key(world, cx, cz));
        return v != null ? v : 0L;
    }

    // --------------- write path ---------------

    /**
     * Mark a chunk as scanned at the given time.
     * Called after a successful chunk scan.
     */
    public void markScanned(String world, int cx, int cz, long time) {
        lastScanTime.put(key(world, cx, cz), time);
    }

    // --------------- async population ---------------

    /**
     * Lazily load the last scan time for a chunk from the database.
     * Runs on the DB executor thread so the main thread is never blocked.
     */
    private void loadAsync(String world, int cx, int cz) {
        DatabaseManager db = plugin.getDatabaseManager();
        String k = key(world, cx, cz);

        // Put a sentinel value (0) to prevent concurrent duplicate loads
        if (lastScanTime.putIfAbsent(k, 0L) != null) {
            return; // Another thread already triggered the load
        }

        db.getDbExecutor().submit(() -> {
            try {
                long t = db.getLastChunkScanTime(world, cx, cz);
                lastScanTime.put(k, t); // update from sentinel → real value (or 0 if never scanned)
            } catch (Exception e) {
                // Remove sentinel so next lookup retries
                lastScanTime.remove(k);
                plugin.getLogger().log(Level.FINE,
                        "Failed to load scan time for chunk " + world + ":" + cx + ":" + cz, e);
            }
        });
    }

    /**
     * Clear all cached entries (e.g., on plugin disable or config reload).
     */
    public void clear() {
        lastScanTime.clear();
    }

    /**
     * Number of cached entries (for debugging/stats).
     */
    public int size() {
        return lastScanTime.size();
    }
}
