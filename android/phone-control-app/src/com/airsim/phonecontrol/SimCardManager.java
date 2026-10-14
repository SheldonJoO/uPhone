package com.airsim.phonecontrol;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.List;

/** Small helpers for dual-SIM subscription lookups. */
final class SimCardManager {
    private SimCardManager() {}

    /**
     * 把 iOS 传来的 subscription_id 解析为本机真实活跃订阅 ID。
     * 手动补卡场景下上报的 0/1 是卡槽占位符（见 AndroidAgentIdentity.simCards），
     * 按卡槽反查真实订阅；找不到返回 INVALID_SUBSCRIPTION_ID。
     */
    static int resolveSubscriptionId(Context context, String requested) {
        if (requested == null || requested.isEmpty()) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int subId;
        try {
            subId = Integer.parseInt(requested.trim());
        } catch (NumberFormatException notNumber) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            if (manager == null) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
            if (infos == null) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            for (SubscriptionInfo info : infos) {
                if (info.getSubscriptionId() == subId) return subId;
            }
            if (subId == 0 || subId == 1) {
                for (SubscriptionInfo info : infos) {
                    if (info.getSimSlotIndex() == subId) {
                        BridgeLog.info("sim_slot_placeholder_resolved slot=" + subId
                                + " subId=" + info.getSubscriptionId());
                        return info.getSubscriptionId();
                    }
                }
            }
        } catch (SecurityException noPermission) {}
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /** Returns the SIM slot index for a subscription ID, or -1 if unknown. */
    static int slotIndexFor(Context context, int subscriptionId) {
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            if (manager == null) return -1;
            List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
            if (infos == null) return -1;
            for (SubscriptionInfo info : infos) {
                if (info.getSubscriptionId() == subscriptionId) {
                    return info.getSimSlotIndex();
                }
            }
        } catch (SecurityException ignored) {}
        return -1;
    }

    /** Returns the carrier/display label for a subscription ID, or "" if unknown. */
    static String labelFor(Context context, int subscriptionId) {
        try {
            SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
            if (manager == null) return "";
            List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
            if (infos == null) return "";
            for (SubscriptionInfo info : infos) {
                if (info.getSubscriptionId() == subscriptionId) {
                    CharSequence carrier = info.getCarrierName();
                    if (carrier != null && carrier.length() > 0) return carrier.toString().trim();
                    CharSequence display = info.getDisplayName();
                    if (display != null && display.length() > 0) return display.toString().trim();
                    int slot = info.getSimSlotIndex();
                    return slot >= 0 ? "SIM " + (slot + 1) : "";
                }
            }
        } catch (SecurityException ignored) {}
        return "";
    }
}
