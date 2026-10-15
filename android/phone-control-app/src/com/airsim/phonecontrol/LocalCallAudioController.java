package com.airsim.phonecontrol;

import android.content.Context;
import android.media.AudioManager;

/**
 * 让本机在远程通话期间完全静默（值守机不能漏音）。
 *
 * 之前的实现有一个致命缺陷：{@code update} 用 {@code shouldSilence == silencing} 做 early-return，
 * 只在状态「从静音切到非静音 / 反之」时施加一次。但通话从拨号态切到 ACTIVE（接通）的瞬间，
 * 系统音频策略会重置 STREAM_VOICE_CALL 的音量/静音，于是只施加一次的静音在接通那一刻被冲掉，
 * 听筒重新漏音——这就是「用 iPhone 通话时安卓端听筒还是有声音」的根因之一。
 *
 * 修复：只要 shouldSilence=true 就每次重新施加（不跳过），确保任何状态切换后静音都还在。
 * 同时把静音做成多层兜底：Shizuku 特权 cmd audio adj-mute 0（最可靠）→ setStreamMute
 * → adjustStreamVolume(ADJUST_MUTE) → setStreamVolume(0) → 关扬声器 → 压低 RING。
 */
final class LocalCallAudioController {
    private final Context appContext;
    private final ShizukuBridgeManager privilegedBridge;
    private boolean silencing;

    LocalCallAudioController(Context context) {
        appContext = context.getApplicationContext();
        privilegedBridge = ShizukuBridgeManager.get(context);
    }

    synchronized void update(boolean shouldSilence) {
        // 仅在「本来就没在静音、且这次也不静音」时跳过，避免无意义的重复操作；
        // 其余情况（尤其是 shouldSilence=true）一律重新施加，覆盖系统重置。
        if (!shouldSilence && !silencing) return;
        boolean changed = silencing != shouldSilence;
        silencing = shouldSilence;
        if (changed) {
            BridgeLog.info("local_call_output_requested target_silent=" + shouldSilence);
        }
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            BridgeLog.info("local_call_output_no_audio_manager");
            return;
        }
        if (shouldSilence) applySilence(am);
        else applyUnsilence(am);
    }

    private void applySilence(AudioManager am) {
        // 1) Shizuku 特权桥：以 shell 身份执行 cmd audio adj-mute 0，系统级静音，最可靠。
        //    失败也不放弃，继续走下面的普通 API 兜底。
        try {
            privilegedBridge.setLocalOutputMuted(true);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_privileged_failed", error);
        }
        // 2) 普通 API 多重兜底（部分 ROM 需要特权身份才生效，普通 API 失败则忽略）。
        try {
            am.setStreamMute(AudioManager.STREAM_VOICE_CALL, true);
        } catch (Exception ignored) {}
        try {
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0);
        } catch (Exception ignored) {}
        try {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0);
        } catch (Exception ignored) {}
        try {
            // 确保不走扬声器，避免免提外放漏音。
            am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}
        try {
            // 部分 ROM 用铃声音频承载来电预览，一并压低。
            am.setStreamMute(AudioManager.STREAM_RING, true);
        } catch (Exception ignored) {}
    }

    private void applyUnsilence(AudioManager am) {
        try {
            privilegedBridge.setLocalOutputMuted(false);
        } catch (Exception ignored) {}
        try {
            am.setStreamMute(AudioManager.STREAM_VOICE_CALL, false);
        } catch (Exception ignored) {}
        try {
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {}
        try {
            am.setStreamMute(AudioManager.STREAM_RING, false);
        } catch (Exception ignored) {}
    }

    /** 当前静音现场，供 /api/diagnostics 回传，远程即可判断 TCL 上静音是否真的生效。 */
    synchronized String diagnostics() {
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "audio_manager=unavailable silencing=" + silencing;
        int vol = -1;
        try { vol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL); } catch (Exception ignored) {}
        boolean speaker = false;
        try { speaker = am.isSpeakerphoneOn(); } catch (Exception ignored) {}
        boolean micMute = false;
        try { micMute = am.isMicrophoneMute(); } catch (Exception ignored) {}
        return "silencing=" + silencing
                + " voice_call_volume=" + vol
                + " speakerphone=" + speaker
                + " microphone_mute=" + micMute
                + " shizuku=" + privilegedBridge.status();
    }
}
