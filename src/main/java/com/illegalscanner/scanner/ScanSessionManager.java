package com.illegalscanner.scanner;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager.ScanSession;

import java.util.concurrent.CompletableFuture;

/**
 * Manages scan session lifecycle for grouping scan records.
 */
public class ScanSessionManager {

    private final IllegalScanner plugin;

    public ScanSessionManager(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    /**
     * Create a new scan session.
     *
     * @param scanType chunk | player | area | res | world | full
     * @param target   description of what's being scanned
     * @return CompletableFuture with the new session ID
     */
    public CompletableFuture<Integer> createSession(String scanType, String target, int totalItems) {
        return plugin.getDatabaseManager().createScanSession(scanType, target, totalItems);
    }

    /**
     * Mark a session as completed with final stats.
     */
    public CompletableFuture<Void> completeSession(int sessionId, int itemsScanned, int itemsFlagged) {
        return plugin.getDatabaseManager().completeScanSession(sessionId, itemsScanned, itemsFlagged);
    }

    /**
     * Get a session by ID.
     */
    public ScanSession getSession(int id) {
        return plugin.getDatabaseManager().getScanSession(id);
    }
}
