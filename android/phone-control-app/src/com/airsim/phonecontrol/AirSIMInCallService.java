package com.airsim.phonecontrol;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.InCallService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class AirSIMInCallService extends InCallService {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Map<Call, Call.Callback> callbacks = new ConcurrentHashMap<>();
	private final Map<Call, String> callStates = new ConcurrentHashMap<>();
    private final AtomicLong events = new AtomicLong();
    private final CallEventDeduplicator deduplicator = new CallEventDeduplicator();
	private LocalCallAudioController localAudio;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
		if (localAudio == null) localAudio = new LocalCallAudioController(this);
        String callId = CallRepository.add(call);
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
        BridgeLog.info("telecom_call_removed call_id=" + DebugRedactor.safeIdentifier(callId));
        emit(callId, direction(call), "ended", number(call));
		deduplicator.remove(callId);
		callStates.remove(call);
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
			emit(callId, callDirection, wireState, callNumber);
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
		boolean currentlyMuted;
		try {
			currentlyMuted = audioManager.isMicrophoneMute();
		} catch (Exception error) {
			BridgeLog.error("call_mic_mute_query_failed", error);
			return;
		}
		if (currentlyMuted == shouldMute) return;
		try {
			audioManager.setMicrophoneMute(shouldMute);
			BridgeLog.info("call_mic_mute target_muted=" + shouldMute);
		} catch (Exception error) {
			BridgeLog.error("call_mic_mute_failed target_muted=" + shouldMute, error);
		}
	}

    private void emit(String callId, String direction, String state, String number) {
        String eventId = Long.toString(System.currentTimeMillis()) + "-" + events.incrementAndGet();
        String json = WireJson.callEvent(eventId, callId, direction, state, number, AppConfig.mode(this));
        network.execute(() -> {
            try {
                new AgentClient(this).sendEvent(json);
                BridgeLog.info("call_event_sent state=" + state + " call_id=" + DebugRedactor.safeIdentifier(callId));
            } catch (Exception error) {
                BridgeLog.error("call_event_failed state=" + state, error);
            }
        });
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
