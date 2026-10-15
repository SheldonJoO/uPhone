package com.airsim.phonecontrol;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 双卡选路依赖的 {@code ROLE_CALL_REDIRECTION} 角色。
 *
 * 只有应用持有该角色，系统才会在发起通话时调用 {@link SimCallRedirectionService}，
 * 用 {@code redirectCall} 强制把去电重定向到指定 SIM——这是 TCL 等国产 ROM 上让双卡选路真正生效的关键。
 * 未持有时系统不会调用该服务，退化为普通 placeCall 逻辑（无回归）。
 */
public final class CallRedirectionPolicy {
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

    /** 发起角色申请（系统会弹确认框）。必须在 Activity 上下文调用。返回是否成功发起。 */
    public static boolean requestRole(Activity activity) {
        try {
            RoleManager rm = activity.getSystemService(RoleManager.class);
            if (rm == null) return false;
            if (rm.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) return true;
            Executor executor = activity.getMainExecutor();
            rm.addRoleHolderAsUser(
                    RoleManager.ROLE_CALL_REDIRECTION,
                    Process.myUserHandle(),
                    0,
                    executor,
                    granted -> BridgeLog.info("call_redirection_role_granted=" + granted));
            return true;
        } catch (Throwable error) {
            BridgeLog.error("call_redirection_role_request_failed", error);
            return false;
        }
    }
}
