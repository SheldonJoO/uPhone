package com.airsim.phonecontrol;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 电池优化豁免：TCL 等 OEM 的 Doze / 省电策略会限制甚至杀掉值守机的前台服务，
 * 导致通话 / 短信中断——这是"不稳定"的一个具体且可修的根因。
 * 引导用户把本 App 加入电池优化白名单，让内置 Agent 在后台长期存活。
 */
final class BatteryOptimizationPolicy {
    private BatteryOptimizationPolicy() {}

    /** 是否已在电池优化白名单（忽略电池优化）。 */
    static boolean isExempt(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception error) {
            return false;
        }
    }

    /** 弹出系统对话框，引导用户允许"忽略电池优化"。需在 Activity 上下文调用。 */
    static void requestExemption(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception error) {
            // 部分 ROM 没有该 Action，退回电池优化设置列表
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
                BridgeLog.error("battery_optimization_request_failed", ignored);
            }
        }
    }
}
