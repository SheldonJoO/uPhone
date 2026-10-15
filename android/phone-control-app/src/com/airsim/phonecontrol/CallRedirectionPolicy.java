package com.airsim.phonecontrol;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;

/**
 * 双卡选路依赖的 {@code ROLE_CALL_REDIRECTION} 角色。
 *
 * 只有应用持有该角色，系统才会在发起通话时调用 {@link SimCallRedirectionService}，
 * 用 {@code redirectCall} 强制把去电重定向到指定 SIM——这是 TCL 等国产 ROM 上让双卡选路真正生效的关键。
 * 未持有时系统不会调用该服务，退化为普通 placeCall 逻辑（无回归）。
 */
public final class CallRedirectionPolicy {
    /** startActivityForResult 请求码，供 {@code Activity.onActivityResult} 识别角色申请结果。 */
    public static final int REQUEST_CODE = 0x51ED;

    private CallRedirectionPolicy() {}

    public static boolean isRoleHeld(Context context) {
        try {
            RoleManager rm = context.getSystemService(RoleManager.class);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否有可能申请该角色（部分 OEM 会隐藏此角色，返回 false 时需手动在系统设置里授予）。 */
    public static boolean isRequestable(Context context) {
        try {
            RoleManager rm = context.getSystemService(RoleManager.class);
            if (rm == null) return false;
            return rm.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 发起角色申请：弹出系统确认框（与「设为默认电话应用」同一套 {@code createRequestRoleIntent} 流程）。
     *
     * @return true 表示已成功弹出系统授权框（含「本就已持有」的情况）；false 表示该角色当前设备不可申请。
     *         用户的选择结果经 {@code Activity.onActivityResult(REQUEST_CODE)} 回调；
     *         也可在下次回到设置页时用 {@link #isRoleHeld(Context)} 复查。
     */
    public static boolean requestRole(Activity activity) {
        try {
            RoleManager rm = activity.getSystemService(RoleManager.class);
            if (rm == null) return false;
            if (rm.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) return true;
            Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION);
            if (intent == null) {
                BridgeLog.info("call_redirection_role_not_requestable");
                return false;
            }
            activity.startActivityForResult(intent, REQUEST_CODE);
            return true;
        } catch (Throwable error) {
            BridgeLog.error("call_redirection_role_request_failed", error);
            return false;
        }
    }
}
