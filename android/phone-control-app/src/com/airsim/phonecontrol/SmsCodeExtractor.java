package com.airsim.phonecontrol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts verification codes from SMS bodies and classifies messages.
 *
 * <p>Used by {@link IncomingSMSReceiver} so the relay / iPhone side can build
 * the verification-code center and the pinned pickup-code cards without
 * re-parsing raw SMS text.
 */
public final class SmsCodeExtractor {
    // e.g. "验证码：123456", "动态码为 1234", "Your verification code is 5678"
    private static final Pattern KEYWORD_CODE = Pattern.compile(
            "(?:验证码|动态码|校验码|确认码|auth(?:entication)?\\s*code|verification\\s*code|otp)[^\\d]{0,10}(\\d{4,8})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // standalone 4-8 digit run, not part of a longer number
    private static final Pattern STANDALONE_CODE =
            Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    // guard: skip numbers that are account/card tails, e.g. "尾号1234"
    private static final Pattern TAIL_GUARD =
            Pattern.compile("(尾号|卡号|账号).{0,2}$");

    private static final Pattern PICKUP_SENDER =
            Pattern.compile("丰巢|菜鸟|京东|顺丰|邮政|中通|圆通|韵达|申通|极兔|德邦");
    private static final Pattern PICKUP_BODY =
            Pattern.compile("取件码|提货码|凭.{0,8}取件|快递柜|驿站.*取");

    private SmsCodeExtractor() {}

    /** Returns the verification code, or "" when none is found. */
    public static String extract(String body) {
        if (body == null || body.isEmpty()) return "";
        Matcher keyword = KEYWORD_CODE.matcher(body);
        if (keyword.find()) return keyword.group(1);
        Matcher standalone = STANDALONE_CODE.matcher(body);
        while (standalone.find()) {
            String before = body.substring(0, standalone.start());
            if (TAIL_GUARD.matcher(before).find()) continue; // e.g. 尾号1234
            return standalone.group(1);
        }
        return "";
    }

    /**
     * Classifies an SMS: "pickup" (courier pickup code), "code"
     * (verification code), or "other".
     */
    public static String classify(String sender, String body) {
        String safeSender = sender == null ? "" : sender;
        String safeBody = body == null ? "" : body;
        if (PICKUP_SENDER.matcher(safeSender).find()
                || PICKUP_BODY.matcher(safeBody).find()) {
            return "pickup";
        }
        if (!extract(safeBody).isEmpty()) return "code";
        return "other";
    }
}
