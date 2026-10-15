package com.airsim.phonecontrol;

import android.content.Context;
import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * 双卡选路的可靠兜底。
 *
 * 普通 {@code placeCall(EXTRA_PHONE_ACCOUNT_HANDLE)} 在部分国产 ROM（含 TCL T508N）上会被忽略，
 * 导致去电永远走默认卡。CallRedirectionService 在系统发起通话的更早阶段被调用，
 * 用 {@code redirectCall(uri, handle, false)} 把去电重定向到指定 SIM 的 PhoneAccountHandle，
 * 比 {@code EXTRA_PHONE_ACCOUNT_HANDLE} 更可能被系统采纳。
 *
 * 仅在应用持有 {@code ROLE_CALL_REDIRECTION} 时系统才会调用本服务；未持有该角色时本服务不会被触发，
 * 退化为原有 placeCall 逻辑（无回归）。因此是否授予该角色只决定「双卡选路是否更可靠」，
 * 不影响其他功能。
 *
 * API 兼容：目标机 TCL T508N 为 Android 11（API 30），CI 编译所依赖的 android.jar 亦为该体系，
 * 因此实现 API 29–33 的 {@link #onPlaceCall} 抽象回调。API 34+ 引入的
 * {@code onCallCreated/onCallRedirected}（及 CallRedirectionDetails 等）不在本编译目标内；
 * 34+ 设备上若框架不再回调 onPlaceCall，届时再补充 34+ 专路径，不影响当前 Android 11 目标机。
 */
public final class SimCallRedirectionService extends CallRedirectionService {

    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle initialPhoneAccount,
                            boolean allowInteractiveResponse) {
        PhoneAccountHandle target = resolveTargetHandle();
        if (target != null && (initialPhoneAccount == null || !target.equals(initialPhoneAccount))) {
            BridgeLog.info("call_redirection_redirect subscription_id="
                    + CallRepository.pendingDialSubscriptionId);
            redirectCall(handle, target, false);
        } else {
            BridgeLog.info("call_redirection_unmodified");
            placeCallUnmodified();
        }
    }

    private PhoneAccountHandle resolveTargetHandle() {
        String pending = CallRepository.pendingDialSubscriptionId;
        if (pending == null || pending.isEmpty()) return null;
        TelecomManager telecom = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (telecom == null) return null;
        return CallRepository.findPhoneAccountForSubscription(this, telecom, pending);
    }
}
