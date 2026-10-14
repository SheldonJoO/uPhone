package com.airsim.phonecontrol;

/** Persisted high-water mark: first grant skips history; failed rows remain retryable. */
final class SMSInboxCursor {
    private long lastID;

    SMSInboxCursor(long lastID) { this.lastID = lastID; }

    boolean initialized() { return lastID >= 0; }

    void baseline(long newestID) {
        if (!initialized()) lastID = Math.max(0, newestID);
    }

    boolean shouldQueue(long rowID) { return initialized() && rowID > lastID; }

    void processed(long rowID) { lastID = Math.max(lastID, rowID); }

    long value() { return lastID; }
}
