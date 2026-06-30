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
     * @param scanType chunk | player | area | res | world
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
     * Update session progress incrementally (does NOT change status).
     * Call periodically during long scans so the GUI shows real-time progress.
     */
    public CompletableFuture<Void> updateProgress(int sessionId, int itemsScanned, int itemsFlagged) {
        return plugin.getDatabaseManager().updateScanSessionProgress(sessionId, itemsScanned, itemsFlagged);
    }

    /**
     * Get a session by ID.
     */
    public ScanSession getSession(int id) {
        return plugin.getDatabaseManager().getScanSession(id);
    }

    /**
     * Pause a running scan session — sets status to PAUSED.
     */
    public CompletableFuture<Void> pauseSession(int sessionId) {
        return plugin.getDatabaseManager().setSessionStatus(sessionId, "PAUSED");
    }

    /**
     * Resume a paused scan session — sets status back to RUNNING.
     */
    public CompletableFuture<Void> resumeSession(int sessionId) {
        return plugin.getDatabaseManager().setSessionStatus(sessionId, "RUNNING");
    }

    /**
     * Stop a running or paused scan session — sets status to STOPPED and records completed_at.
     */
    public CompletableFuture<Void> stopSession(int sessionId) {
        return plugin.getDatabaseManager().stopScanSession(sessionId);
    }

    /**
     * Update scan session status.
     */
    public CompletableFuture<Void> setSessionStatus(int sessionId, String status) {
        return plugin.getDatabaseManager().setSessionStatus(sessionId, status);
    }
}
