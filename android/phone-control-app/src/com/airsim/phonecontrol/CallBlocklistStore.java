package com.airsim.phonecontrol;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 本地来电黑名单：iPhone 通过 /api/policy/blocklist 同步，Android 在
 * AirSIMInCallService.onCallAdded 中直接拒接（iPhone 离线时仍生效）。
 *
 * 只做黑名单（明确拉黑的号码）；白名单/静默时段仍由 iPhone 侧执行，
 * 避免离线时误伤正常来电。
 */
public final class CallBlocklistStore {
    private static final String PREFS = "uphone_call_blocklist";
    private static final String KEY_NUMBERS = "numbers";
    private static final String KEY_UPDATED_AT = "updated_at";

    private CallBlocklistStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 用归一化后的号码集合替换本地黑名单。 */
    public static void replace(Context context, Set<String> normalizedNumbers, long updatedAt) {
        prefs(context).edit()
                .putStringSet(KEY_NUMBERS, new HashSet<>(normalizedNumbers))
                .putLong(KEY_UPDATED_AT, updatedAt)
                .apply();
        BridgeLog.info("blocklist_updated count=" + normalizedNumbers.size());
    }

    /** 从 JSON body 更新：{"numbers":[...],"updated_at":123} */
    public static boolean replaceFromJson(Context context, String body) {
        try {
            JSONObject input = new JSONObject(body == null ? "{}" : body);
            JSONArray array = input.optJSONArray("numbers");
            Set<String> numbers = new HashSet<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String normalized = normalize(array.optString(i, ""));
                    if (!normalized.isEmpty()) numbers.add(normalized);
                }
            }
            replace(context, numbers, input.optLong("updated_at", System.currentTimeMillis() / 1000));
            return true;
        } catch (Exception error) {
            BridgeLog.error("blocklist_update_failed", error);
            return false;
        }
    }

    /** 来电号码是否在黑名单中（归一化后比较）。 */
    public static boolean isBlocked(Context context, String number) {
        String normalized = normalize(number);
        if (normalized.isEmpty()) return false;
        Set<String> stored = prefs(context).getStringSet(KEY_NUMBERS, null);
        return stored != null && stored.contains(normalized);
    }

    public static int count(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_NUMBERS, null);
        return stored == null ? 0 : stored.size();
    }

    /**
     * 归一化：去 +86/0086/86 前缀、去分隔符。保持与 iOS CallPolicyStore.normalizedNumber
     * 一致的规则，确保两端匹配。
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0086")) return digits.substring(4);
        if (digits.startsWith("86") && digits.length() == 13) return digits.substring(2);
        if (raw.trim().startsWith("+86") && digits.length() == 13) return digits.substring(2);
        return digits;
    }
}
