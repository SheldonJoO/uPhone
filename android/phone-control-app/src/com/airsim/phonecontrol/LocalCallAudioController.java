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
        BridgeLog.info("local_call_output_requested target_silent=" + shouldSilence);
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            BridgeLog.error("local_call_output_no_audio_manager");
            return;
        }
        // 1) Shizuku 特权桥：在特权身份下真正静音本机语音通话输出流，最可靠。
        try {
            privilegedBridge.setLocalOutputMuted(shouldSilence);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_privileged_failed", error);
        }
        // 2) 普通 API 多重兜底：部分 ROM 需要特权身份才生效，失败则忽略，不阻断通话。
        try {
            am.setStreamMute(AudioManager.STREAM_VOICE_CALL, shouldSilence);
            BridgeLog.info("local_call_output_stream_mute requested=" + shouldSilence);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_stream_mute_failed", error);
        }
        try {
            // 直接把通话流音量压到 0，覆盖 setStreamMute 被忽略的 ROM。
            int vol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, shouldSilence ? 0 : vol, 0);
            BridgeLog.info("local_call_output_stream_volume requested_silent=" + shouldSilence);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_stream_volume_failed", error);
        }
        try {
            // 确保不走扬声器，避免免提外放漏音。
            am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}
        try {
            // 部分 ROM 用铃声音频承载来电预览，一并压低。
            am.setStreamMute(AudioManager.STREAM_RING, shouldSilence);
        } catch (Exception ignored) {}
    }
}
