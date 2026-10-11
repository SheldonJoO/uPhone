package com.airsim.phonecontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Receives carrier SMS without requiring the default-SMS-app role.
 *
 * <p>Processes synchronously inside {@link #onReceive}: PDU parsing and the
 * store write take well under 100ms, so there is no need for {@code goAsync()}
 * plus a background executor. The old async pattern could drop messages when
 * the process was killed before the worker ran, or when the static executor
 * was shut down — that was the "SMS never arrives" bug.
 */
public final class IncomingSMSReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        Context application = context.getApplicationContext();
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;
            StringBuilder content = new StringBuilder();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String sender = parts[0].getOriginatingAddress();
            long timestamp = parts[0].getTimestampMillis();
            int subscriptionId = intent.getIntExtra("subscription",
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            for (SmsMessage part : parts) {
                if (part == null) continue;
                content.append(part.getMessageBody() == null ? "" : part.getMessageBody());
                byte[] pdu = part.getPdu();
                if (pdu != null) digest.update(pdu);
                if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        && Build.VERSION.SDK_INT >= 22) {
                    try {
                        int subId = part.getSubId();
                        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                            subscriptionId = subId;
                        }
                    } catch (SecurityException ignored) {}
                }
            }
            if (content.length() == 0) return;
            digest.update((sender == null ? "" : sender).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            digest.update(content.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("android-sms-");
            for (byte value : digest.digest()) id.append(String.format("%02x", value & 0xff));
            JSONObject event = new JSONObject(WireJson.smsEvent(UUID.randomUUID().toString(),
                    id.toString(), sender == null ? "" : sender, content.toString(),
                    Instant.ofEpochMilli(timestamp > 0 ? timestamp : System.currentTimeMillis()).toString()));
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                event.put("subscription_id", subscriptionId);
                event.put("slot_index", SimCardManager.slotIndexFor(application, subscriptionId));
            }
            if (RuntimeMode.isStandalone(application)) {
                StandaloneAgentGateway.get(application).sendSMSEvent(event.toString());
            } else {
                new StandaloneSMSStore(application).add(event, true);
                AgentWatchdogService.start(application);
            }
            BridgeLog.info("incoming_sms_queued delivery_id=" + DebugRedactor.safeIdentifier(id.toString())
                    + " subscription_id=" + subscriptionId);
        } catch (Exception error) {
            BridgeLog.error("incoming_sms_queue_failed", error);
        }
    }
}
