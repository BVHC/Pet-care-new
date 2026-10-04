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
 * một phiên đang có.
 */
final class JtiHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int JTI_BYTES = 16;

    private JtiHasher() {
    }

    static String newJti() {
        byte[] bytes = new byte[JTI_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
