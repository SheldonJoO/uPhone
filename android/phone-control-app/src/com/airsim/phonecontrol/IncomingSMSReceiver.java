package com.airsim.phonecontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

import org.json.JSONObject;

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
            String sender = parts[0].getOriginatingAddress();
            long timestamp = parts[0].getTimestampMillis();
            int subscriptionId = intent.getIntExtra("subscription",
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            for (SmsMessage part : parts) {
                if (part == null) continue;
                content.append(part.getMessageBody() == null ? "" : part.getMessageBody());
            }
            if (content.length() == 0) return;
            long eventTimestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
            // 与 SMSInboxScanner 共用同一套 delivery key：广播漏收时由扫描器补回的同一条短信，
            // delivery_id 一致，StandaloneSMSStore.add 的去重会直接挡掉，不会重复推给 iPhone。
            String id = SMSDeliveryIdentity.id(sender, content.toString(), eventTimestamp);
            JSONObject event = new JSONObject(WireJson.smsEvent(UUID.randomUUID().toString(),
                    id, sender == null ? "" : sender, content.toString(),
                    Instant.ofEpochMilli(eventTimestamp).toString()));
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                event.put("subscription_id", subscriptionId);
                event.put("slot_index", SimCardManager.slotIndexFor(application, subscriptionId));
            }
            String body = content.toString();
            String code = SmsCodeExtractor.extract(body);
            if (!code.isEmpty()) event.put("code", code);
            event.put("category", SmsCodeExtractor.classify(sender, body));
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
