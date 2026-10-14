package com.airsim.phonecontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionManager;

/**
 * 一键值守的持久化状态。
 *
 * 值守机的使用方式被刻意收敛成一个开关：打开即值守（Agent 常驻、来电/短信转发、
 * 通话全程静默），关闭即停。默认卡决定「值守时用哪张 SIM 拨出」，是双卡体验的核心。
 */
public final class DutyStore {
    private static final String PREFS = "uphone_duty";
    private static final String KEY_DUTY_ON = "duty_on";
    private static final String KEY_DUTY_SLOT = "duty_slot";

    /** 未指定值守卡：拨号时跟随系统默认语音卡。 */
    public static final int SLOT_FOLLOW_SYSTEM = -1;

    private DutyStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDutyOn(Context context) {
        SharedPreferences prefs = prefs(context);
        // 升级迁移：旧版本没有值守开关，凡已配对的机器一律视为已开启，
        // 避免用户升级后值守被静默关掉、iPhone 端突然失联。
        if (!prefs.contains(KEY_DUTY_ON)) {
            boolean migrated;
            try {
                migrated = new StandaloneAgentStore(context).configured();
            } catch (Throwable error) {
                migrated = false;
            }
            prefs.edit().putBoolean(KEY_DUTY_ON, migrated).apply();
            return migrated;
        }
        return prefs.getBoolean(KEY_DUTY_ON, false);
    }

    public static void setDutyOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_DUTY_ON, on).apply();
    }

    /** 值守卡所在的 SIM 槽位；{@link #SLOT_FOLLOW_SYSTEM} 表示跟随系统默认。 */
    public static int getDutySlot(Context context) {
        return prefs(context).getInt(KEY_DUTY_SLOT, SLOT_FOLLOW_SYSTEM);
    }

    public static void setDutySlot(Context context, int slot) {
        prefs(context).edit().putInt(KEY_DUTY_SLOT, slot).apply();
    }

    /** 值守卡的 subscription id；未指定时返回 INVALID_SUBSCRIPTION_ID。 */
    public static int getDutySubscriptionId(Context context) {
        int slot = getDutySlot(context);
        if (slot == SLOT_FOLLOW_SYSTEM) return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        return SimCards.subscriptionIdForSlot(context, slot);
    }
}
