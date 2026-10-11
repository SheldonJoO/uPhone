package com.airsim.phonecontrol;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.List;

/** Small helpers for dual-SIM subscription lookups. */
final class SimCardManager {
    private SimCardManager() {}

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
