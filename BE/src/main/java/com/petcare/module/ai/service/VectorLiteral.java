package com.petcare.module.ai.service;

/** Dạng chữ của vector cho pgvector: {@code [0.1,0.2,…]}, dùng với {@code CAST(? AS vector)}. */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
