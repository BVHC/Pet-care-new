package com.petcare.module.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 dạng hex 64 ký tự: mã băm phác đồ ({@code content_hash}) và dấu vết nội dung trong audit. */
final class Sha256 {

    private Sha256() {
    }

    static String hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
