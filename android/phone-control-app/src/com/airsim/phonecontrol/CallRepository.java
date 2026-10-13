package com.airsim.phonecontrol;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.content.Context;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CallRepository {
    private static final Map<String, Call> CALLS = new ConcurrentHashMap<>();
    private static final Map<Call, String> IDS = new ConcurrentHashMap<>();

    private CallRepository() {}

    public static String add(Call call) {
        String id = UUID.randomUUID().toString();
        IDS.put(call, id);
        CALLS.put(id, call);
        return id;
    }

    public static String id(Call call) {
        return IDS.getOrDefault(call, "");
    }

    public static void remove(Call call) {
        String id = IDS.remove(call);
        if (id != null) CALLS.remove(id);
    }

    public static String firstId() {
        return idForAction("end");
    }

    public static String idForAction(String action) {
        int preferred = ("answer".equals(action) || "reject".equals(action))
                ? Call.STATE_RINGING : Call.STATE_ACTIVE;
        for (Map.Entry<String, Call> entry : CALLS.entrySet()) {
            if (entry.getValue().getState() == preferred) return entry.getKey();
        }
        for (Map.Entry<String, Call> entry : CALLS.entrySet()) {
            int state = entry.getValue().getState();
            if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) return entry.getKey();
        }
        return "";
    }

    public static void disconnectAll() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Call call : CALLS.values()) {
                try { call.disconnect(); }
                catch (RuntimeException ignored) {}
            }
        });
    }

    /**
     * Finds the Telecom PhoneAccountHandle for a given subscription ID, so outgoing calls
     * can be placed via a specific SIM on dual-SIM devices. Matches by ICC ID first
     * (the most reliable identifier), then falls back to number matching.
     */
    static PhoneAccountHandle findPhoneAccountForSubscription(
            Context context, TelecomManager telecomManager, String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty()) return null;
        int subId;
        try {
            subId = Integer.parseInt(subscriptionId);
        } catch (NumberFormatException ignored) {
            return null;
        }
        String iccId = null;
        String number = null;
        try {
            SubscriptionManager subscriptions =
                    context.getSystemService(SubscriptionManager.class);
            if (subscriptions != null) {
                List<SubscriptionInfo> infos = subscriptions.getActiveSubscriptionInfoList();
                if (infos != null) {
                    for (SubscriptionInfo info : infos) {
                        if (info.getSubscriptionId() == subId) {
                            iccId = info.getIccId();
                            try {
                                number = subscriptions.getPhoneNumber(subId);
                            } catch (SecurityException ignored) {}
                            break;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}
        if (iccId == null && number == null) return null;
        List<PhoneAccountHandle> handles;
        try {
            handles = telecomManager.getCallCapablePhoneAccounts();
        } catch (SecurityException ignored) {
            return null;
        }
        if (handles == null) return null;
        // First pass: match by ICC ID embedded in the handle ID.
        if (iccId != null && !iccId.isEmpty()) {
            for (PhoneAccountHandle handle : handles) {
                if (handle.getId() != null && handle.getId().contains(iccId)) return handle;
            }
        }
        // Second pass: match by phone number on the account.
        if (number != null && !number.isEmpty()) {
            String digits = number.replaceAll("\\D", "");
            for (PhoneAccountHandle handle : handles) {
                try {
                    PhoneAccount account = telecomManager.getPhoneAccount(handle);
                    if (account == null || account.getAddress() == null) continue;
                    String addressDigits = account.getAddress().getSchemeSpecificPart()
                            .replaceAll("\\D", "");
                    if (!addressDigits.isEmpty() && (addressDigits.endsWith(digits)
                            || digits.endsWith(addressDigits))) return handle;
                } catch (SecurityException ignored) {}
            }
        }
        return null;
    }

    public static ActionResult execute(Context context, AgentCommand command, TelecomManager telecomManager) {
        if (!command.isSupported()) return ActionResult.failure("unsupported action");
        if ("dial".equals(command.action)) {
            if (command.number.isEmpty()) return ActionResult.failure("number missing");
            Bundle extras = new Bundle();
            PhoneAccountHandle handle = findPhoneAccountForSubscription(context, telecomManager,
                    command.subscriptionId);
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
                BridgeLog.info("dial_with_sim subscription_id=" + command.subscriptionId);
            } else if (!command.subscriptionId.isEmpty()) {
                BridgeLog.info("dial_sim_not_found subscription_id=" + command.subscriptionId
                        + " fallback=default");
            }
            String error = TelecomInvocation.run(
                    task -> new Handler(Looper.getMainLooper()).post(task),
                    () -> telecomManager.placeCall(Uri.parse("tel:" + command.number), extras),
                    2_000L);
            return error.isEmpty() ? ActionResult.success() : ActionResult.failure(error);
        }
        Call call = CALLS.get(command.callId);
        if (call == null) {
            // 挂断/拒接是幂等的：通话已不在 Telecom 栈里（对方已挂断或本地已释放）
            // 说明期望的结束状态已达成，直接成功，避免 iPhone 侧弹“active call id mismatch”。
            if ("end".equals(command.action) || "reject".equals(command.action)) {
                BridgeLog.info("call_action_idempotent_noop action=" + command.action);
                return ActionResult.success();
            }
            return ActionResult.failure("active call id mismatch");
        }
        CountDownLatch invoked = new CountDownLatch(1);
		AtomicReference<Throwable> invocationError = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                switch (command.action) {
                    case "answer" -> call.answer(VideoProfile.STATE_AUDIO_ONLY);
                    case "reject" -> {
                        if (call.getState() == Call.STATE_RINGING) call.reject(false, null);
                        else call.disconnect();
                    }
                    case "end" -> call.disconnect();
                    case "dtmf" -> {
                        if (command.number.length() != 1) throw new IllegalArgumentException("DTMF digit missing");
                        call.playDtmfTone(command.number.charAt(0));
                        call.stopDtmfTone();
                    }
                    default -> throw new IllegalArgumentException("unsupported action");
                }
			} catch (Throwable error) {
				invocationError.set(error);
            } finally {
                invoked.countDown();
            }
        });
        try {
            if (!invoked.await(2, TimeUnit.SECONDS)) return ActionResult.failure("Telecom invocation timeout");
			if (invocationError.get() != null) {
				Throwable error = invocationError.get();
				return ActionResult.failure(error.getClass().getSimpleName() + ": " + error.getMessage());
			}
            if ("dtmf".equals(command.action)) return ActionResult.success();
            int expected = "answer".equals(command.action) ? Call.STATE_ACTIVE : Call.STATE_DISCONNECTED;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (call.getState() == expected || (expected == Call.STATE_DISCONNECTED && !IDS.containsKey(call))) {
                    return ActionResult.success();
                }
                Thread.sleep(50);
            }
            return ActionResult.failure("Telecom state confirmation timeout");
        } catch (Exception error) {
            return ActionResult.failure(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    public static final class ActionResult {
        public final boolean success;
        public final String error;

        ActionResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }

        static ActionResult success() { return new ActionResult(true, ""); }
        static ActionResult failure(String error) { return new ActionResult(false, error == null ? "unknown" : error); }
    }
}
