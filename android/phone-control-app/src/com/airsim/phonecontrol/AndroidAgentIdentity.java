package com.airsim.phonecontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** Stable, non-secret metadata used to distinguish paired Android Agent instances. */
final class AndroidAgentIdentity {
    private AndroidAgentIdentity() {}

    static String agentID(Context context) {
        String androidID = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String source = context.getPackageName() + ":" + (androidID == null ? "unknown" : androidID);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("android-");
            for (int index = 0; index < 12; index++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "android-" + Integer.toUnsignedString(source.hashCode(), 16);
        }
    }

    static String deviceName() {
        String manufacturer = clean(Build.MANUFACTURER);
        String model = clean(Build.MODEL);
        if (manufacturer.isEmpty()) return model;
        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return (manufacturer + " " + model).trim();
    }

    static String phoneNumber(Context context) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                != PackageManager.PERMISSION_GRANTED) return "";
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            if (manager != null && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED) {
                List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
                if (subscriptions != null) {
                    for (SubscriptionInfo subscription : subscriptions) {
                        String value = Build.VERSION.SDK_INT >= 33
                                ? manager.getPhoneNumber(subscription.getSubscriptionId())
                                : subscription.getNumber();
                        if (!clean(value).isEmpty()) return clean(value);
                    }
                }
            }
            TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
            return telephony == null ? "" : clean(telephony.getLine1Number());
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Lists active SIM subscriptions for dual-SIM dialing and SMS routing.
     * 自动检测失败时，用手动配置的号码补齐（TCL 功能机 SubscriptionManager 可能不可靠）。 */
    static JSONArray simCards(Context context) {
        JSONArray cards = new JSONArray();
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return cards;
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            if (manager == null) return cards;
            List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
            if (subscriptions == null) return cards;
            boolean canReadNumbers = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                    == PackageManager.PERMISSION_GRANTED;
            for (SubscriptionInfo subscription : subscriptions) {
                int subscriptionId = subscription.getSubscriptionId();
                String number = "";
                if (canReadNumbers) {
                    try {
                        number = Build.VERSION.SDK_INT >= 33
                                ? manager.getPhoneNumber(subscriptionId)
                                : subscription.getNumber();
                    } catch (SecurityException ignored) {}
                }
                CharSequence carrier = subscription.getCarrierName();
                CharSequence displayName = subscription.getDisplayName();
                try {
                    cards.put(new JSONObject()
                            .put("subscription_id", subscriptionId)
                            .put("slot_index", subscription.getSimSlotIndex())
                            .put("carrier_name", clean(carrier == null ? "" : carrier.toString()))
                            .put("display_name", clean(displayName == null ? "" : displayName.toString()))
                            .put("number", clean(number))
                            .put("icc_id", clean(subscription.getIccId())));
                } catch (JSONException ignored) {}
            }
        } catch (SecurityException ignored) {}
        // 手动配置兜底：如果自动检测不到该槽位，或检测到的号码为空，
        // 用用户手动填的号码补齐/填补（TCL 功能机 SubscriptionManager 经常返回空号码）。
        // slot_index 0 = SIM 1, 1 = SIM 2；subscription_id 用 slot 占位，iOS 仅做展示。
        try {
            String[] manualNumbers = { AppConfig.sim1Number(context), AppConfig.sim2Number(context) };
            for (int slot = 0; slot < 2; slot++) {
                String manualNumber = manualNumbers[slot];
                if (manualNumber.isEmpty()) continue;
                boolean filled = false;
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.getJSONObject(i);
                    if (card.optInt("slot_index", -1) == slot) {
                        // 槽位已检测到但号码为空：用手动号码填补
                        if (card.optString("number", "").isEmpty()) {
                            card.put("number", manualNumber);
                            card.put("manual", true);
                        }
                        filled = true;
                        break;
                    }
                }
                if (!filled) {
                    cards.put(new JSONObject()
                            .put("subscription_id", slot)
                            .put("slot_index", slot)
                            .put("carrier_name", "")
                            .put("display_name", "SIM " + (slot + 1))
                            .put("number", manualNumber)
                            .put("icc_id", "")
                            .put("manual", true));
                }
            }
        } catch (JSONException ignored) {}
        return cards;
    }
}
