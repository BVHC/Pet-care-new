package com.petcare.module.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * {@code jti} của access token: 128 bit ngẫu nhiên (base64url); {@code sessions.token_hash} = SHA-256 hex của nó
 * (docs/adr/0003). {@code jti} chỉ nằm trong token, nên dù lộ {@code JWT_SECRET} cũng không giả được token cho
 * một phiên đang có. Refresh token (docs/adr/0091) dùng cùng cách: 256 bit ngẫu nhiên, chỉ lưu SHA-256 hex
 * ({@code refresh_tokens.token_hash}); chuỗi gốc chỉ nằm ở client.
 */
final class JtiHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int JTI_BYTES = 16;
    private static final int REFRESH_TOKEN_BYTES = 32;

    private JtiHasher() {
    }

    static String newJti() {
        return randomToken(JTI_BYTES);
    }

    /** Refresh token: 256 bit ngẫu nhiên, 43 ký tự base64url. */
    static String newRefreshToken() {
        return randomToken(REFRESH_TOKEN_BYTES);
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static String hash(String jti) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jti.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** So sánh hằng thời gian để không lộ thông tin qua thời gian phản hồi. */
    static boolean matches(String jti, String storedHash) {
        return storedHash != null && MessageDigest.isEqual(
                hash(jti).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
