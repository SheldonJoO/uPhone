package com.airsim.phonecontrol;

import android.content.Context;
import android.net.Uri;
import android.telecom.CallRedirectionDetails;
import android.telecom.CallRedirectionResult;
import android.telecom.CallRedirectionResultReceiver;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * 双卡选路的可靠兜底。
 *
 * 普通 {@code placeCall(EXTRA_PHONE_ACCOUNT_HANDLE)} 在部分国产 ROM（含 TCL T508N）上会被忽略，
 * 导致去电永远走默认卡。CallRedirectionService 在系统发起通话的更早阶段被调用，
 * 用 {@code redirectCall(uri, handle, false)} 把去电重定向到指定 SIM 的 PhoneAccountHandle，
 * 比 EXTRA_PHONE_ACCOUNT_HANDLE 更可能被系统采纳。
 *
 * 仅在应用持有 {@code ROLE_CALL_REDIRECTION} 时系统才会调用本服务；未持有该角色时本服务不会被触发，
 * 退化为原有 placeCall 逻辑（无回归）。因此是否授予该角色只决定「双卡选路是否更可靠」，不影响其他功能。
 *
 * API 兼容：29–33 走 {@link #onCallCreated}；34+ 走 {@link #onCallRedirected}（旧回调在 34+ 可能不再被框架调用）。
 */
public final class SimCallRedirectionService extends CallRedirectionService {

    @Override
    public void onCallCreated(CallRedirectionDetails details, CallRedirectionResultReceiver result) {
        PhoneAccountHandle handle = resolveTargetHandle();
        if (handle != null && details != null) {
            BridgeLog.info("call_redirection_redirect subscription_id=" + CallRepository.pendingDialSubscriptionId);
            result.redirectCall(details.getHandle(), handle, false);
        } else {
            BridgeLog.info("call_redirection_unmodified");
            result.placeCallUnmodified();
        }
    }

    @Override
    public void onCallRedirected(CallRedirectionDetails details, CallRedirectionResult result) {
        PhoneAccountHandle handle = resolveTargetHandle();
        if (handle != null && details != null) {
            BridgeLog.info("call_redirection_redirect subscription_id=" + CallRepository.pendingDialSubscriptionId);
            result.redirectCall(details.getHandle(), handle, false);
        } else {
            BridgeLog.info("call_redirection_unmodified");
            result.placeCallUnmodified();
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
