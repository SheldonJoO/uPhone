package com.airsim.phonecontrol;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.Telephony;

import org.json.JSONObject;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional READ_SMS fallback; never imports messages present before the first authorized scan.
 *
 * <p>SMS_RECEIVED 广播在后台被限制、进程被杀等场景下会漏，收件箱扫描把这些漏掉的短信补回来。
 * 首次扫描只建立高水位 baseline，不导入配对前的历史消息；与广播侧共用
 * {@link SMSDeliveryIdentity} 的 delivery key，同一条短信两条路径不会重复入队。
 */
final class SMSInboxScanner {
    private static final String PREFS = "sms_inbox_fallback";
    private static final String LAST_ID = "last_inbox_id";
    private static final long INTERVAL_MS = 30_000;
    private static final int BATCH_SIZE = 100;
    private static long lastScanTime;

    private SMSInboxScanner() {}

    static synchronized void scanIfDue(Context context) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return;
        long now = System.currentTimeMillis();
        if (now - lastScanTime < INTERVAL_MS) return;
        lastScanTime = now;
        try {
            scan(context.getApplicationContext());
        } catch (Exception error) {
            BridgeLog.error("sms_inbox_scan_failed", error);
        }
    }

    private static void scan(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SMSInboxCursor progress = new SMSInboxCursor(preferences.getLong(LAST_ID, -1));
        if (!progress.initialized()) {
            String[] projection = {BaseColumns._ID};
            try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    projection, null, null, BaseColumns._ID + " DESC")) {
                if (cursor == null) throw new IllegalStateException("SMS inbox provider unavailable");
                progress.baseline(cursor.moveToFirst() ? cursor.getLong(0) : 0);
            }
            persist(preferences, progress);
            BridgeLog.info("sms_inbox_baseline_initialized");
            return;
        }

        String[] projection = {BaseColumns._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.DATE_SENT};
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                projection, BaseColumns._ID + " > ?",
                new String[]{Long.toString(progress.value())}, BaseColumns._ID + " ASC")) {
            if (cursor == null) throw new IllegalStateException("SMS inbox provider unavailable");
            int scanned = 0;
            int nonempty = 0;
            long startingID = progress.value();
            while (scanned < BATCH_SIZE && cursor.moveToNext()) {
                scanned++;
                long rowID = cursor.getLong(0);
                if (!progress.shouldQueue(rowID)) continue;
                String sender = cursor.getString(1);
                String content = cursor.getString(2);
                if (content != null && !content.isEmpty()) {
                    nonempty++;
                    long receivedAt = cursor.getLong(3);
                    long sentAt = cursor.getLong(4);
                    long timestamp = SMSDeliveryIdentity.providerTimestamp(sentAt, receivedAt);
                    if (timestamp <= 0) timestamp = System.currentTimeMillis();
                    String deliveryID = SMSDeliveryIdentity.id(sender, content, timestamp);
                    JSONObject event = new JSONObject(WireJson.smsEvent(UUID.randomUUID().toString(),
                            deliveryID, sender == null ? "" : sender, content,
                            Instant.ofEpochMilli(timestamp).toString()));
                    if (RuntimeMode.isStandalone(context)) {
                        StandaloneAgentGateway.get(context).sendSMSEvent(event.toString());
                    } else {
                        new StandaloneSMSStore(context).add(event, true);
                    }
                }
                progress.processed(rowID);
                persist(preferences, progress);
            }
            if (scanned > 0) BridgeLog.info("sms_inbox_scan rows=" + scanned
                    + " nonempty=" + nonempty + " cursor_before=" + startingID
                    + " cursor_after=" + progress.value());
        }
    }

    private static void persist(SharedPreferences preferences, SMSInboxCursor progress) {
        if (!preferences.edit().putLong(LAST_ID, progress.value()).commit()) {
            throw new IllegalStateException("SMS inbox cursor could not be saved");
        }
    }
}
