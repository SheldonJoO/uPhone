package com.airsim.phonecontrol;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
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

    /**
     * 待推断 SIM 的去电：归一化号码 -> subscription_id（字符串）。
     * execute() 发起 placeCall 时按号码写入；AirSIMInCallService 在去电的 onCallAdded 里
     * 按号码消费并移除。按号码 key 而不是单个静态槽位，避免连续快速拨号时串卡。
     */
    public static final ConcurrentHashMap<String, String> pendingDialSubscriptionIds =
            new ConcurrentHashMap<>();

    /** 号码归一化：只保留数字与开头的 +，供拨号暂存与消费两端对齐。 */
    public static String canonicalDialNumber(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("[^0-9+]", "");
        // 只保留开头的 +，中间的 + 去掉
        if (digits.startsWith("+")) return "+" + digits.substring(1).replace("+", "");
        return digits.replace("+", "");
    }

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
        int subId = SimCardManager.resolveSubscriptionId(context, subscriptionId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null;
        String iccId = null;
        String number = null;
        String carrierName = null;
        try {
            SubscriptionManager subscriptions =
                    context.getSystemService(SubscriptionManager.class);
            if (subscriptions != null) {
                List<SubscriptionInfo> infos = subscriptions.getActiveSubscriptionInfoList();
                if (infos != null) {
                    for (SubscriptionInfo info : infos) {
                        if (info.getSubscriptionId() == subId) {
                            iccId = info.getIccId();
                            carrierName = safeCharSequence(info.getCarrierName());
                            try {
                                number = subscriptions.getPhoneNumber(subId);
                            } catch (SecurityException ignored) {}
                            break;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}
        if (iccId == null && number == null && carrierName == null) return null;
        List<PhoneAccountHandle> handles;
        try {
            handles = telecomManager.getCallCapablePhoneAccounts();
        } catch (SecurityException ignored) {
            return null;
        }
        if (handles == null) return null;
        // Pass 0（双卡正确选路的关键）：PhoneAccount 本身不暴露 subscription id，
        // 需经 TelephonyManager.getSubscriptionId(PhoneAccountHandle) 反查（API 30+）。
        // TCL / 多数 OEM ROM 既不把 ICC ID 嵌进 handle id，也不暴露号码，
        // 因此这一 pass 是让指定 SIM 真正生效的唯一可靠路径。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            TelephonyManager tel = context.getSystemService(TelephonyManager.class);
            if (tel != null) {
                for (PhoneAccountHandle handle : handles) {
                    try {
                        if (tel.getSubscriptionId(handle) == subId) {
                            BridgeLog.info("dial_sim_matched subscription_id=" + subId
                                    + " match=subscription_id");
                            return handle;
                        }
                    } catch (SecurityException ignored) {}
                }
            }
        }
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
        // 兜底 pass：部分国产 ROM（TCL 等）既不暴露 ICC ID、也不暴露号码，但 PhoneAccount 的
        // label 可能是运营商名（如「中国移动」）。用订阅的 carrierName 与 label 做归一化包含匹配，
        // 给双卡选路多一条活路。
        if (carrierName != null && !carrierName.isEmpty()) {
            String norm = normalizeCarrier(carrierName);
            for (PhoneAccountHandle handle : handles) {
                try {
                    PhoneAccount account = telecomManager.getPhoneAccount(handle);
                    if (account == null) continue;
                    CharSequence label = account.getLabel();
                    if (label != null) {
                        String normLabel = normalizeCarrier(label.toString());
                        if (!normLabel.isEmpty() && (normLabel.contains(norm) || norm.contains(normLabel))) {
                            return handle;
                        }
                    }
                } catch (SecurityException ignored) {}
            }
        }
        return null;
    }

    private static String safeCharSequence(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String normalizeCarrier(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s\\-()（）]", "").toLowerCase(java.util.Locale.ROOT);
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
            } else if (command.subscriptionId != null && !command.subscriptionId.isEmpty()) {
                // 选卡失败必须 loud fail：静默回退默认卡会导致用户以为用 A 卡打出、实际走 B 卡。
                BridgeLog.info("dial_sim_not_found subscription_id=" + command.subscriptionId
                        + " rejected: no matching PhoneAccount");
                return ActionResult.failure("选卡失败：本机无法定位该 SIM 的通话账号");
            } else {
                // 未指定选卡：用值守卡（UI 承诺"拨出默认使用"），值守卡也定位不到才走系统默认。
                int dutySub = DutyStore.getDutySubscriptionId(context);
                if (dutySub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    PhoneAccountHandle dutyHandle = findPhoneAccountForSubscription(
                            context, telecomManager, String.valueOf(dutySub));
                    if (dutyHandle != null) {
                        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, dutyHandle);
                        BridgeLog.info("dial_with_duty_sim subscription_id=" + dutySub);
                    }
                }
            }
            // 暂存本次拨号指定的订阅，供 InCallService 按号码推断通话所用 SIM 卡（消费一次后移除）。
            String dialSubId = command.subscriptionId == null ? "" : command.subscriptionId;
            if (!dialSubId.isEmpty()) {
                pendingDialSubscriptionIds.put(canonicalDialNumber(command.number), dialSubId);
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
