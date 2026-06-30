package com.illegalscanner.monitor;

/**
 * Types of events the MonitorEngine can listen to.
 * CONTAINER_OPEN triggers on InventoryCloseEvent: re-scans the closed
 * container and writes a snapshot to monitor_records as CONTAINER_CLOSE.
 */
public enum MonitorEventType {
    INVENTORY_CLICK,
    INVENTORY_DRAG,
    ITEM_MOVE,
    PLAYER_JOIN,
    CHUNK_LOAD,
    CONTAINER_OPEN
}
