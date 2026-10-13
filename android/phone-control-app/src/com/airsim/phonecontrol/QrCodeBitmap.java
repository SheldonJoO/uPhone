package com.airsim.phonecontrol;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Minimal QR code renderer for the pairing payload (zxing core only, no Android extras). */
public final class QrCodeBitmap {
    private QrCodeBitmap() {}

    /**
     * Encodes content as a square black-on-white QR Bitmap, or null on failure.
     * sizePx is the target width/height in pixels.
     */
    public static Bitmap encode(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception error) {
            BridgeLog.info("qr_encode_failed reason=" + error.getClass().getSimpleName());
            return null;
        }
    }
}
