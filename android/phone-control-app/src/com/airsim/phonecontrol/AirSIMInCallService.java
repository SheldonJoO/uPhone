package com.airsim.phonecontrol;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class AirSIMInCallService extends InCallService {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Map<Call, Call.Callback> callbacks = new ConcurrentHashMap<>();
    private final Map<Call, String> callStates = new ConcurrentHashMap<>();
    /** 每次通话推断出的 [subscription_id, slot_index]；未知为 -1。去电在 onCallAdded 时写入。 */
    private final Map<Call, int[]> callSimCache = new ConcurrentHashMap<>();
    private final AtomicLong events = new AtomicLong();
    private final CallEventDeduplicator deduplicator = new CallEventDeduplicator();
    private LocalCallAudioController localAudio;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        if (localAudio == null) localAudio = new LocalCallAudioController(this);
        // 本地黑名单（iPhone 离线时的兜底；在线时由 iPhone 策略先行处理）。
        if ("incoming".equals(direction(call))) {
            String incomingNumber = number(call);
            if (CallBlocklistStore.isBlocked(this, incomingNumber)) {
                BridgeLog.info("blocklist_reject_local number=" + DebugRedactor.safeIdentifier(
                        CallBlocklistStore.normalize(incomingNumber)));
                try {
                    if (call.getState() == Call.STATE_RINGING) call.reject(false, null);
                    else call.disconnect();
                } catch (Exception error) {
                    BridgeLog.error("blocklist_reject_failed", error);
                }
                return;
            }
        }
        String callId = CallRepository.add(call);
        // 去电：消费拨号命令里暂存的 subscription_id（取一次后清空），供本次通话的事件推断使用。
        if ("outgoing".equals(direction(call))) {
            String pending = CallRepository.pendingDialSubscriptionId;
            CallRepository.pendingDialSubscriptionId = "";
            int subscriptionId = parseSubscriptionId(pending);
            if (subscriptionId >= 0) {
                // 手动补卡的 0/1 占位先解析为真实订阅，保证事件上报的是真实 subId 和正确卡槽。
                int resolved = SimCardManager.resolveSubscriptionId(this, pending);
                if (resolved != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    subscriptionId = resolved;
                }
                callSimCache.put(call, new int[]{subscriptionId, slotIndexForSubscriptionId(subscriptionId)});
            }
        }
        BridgeLog.info("telecom_call_added call_id=" + DebugRedactor.safeIdentifier(callId)
                + " direction=" + direction(call) + " state=" + TelecomStateMapper.toWireState(call.getState()));
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call changed, int state) { emit(changed, state); }
            @Override public void onDetailsChanged(Call changed, Call.Details details) { emit(changed, changed.getState()); }
        };
        callbacks.put(call, callback);
        call.registerCallback(callback);
        AgentWatchdogService.start(this);
        emit(call, call.getState());
        BridgeNotification.showCallFallback(this, callId, direction(call), AppConfig.mode(this));
    }

    @Override
    public void onCallRemoved(Call call) {
        String callId = CallRepository.id(call);
        BridgeLog.info("telecom_call_removed call_id=" + DebugRedactor.safeIdentifier(callId)
                + " reason=" + disconnectReason(call));
        int[] sim = inferSim(call, direction(call));
        emit(callId, direction(call), "ended", number(call), sim[0], sim[1], disconnectReason(call));
        deduplicator.remove(callId);
        callStates.remove(call);
        callSimCache.remove(call);
        updateLocalAudioPolicy();
        Call.Callback callback = callbacks.remove(call);
        if (callback != null) call.unregisterCallback(callback);
        CallRepository.remove(call);
        BridgeNotification.clearCallFallback(this);
        super.onCallRemoved(call);
    }

    private void emit(Call call, int state) {
        String wireState = TelecomStateMapper.toWireState(state);
        callStates.put(call, wireState);
        updateLocalAudioPolicy();
        String callId = CallRepository.id(call);
        String callDirection = direction(call);
        String callNumber = number(call);
        BridgeLog.debug("telecom_state_changed call_id=" + DebugRedactor.safeIdentifier(callId)
                + " direction=" + callDirection + " state=" + wireState);
        if (!"unknown".equals(wireState) && deduplicator.shouldSend(callId, callDirection, wireState, callNumber)) {
            int[] sim = inferSim(call, callDirection);
            String reason = "ended".equals(wireState) ? disconnectReason(call) : "";
            emit(callId, callDirection, wireState, callNumber, sim[0], sim[1], reason);
        }
    }

    private void updateLocalAudioPolicy() {
        if (localAudio == null) return;
        String mode = AppConfig.mode(this);
        boolean shouldSilence = false;
        for (String state : callStates.values()) {
            if (TelecomStateMapper.shouldSilenceLocalOutput(mode, state)) {
                shouldSilence = true;
                break;
            }
        }
        localAudio.update(shouldSilence);
        updateMicrophoneMutePolicy(shouldSilence);
    }

    /**
     * üPhone 接管通话时（remote_silent），iPhone 是听筒/话筒，
     * 上行音频由 PhoneAudioBridge 经 VOICE_TX 数字注入，安卓本机
     * 麦克风必须静音，否则对端会同时听到安卓周围的环境音/回声。
     * 策略与本地扬声器静音保持一致。
     */
    private void updateMicrophoneMutePolicy(boolean shouldMute) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        // 接通(ACTIVE)瞬间系统会重置麦克风静音，必须每次都重施加，不能只在状态翻转时设一次。
        try {
            audioManager.setMicrophoneMute(shouldMute);
            BridgeLog.info("call_mic_mute target_muted=" + shouldMute);
        } catch (Exception error) {
            BridgeLog.error("call_mic_mute_failed target_muted=" + shouldMute, error);
        }
    }

    private void emit(String callId, String direction, String state, String number,
                      int subscriptionId, int slotIndex) {
        emit(callId, direction, state, number, subscriptionId, slotIndex, "");
    }

    private void emit(String callId, String direction, String state, String number,
                      int subscriptionId, int slotIndex, String reason) {
        String eventId = Long.toString(System.currentTimeMillis()) + "-" + events.incrementAndGet();
        String json = WireJson.callEvent(eventId, callId, direction, state, number, AppConfig.mode(this),
                subscriptionId, slotIndex, reason);
        network.execute(() -> {
            try {
                new AgentClient(this).sendEvent(json);
                BridgeLog.info("call_event_sent state=" + state + " call_id=" + DebugRedactor.safeIdentifier(callId));
            } catch (Exception error) {
                BridgeLog.error("call_event_failed state=" + state, error);
            }
        });
    }

    /**
     * 读取通话的断开原因并映射为线上 reason。
     * 取值：busy(对端占线) / cancelled / local / remote / missed / error；未知或未断开返回空串。
     * 这是让 iPhone 侧区分「对端占线」与普通结束、从而正确结束系统通话并停止忙音的关键信息。
     */
    private static String disconnectReason(Call call) {
        try {
            Call.Details details = call.getDetails();
            if (details == null) return "";
            DisconnectCause cause = details.getDisconnectCause();
            if (cause == null) return "";
            // getCode() 返回 int 断开码（DisconnectCause.BUSY 等），getReason() 返回 String 文案——这里要的是码。
            return mapDisconnectReason(cause.getCode());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String mapDisconnectReason(int code) {
        if (code == DisconnectCause.BUSY) return "busy";
        if (code == DisconnectCause.CANCELED) return "cancelled";
        if (code == DisconnectCause.LOCAL) return "local";
        if (code == DisconnectCause.REMOTE) return "remote";
        if (code == DisconnectCause.MISSED) return "missed";
        if (code == DisconnectCause.ERROR) return "error";
        return "";
    }

    /**
     * 推断通话所用的 subscription_id / slot_index（未知返回 -1）。
     * 去电优先用 onCallAdded 时暂存的拨号订阅；其余走 Telecom 账号句柄多策略匹配。
     * 权限缺失时全部吞掉异常，绝不抛。
     */
    /** 最近一次 SIM 归属推断的现场记录，供 /api/diagnostics 回传，便于远程核对双卡判得对不对。 */
    public static volatile String lastSimInference = "尚未发生通话";

    private int[] inferSim(Call call, String direction) {
        int[] cached = callSimCache.get(call);
        int[] result = cached != null ? cached : inferSimFromAccountHandle(call);
        String handle = "";
        try {
            Call.Details details = call.getDetails();
            if (details != null && details.getAccountHandle() != null) {
                handle = String.valueOf(details.getAccountHandle().getId());
            }
        } catch (Exception ignored) {}
        lastSimInference = (direction == null ? "call" : direction)
                + " subId=" + result[0] + " slot=" + result[1] + " handle=" + handle;
        BridgeLog.info("call_sim_inferred " + lastSimInference);
        return result;
    }

    /** 来电（及无暂存订阅的去电）：从 Call 的账号句柄映射订阅。 */
    private int[] inferSimFromAccountHandle(Call call) {
        try {
            Call.Details details = call.getDetails();
            PhoneAccountHandle handle = details == null ? null : details.getAccountHandle();
            if (handle == null) return new int[]{-1, -1};
            SubscriptionManager manager = getSystemService(SubscriptionManager.class);
            if (manager == null) return new int[]{-1, -1};
            List<SubscriptionInfo> subscriptions;
            try {
                subscriptions = manager.getActiveSubscriptionInfoList();
            } catch (SecurityException noPermission) {
                return new int[]{-1, -1};
            }
            if (subscriptions == null) return new int[]{-1, -1};
            // 策略0（双卡选路关键，API 30+）：由框架直接反查 handle 对应的 subscription id，
            // 与活动订阅比对。TCL / 多数 OEM ROM 不把 ICC ID 嵌进 handle id、也不暴露号码，
            // 这条是让来电归属到正确 SIM 的唯一可靠路径。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    TelephonyManager tel = getSystemService(TelephonyManager.class);
                    if (tel != null) {
                        int sub = tel.getSubscriptionId(handle);
                        if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                            for (SubscriptionInfo info : subscriptions) {
                                if (info.getSubscriptionId() == sub) {
                                    return new int[]{info.getSubscriptionId(), info.getSimSlotIndex()};
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            // 策略①：句柄 ID 对 ICC ID
            String handleId = handle.getId();
            if (handleId != null) {
                for (SubscriptionInfo info : subscriptions) {
                    String iccId = safeIccId(info);
                    if (!iccId.isEmpty() && handleId.contains(iccId)) {
                        return new int[]{info.getSubscriptionId(), info.getSimSlotIndex()};
                    }
                }
            }
            // 策略②：PhoneAccount 地址号码对 SIM 号码（取数字后缀比对）
            String accountDigits = onlyDigits(phoneAccountAddress(handle));
            if (!accountDigits.isEmpty()) {
                for (SubscriptionInfo info : subscriptions) {
                    String simDigits = onlyDigits(simNumber(manager, info));
                    if (!simDigits.isEmpty()
                            && (simDigits.endsWith(accountDigits) || accountDigits.endsWith(simDigits))) {
                        return new int[]{info.getSubscriptionId(), info.getSimSlotIndex()};
                    }
                }
            }
        } catch (Exception error) {
            BridgeLog.error("call_sim_infer_failed", error);
        }
        return new int[]{-1, -1};
    }

    /** 由 subscription_id 反查 slot_index；查不到返回 -1。 */
    private int slotIndexForSubscriptionId(int subscriptionId) {
        if (subscriptionId < 0) return -1;
        try {
            SubscriptionManager manager = getSystemService(SubscriptionManager.class);
            if (manager == null) return -1;
            List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
            if (subscriptions == null) return -1;
            for (SubscriptionInfo info : subscriptions) {
                if (info.getSubscriptionId() == subscriptionId) return info.getSimSlotIndex();
            }
        } catch (Exception error) {
            BridgeLog.error("call_slot_lookup_failed", error);
        }
        return -1;
    }

    private String phoneAccountAddress(PhoneAccountHandle handle) {
        try {
            TelecomManager telecom = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) return "";
            PhoneAccount account = telecom.getPhoneAccount(handle);
            if (account == null || account.getAddress() == null) return "";
            String part = account.getAddress().getSchemeSpecificPart();
            return part == null ? "" : part;
        } catch (Exception error) {
            return "";
        }
    }

    private static String safeIccId(SubscriptionInfo info) {
        try {
            String iccId = info.getIccId();
            return iccId == null ? "" : iccId;
        } catch (Exception error) {
            return "";
        }
    }

    private static String simNumber(SubscriptionManager manager, SubscriptionInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                String number = manager.getPhoneNumber(info.getSubscriptionId());
                return number == null ? "" : number;
            }
            CharSequence number = info.getNumber();
            return number == null ? "" : number.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private static String onlyDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static int parseSubscriptionId(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static String direction(Call call) {
        if (call.getState() == Call.STATE_RINGING) return "incoming";
        Call.Details details = call.getDetails();
        return details != null && details.getCallDirection() == Call.Details.DIRECTION_INCOMING ? "incoming" : "outgoing";
    }

    private static String number(Call call) {
        Call.Details details = call.getDetails();
        Uri handle = details == null ? null : details.getHandle();
        return handle == null || handle.getSchemeSpecificPart() == null ? "" : handle.getSchemeSpecificPart();
    }

    @Override public void onDestroy() {
        BridgeLog.info("incall_service_destroyed");
        if (localAudio != null) localAudio.update(false);
        updateMicrophoneMutePolicy(false);
        network.shutdownNow();
        super.onDestroy();
    }
}
