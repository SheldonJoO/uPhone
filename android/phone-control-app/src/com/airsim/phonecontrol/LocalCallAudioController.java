package com.airsim.phonecontrol;

import android.content.Context;
import android.media.AudioManager;

final class LocalCallAudioController {
    private final Context appContext;
    private final ShizukuBridgeManager privilegedBridge;
    private boolean silencing;

    LocalCallAudioController(Context context) {
        appContext = context.getApplicationContext();
        privilegedBridge = ShizukuBridgeManager.get(context);
    }

    synchronized void update(boolean shouldSilence) {
        if (shouldSilence == silencing) return;
        silencing = shouldSilence;
        // 优先走 Shizuku 特权桥（能真正静音本机语音通话输出流）。
        try {
            privilegedBridge.setLocalOutputMuted(shouldSilence);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_privileged_failed", error);
        }
        // 兜底：直接通过 AudioManager 静音语音通话流。Shizuku 未配置或部分 ROM 上，
        // 特权桥不生效时，普通 API 仍可能静音本机听筒/扬声器（需相应权限，失败则忽略）。
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamMute(AudioManager.STREAM_VOICE_CALL, shouldSilence);
            }
        } catch (Exception error) {
            BridgeLog.error("local_call_output_fallback_failed", error);
        }
        BridgeLog.info("local_call_output_requested target_silent=" + shouldSilence);
    }
}
