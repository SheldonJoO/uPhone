package com.airsim.phonecontrol;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

public final class PairingSession {
    public static final long LIFETIME_MILLIS = 120_000L;
    /**
     * 配对码是 6 位数字，熵只有 ~20 bit；若不限流，有效期内可被枚举。
     * 连续失败到上限就作废整个会话，让暴破变成不可能（5 / 1,000,000）。
     */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private final String id;
    private final String code;
    private final long expiresAtMillis;
    private final byte[] privateKey;
    private final byte[] publicKey;
    private boolean used;
    private boolean invalidated;
    private int failedAttempts;

    private PairingSession(String id, String code, long expiresAtMillis, byte[] privateKey, byte[] publicKey) {
        this.id = id;
        this.code = code;
        this.expiresAtMillis = expiresAtMillis;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static PairingSession create(long nowMillis) throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        PairingCrypto.KeyMaterial keys = PairingCrypto.generateKeyMaterial();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        return new PairingSession(UUID.randomUUID().toString(), code, nowMillis + LIFETIME_MILLIS,
                keys.privateRaw, keys.publicRaw);
    }

    public String id() { return id; }
    public String code() { return code; }
    public long expiresAtMillis() { return expiresAtMillis; }
    public byte[] publicKey() { return publicKey.clone(); }

    public synchronized byte[] claim(byte[] clientPublicKey, byte[] sealedRegistration, long nowMillis)
            throws GeneralSecurityException {
        if (used) throw new GeneralSecurityException("pairing session already used");
        if (invalidated) {
            throw new GeneralSecurityException("pairing session locked after failed attempts");
        }
        if (nowMillis > expiresAtMillis) throw new GeneralSecurityException("pairing session expired");
        try {
            byte[] shared = PairingCrypto.sharedSecret(privateKey, clientPublicKey);
            byte[] key = PairingCrypto.deriveKey(shared, id, code);
            byte[] plaintext = PairingCrypto.open(sealedRegistration, key, id);
            used = true;
            return plaintext;
        } catch (GeneralSecurityException error) {
            failedAttempts++;
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) invalidated = true;
            throw error;
        }
    }
}
