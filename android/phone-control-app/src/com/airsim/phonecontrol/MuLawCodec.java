package com.airsim.phonecontrol;

/**
 * G.711 µ-law 编解码：8kHz/16bit PCM ↔ 8bit µ-law（64 kbps，4:1 压缩）。
 * VoBLE 的承载码型——BLE 5（2M PHY + DLE）下 20ms 帧（160 字节）可以稳定
 * 通知；音质为窄带电话级，作为「无网本地通话」的中间档够用。
 *
 * 实现策略：解码用 ITU G.711 标准公式；编码表不手写常数，而是对解码表做
 * 最近邻合并生成——保证 encode/decode 往返一致（decode(encode(x))≈x），
 * 不依赖任何记忆中的查表常数。
 */
final class MuLawCodec {
    private MuLawCodec() {}

    private static final short[] DECODE_TABLE = buildDecodeTable();
    private static final int[] ENCODE_TABLE = buildEncodeTable();

    /** 16bit 小端 PCM 320 字节（20ms@8kHz 单声道）→ µ-law 160 字节。 */
    static byte[] encode(byte[] pcm) {
        int samples = pcm.length / 2;
        byte[] output = new byte[samples];
        for (int index = 0; index < samples; index++) {
            int bits = (pcm[index * 2] & 0xff) | (pcm[index * 2 + 1] << 8);
            output[index] = (byte) ENCODE_TABLE[bits & 0xffff];
        }
        return output;
    }

    /** µ-law 帧 → 16bit 小端 PCM（每字节还原为 2 字节样本）。 */
    static byte[] decode(byte[] mulaw) {
        byte[] output = new byte[mulaw.length * 2];
        for (int index = 0; index < mulaw.length; index++) {
            short sample = DECODE_TABLE[mulaw[index] & 0xff];
            output[index * 2] = (byte) (sample & 0xff);
            output[index * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }
        return output;
    }

    /** ITU G.711 µ-law 解码标准公式。 */
    private static short[] buildDecodeTable() {
        short[] table = new short[256];
        for (int index = 0; index < 256; index++) {
            int value = ~index & 0xff;
            int magnitude = (((value & 0x0f) << 3) + 0x84);
            magnitude <<= (value & 0x70) >> 4;
            int sample = (value & 0x80) != 0 ? (0x84 - magnitude) : (magnitude - 0x84);
            table[index] = (short) Math.max(-32768, Math.min(32767, sample));
        }
        return table;
    }

    /**
     * 对每个样本找解码值最近的 µ-law 字节。
     * µ-law 字节布局：0x80..0xFF 承载正样本（解码值随字节号单调递增），
     * 0x00..0x7F 承载负样本。两个指针各自线性合并，O(65536)。
     */
    private static int[] buildEncodeTable() {
        int[] table = new int[65536];
        int pos = 0x80;
        for (int sample = 0; sample <= 32767; sample++) {
            while (pos < 0xFF && Math.abs(DECODE_TABLE[pos + 1] - sample) < Math.abs(DECODE_TABLE[pos] - sample)) {
                pos++;
            }
            table[sample] = pos;
        }
        int neg = 0x00;
        for (int magnitude = 32768; magnitude >= 1; magnitude--) {
            int sample = -magnitude;
            while (neg < 0x7F && Math.abs(DECODE_TABLE[neg + 1] - sample) < Math.abs(DECODE_TABLE[neg] - sample)) {
                neg++;
            }
            table[sample & 0xffff] = neg;
        }
        return table;
    }
}
