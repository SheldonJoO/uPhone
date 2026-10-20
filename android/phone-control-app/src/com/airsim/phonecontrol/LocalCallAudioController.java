package com.airsim.phonecontrol;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 *
 * 进程级单例 + 多原因保持：静音不再只靠 AirSIMInCallService 一条路。云端通话的
 * 媒体会话（StandaloneCloudMediaManager）与 Agent 通话事件同样会 retain 静音，
 * 任一原因 active 即静默；InCallService 未绑定（默认拨号角色丢失 / 被系统回收）时
 * 云端通话也不再漏音。
 */
final class LocalCallAudioController {
    private static volatile LocalCallAudioController instance;

    /** 进程级单例：InCallService、云媒体会话、Agent 事件共用同一份静音状态。 */
    static LocalCallAudioController get(android.content.Context context) {
        LocalCallAudioController value = instance;
        if (value == null) {
            synchronized (LocalCallAudioController.class) {
                value = instance;
                if (value == null) instance = value = new LocalCallAudioController(context);
            }
        }
        return value;
    }

    private final android.content.Context appContext;
    private final ShizukuBridgeManager privilegedBridge;
    /** 当前要求静音的原因集合；任一存在即静默。 */
    private final java.util.Set<String> reasons = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean silencing;
    private AudioManager.OnModeChangedListener modeListener;
    /** OEM ROM 会在通话过程中异步重置音量/静音（不止模式切换那一刻），
     *  定时重施加兜底覆盖所有重置触发点。 */
    private final ScheduledExecutorService reassertWorker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reassertTask;

    LocalCallAudioController(android.content.Context context) {
        appContext = context.getApplicationContext();
        privilegedBridge = ShizukuBridgeManager.get(context);
    }

    /** InCallService 路径：通话状态集合非空即 retain，空即 release。 */
    synchronized void update(boolean shouldSilence) {
        if (shouldSilence) reasons.add("incall"); else reasons.remove("incall");
        apply();
    }

    /** Agent / 云媒体路径：通话进行期间保持静音。 */
    synchronized void retain(String reason) {
        if (reason == null || reason.isEmpty()) return;
        reasons.add(reason);
        apply();
    }

    synchronized void release(String reason) {
        if (reason == null || reason.isEmpty()) return;
        reasons.remove(reason);
        apply();
    }

    private synchronized void apply() {
        boolean shouldSilence = !reasons.isEmpty();
        // 仅在「本来就没在静音、且这次也不静音」时跳过，避免无意义的重复操作；
        // 其余情况（尤其是 shouldSilence=true）一律重新施加，覆盖系统重置。
        if (!shouldSilence && !silencing) return;
        boolean changed = silencing != shouldSilence;
        silencing = shouldSilence;
        if (changed) {
            BridgeLog.info("local_call_output_requested target_silent=" + shouldSilence
                    + " reasons=" + reasons);
        }
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            BridgeLog.info("local_call_output_no_audio_manager");
            return;
        }
        if (shouldSilence) {
            applySilence(am);
            ensureModeListener(am);
            startReassertLoop();
        } else {
            applyUnsilence(am);
            removeModeListener(am);
            stopReassertLoop();
        }
    }

    private void startReassertLoop() {
        if (reassertTask != null) return;
        reassertTask = reassertWorker.scheduleWithFixedDelay(() -> {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && silencing) applySilence(am);
        }, 300, 300, TimeUnit.MILLISECONDS);
    }

    private void stopReassertLoop() {
        ScheduledFuture<?> task = reassertTask;
        reassertTask = null;
        if (task != null) task.cancel(false);
    }

    /**
     * 系统在通话接通/音频模式切换时会重置 STREAM_VOICE_CALL 的音量与静音，
     * 且这个重置往往发生在 InCallService 的 onStateChanged 回调之后，
     * 导致「ACTIVE 时施加过一次」的静音被冲掉、听筒重新漏音。
     * 这里监听音频模式变化，一旦进入通话态就立刻重新施加静音，堵住漏音窗口。
     */
    private void ensureModeListener(AudioManager am) {
        if (modeListener != null) return;
        modeListener = new AudioManager.OnModeChangedListener() {
            @Override public void onModeChanged(int mode) {
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    AudioManager current = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                    if (current != null) applySilence(current);
                }
            }
        };
        try {
            am.addOnModeChangedListener(appContext.getMainExecutor(), modeListener);
        } catch (Exception error) {
            BridgeLog.error("local_call_output_modelistener_failed", error);
            modeListener = null;
        }
    }

    private void removeModeListener(AudioManager am) {
        if (modeListener != null) {
            try { am.removeOnModeChangedListener(modeListener); } catch (Exception ignored) {}
            modeListener = null;
        }
    }

    private void applySilence(AudioManager am) {
        // 0) 清理已选通信设备：连着蓝牙/耳机时通话音频会整体绕到那些设备，
        //    先把路由拉回默认（听筒），静音才有意义。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.clearCommunicationDevice(); } catch (Exception ignored) {}
        }
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
