package com.illegalscanner.query;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import com.illegalscanner.database.DatabaseManager.UnifiedRecord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified query layer combining scan_records + monitor_records.
 * Wraps DatabaseManager's union queries with a simple in-memory cache.
 */
public class UnifiedQueryService {

    private final IllegalScanner plugin;
    private final DatabaseManager db;

    /** Simple TTL cache: key → (timestamp, data). */
    private static class CacheEntry<T> {
        final long timestamp;
        final T data;
        CacheEntry(T data) { this.timestamp = System.currentTimeMillis(); this.data = data; }
        boolean isExpired(long ttlMs) { return System.currentTimeMillis() - timestamp > ttlMs; }
    }

    private final Map<String, CacheEntry<List<UnifiedRecord>>> chunkCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<UnifiedRecord>>> playerCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<UnifiedRecord>>> itemCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000; // 60 seconds

    public UnifiedQueryService(IllegalScanner plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
    }

    // ==================== Chunk ====================

    public List<UnifiedRecord> getChunkStatus(String world, int chunkX, int chunkZ, int page, int pageSize) {
        String key = world + "/" + chunkX + "/" + chunkZ + "/" + page;
        CacheEntry<List<UnifiedRecord>> cached = chunkCache.get(key);
        if (cached != null && !cached.isExpired(CACHE_TTL_MS)) {
            return cached.data;
        }
        List<UnifiedRecord> records = db.getRecordsByChunk(world, chunkX, chunkZ, page, pageSize);
        chunkCache.put(key, new CacheEntry<>(records));
        return records;
    }

    public int countChunkRecords(String world, int chunkX, int chunkZ) {
        return db.countViolationsByChunk(world, chunkX, chunkZ);
    }

    public void invalidateChunk(String world, int chunkX, int chunkZ) {
        chunkCache.clear(); // Simple full flush on write
    }

    // ==================== Player ====================

    public List<UnifiedRecord> getPlayerStatus(String playerUuid, int page, int pageSize) {
        String key = playerUuid + "/" + page;
        CacheEntry<List<UnifiedRecord>> cached = playerCache.get(key);
        if (cached != null && !cached.isExpired(CACHE_TTL_MS)) {
            return cached.data;
        }
        List<UnifiedRecord> records = db.getRecordsByPlayer(playerUuid, page, pageSize);
        playerCache.put(key, new CacheEntry<>(records));
        return records;
    }

    public int countPlayerRecords(String playerUuid) {
        return db.countRecordsByPlayer(playerUuid);
    }

    public void invalidatePlayer(String playerUuid) {
        playerCache.clear();
    }

    // ==================== Item ====================

    public List<UnifiedRecord> getItemRecords(String itemHash, int page, int pageSize) {
        String key = itemHash + "/" + page;
        CacheEntry<List<UnifiedRecord>> cached = itemCache.get(key);
        if (cached != null && !cached.isExpired(CACHE_TTL_MS)) {
            return cached.data;
        }
        List<UnifiedRecord> records = db.getRecordsByItem(itemHash, page, pageSize);
        itemCache.put(key, new CacheEntry<>(records));
        return records;
    }

    public int countItemRecords(String itemHash) {
        return db.getItemRecordCount(itemHash);
    }

    // ==================== Write-side invalidation ====================

    /** Call when a new scan or monitor record is written. */
    public void invalidateAll() {
        chunkCache.clear();
        playerCache.clear();
        itemCache.clear();
    }
}
