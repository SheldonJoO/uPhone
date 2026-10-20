package com.airsim.phonecontrol;

public final class TelecomStateMapper {
    private TelecomStateMapper() {}

    public static String toWireState(int state) {
        return switch (state) {
            case 1, 8, 9 -> "dialing";
            case 2, 13 -> "incoming";
            case 3 -> "held";
            case 4 -> "active";
            case 7, 10 -> "ended";
            default -> "unknown";
        };
    }

	public static boolean shouldSilenceLocalOutput(String mode, String wireState) {
		// 值守机的存在意义就是替 iPhone 值守：任何走到 Agent 的通话（VoWLAN / 云端）
		// 都必须本机静默，听筒漏音会直接把通话内容外放。「本机显示」模式只影响
		// 通知文案，不再有权放开通话静音——此前它把静音整体关掉，是
		// 「通话时安卓听筒依旧有声音」的直接根因。
		return "dialing".equals(wireState)
				|| "incoming".equals(wireState)
				|| "active".equals(wireState)
				|| "held".equals(wireState);
	}
}
