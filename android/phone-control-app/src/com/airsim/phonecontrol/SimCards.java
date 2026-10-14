package com.airsim.phonecontrol;

import android.content.Context;
import android.os.Build;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 双卡读取与体检。
 *
 * 双卡失效的根因一直藏在「PhoneAccountHandle 长什么样、能不能反查回 subscription」这一步：
 * 多数国产机型（含 TCL T508N）既不在 handle id 里嵌 ICC ID，也不暴露号码，导致靠字符串
 * 匹配 SIM 的老逻辑全部落空。这里把每一张卡、每一个 handle、以及反查是否成功全部摊开，
 * 既供 UI 显示，也供 /api/diagnostics 回传，让远端能直接看到现场。
 */
public final class SimCards {
    private SimCards() {}

    public static final class Card {
        public final int slot;
        public final int subscriptionId;
        public final String displayName;
        public final String carrierName;
        public final String number;
        public final String handleId;
        public final boolean reverseLookupOk;

        Card(int slot, int subscriptionId, String displayName, String carrierName,
             String number, String handleId, boolean reverseLookupOk) {
            this.slot = slot;
            this.subscriptionId = subscriptionId;
            this.displayName = displayName;
            this.carrierName = carrierName;
            this.number = number;
            this.handleId = handleId;
            this.reverseLookupOk = reverseLookupOk;
        }

        public String title() {
            String name = displayName != null && !displayName.isEmpty() ? displayName : "SIM " + (slot + 1);
            return "卡" + (slot + 1) + " · " + name;
        }
    }

    /** 列出当前可用的 SIM 卡，按槽位排序。任何权限异常都降级为空列表而不是崩溃。 */
    public static List<Card> activeCards(Context context) {
        List<Card> cards = new ArrayList<>();
        SubscriptionManager subscriptions = null;
        try {
            subscriptions = context.getSystemService(SubscriptionManager.class);
        } catch (Throwable ignored) {}
        if (subscriptions == null) return cards;

        List<SubscriptionInfo> infos = null;
        try {
            infos = subscriptions.getActiveSubscriptionInfoList();
        } catch (SecurityException error) {
            BridgeLog.error("sim_cards_permission_denied", error);
            return cards;
        } catch (Throwable error) {
            BridgeLog.error("sim_cards_read_failed", error);
            return cards;
        }
        if (infos == null || infos.isEmpty()) return cards;

        TelephonyManager telephony = null;
        try {
            telephony = context.getSystemService(TelephonyManager.class);
        } catch (Throwable ignored) {}

        List<PhoneAccountHandle> handles = callCapableHandles(context);

        for (SubscriptionInfo info : infos) {
            if (info == null) continue;
            int slot = info.getSimSlotIndex();
            int subId = info.getSubscriptionId();
            CharSequence display = null;
            CharSequence carrier = null;
            String number = "";
            try { display = info.getDisplayName(); } catch (Throwable ignored) {}
            try { carrier = info.getCarrierName(); } catch (Throwable ignored) {}
            try { number = safeNumber(info); } catch (Throwable ignored) {}

            // 找到这张卡对应的 PhoneAccountHandle，并当场验证反查是否成立
            String handleId = "";
            boolean reverseOk = false;
            PhoneAccountHandle matched = matchHandle(handles, telephony, subId);
            if (matched != null) {
                try { handleId = String.valueOf(matched.getId()); } catch (Throwable ignored) {}
                reverseOk = reverseLookup(telephony, matched, subId);
            }
            cards.add(new Card(
                    slot,
                    subId,
                    display == null ? "" : display.toString(),
                    carrier == null ? "" : carrier.toString(),
                    number,
                    handleId,
                    reverseOk));
        }
        return cards;
    }

    private static String safeNumber(SubscriptionInfo info) {
        try {
            String value = info.getNumber();
            return value == null ? "" : value;
        } catch (SecurityException error) {
            return "";
        }
    }

    private static List<PhoneAccountHandle> callCapableHandles(Context context) {
        List<PhoneAccountHandle> handles = new ArrayList<>();
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom == null) return handles;
            List<PhoneAccountHandle> capable = telecom.getCallCapablePhoneAccounts();
            if (capable != null) handles.addAll(capable);
        } catch (Throwable error) {
            BridgeLog.error("sim_cards_handles_failed", error);
        }
        return handles;
    }

    private static PhoneAccountHandle matchHandle(List<PhoneAccountHandle> handles,
                                                  TelephonyManager telephony, int subId) {
        if (telephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (PhoneAccountHandle handle : handles) {
                if (reverseLookup(telephony, handle, subId)) return handle;
            }
        }
        return null;
    }

    /**
     * 反查校验：handle 能否映射回目标 subscription。
     * 这是双卡选路在 API 30+ 上唯一可靠的路径，也是本应用真正依赖的机制。
     */
    public static boolean reverseLookup(TelephonyManager telephony, PhoneAccountHandle handle, int subId) {
        if (telephony == null || handle == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            return telephony.getSubscriptionId(handle) == subId;
        } catch (Throwable error) {
            return false;
        }
    }

    public static int subscriptionIdForSlot(Context context, int slot) {
        if (slot < 0) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        for (Card card : activeCards(context)) {
            if (card.slot == slot) return card.subscriptionId;
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /** 机器可读快照，供 /api/diagnostics 与日志回传。 */
    public static JSONArray snapshotJson(Context context) {
        JSONArray array = new JSONArray();
        for (Card card : activeCards(context)) {
            JSONObject item = new JSONObject();
            try {
                item.put("slot", card.slot);
                item.put("subscription_id", card.subscriptionId);
                item.put("display_name", card.displayName);
                item.put("carrier", card.carrierName);
                item.put("number", card.number);
                item.put("handle_id", card.handleId);
                item.put("reverse_lookup_ok", card.reverseLookupOk);
            } catch (Throwable ignored) {}
            array.put(item);
        }
        return array;
    }

    /** 人类可读摘要，用于 UI 与诊断文本。 */
    public static String describe(Context context) {
        List<Card> cards = activeCards(context);
        if (cards.isEmpty()) return "未检测到 SIM 卡（检查 READ_PHONE_STATE 权限与卡槽）";
        StringBuilder text = new StringBuilder();
        for (Card card : cards) {
            if (text.length() > 0) text.append("\n");
            text.append(card.title());
            if (!card.carrierName.isEmpty()) text.append(" / ").append(card.carrierName);
            if (!card.number.isEmpty()) text.append(" / ").append(card.number);
            text.append("\n  subId=").append(card.subscriptionId);
            text.append("  handle=").append(card.handleId.isEmpty() ? "未匹配" : card.handleId);
            // 反查失败意味着这台机器上双卡选路会退化，必须显性提示
            text.append("  反查=").append(card.reverseLookupOk ? "成功" : "失败");
        }
        return text.toString();
    }
}
