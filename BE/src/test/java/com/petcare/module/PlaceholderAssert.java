package com.petcare.module;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Khẳng định dùng chung cho test placeholder tầng 3 của BE-2 (T55c, docs/06 §1): ném
 * {@link UnsupportedOperationException} (không trả giá trị giả), thông điệp nêu đúng {@code Api.method}, nói rõ là
 * placeholder T55c và task cài bản thật.
 */
public final class PlaceholderAssert {

    private PlaceholderAssert() {
    }

    public static void assertPlaceholder(ThrowingCallable call, String apiMethod, String task) {
        assertThatThrownBy(call)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(apiMethod + " chưa cài")
                .hasMessageContaining("placeholder T55c")
                .hasMessageContaining(task);
    }
}
