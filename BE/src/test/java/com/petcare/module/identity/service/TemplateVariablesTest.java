package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ngữ pháp chặt của biến mẫu thông báo (BR-QT-14; docs/adr/0037 mục 3): dấu ngoặc nhọn chỉ dùng để viết {@code {ten}}
 * với tên thuộc {@code allowedVars}, khớp chính xác.
 */
class TemplateVariablesTest {

    private static final List<String> ALLOWED = List.of("ma_otp", "thoi_han_phut", "ten_khach");

    @Test
    void validVariablesAreCollectedInOrderWithoutDuplicates() {
        TemplateVariables.Scan scan = TemplateVariables.scan(
                "Chào {ten_khach}, mã {ma_otp} còn {thoi_han_phut} phút. Nhắc lại: {ma_otp}", ALLOWED);

        assertThat(scan.valid()).isTrue();
        assertThat(scan.used()).containsExactly("ten_khach", "ma_otp", "thoi_han_phut");
        assertThat(scan.unknown()).isEmpty();
        assertThat(scan.strayBrace()).isFalse();
    }

    @Test
    void textWithoutBracesIsValidAndUsesNothing() {
        TemplateVariables.Scan scan = TemplateVariables.scan("Không có biến nào. Ký tự đặc biệt: $ \\ % [ ] ( )",
                ALLOWED);

        assertThat(scan.valid()).isTrue();
        assertThat(scan.used()).isEmpty();
    }

    @Test
    void nullOrEmptyTextIsValid() {
        assertThat(TemplateVariables.scan(null, ALLOWED).valid()).isTrue();
        assertThat(TemplateVariables.scan("", ALLOWED).valid()).isTrue();
    }

    @Test
    void unknownNamesAreReportedWithBracesInOrderOfAppearanceWithoutDuplicates() {
        TemplateVariables.Scan scan = TemplateVariables.scan(
                "{abc} {Ma_OTP} {ma_otp} { ma_otp } {} {abc} {ma otp}", ALLOWED);

        assertThat(scan.valid()).isFalse();
        assertThat(scan.unknown()).containsExactly("{abc}", "{Ma_OTP}", "{ ma_otp }", "{}", "{ma otp}");
        assertThat(scan.used()).containsExactly("ma_otp");
        assertThat(scan.strayBrace()).isFalse();
    }

    @Test
    void manyUnknownNamesAreAllReported() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            text.append("{bien_").append(i).append("} ");
        }

        assertThat(TemplateVariables.scan(text.toString(), ALLOWED).unknown()).hasSize(25)
                .startsWith("{bien_0}").endsWith("{bien_24}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ma_otp", "ma_otp}", "}", "{", "{{ma_otp}}", "mã {ma_otp}}", "{ {ma_otp}", "a } b",
            "{ma_otp} {"})
    void strayBraceIsReported(String text) {
        TemplateVariables.Scan scan = TemplateVariables.scan(text, ALLOWED);

        assertThat(scan.strayBrace()).isTrue();
        assertThat(scan.valid()).isFalse();
    }

    @Test
    void doubledBracesAroundAValidNameStillCountTheInnerVariable() {
        TemplateVariables.Scan scan = TemplateVariables.scan("{{ma_otp}}", ALLOWED);

        assertThat(scan.used()).containsExactly("ma_otp");
        assertThat(scan.unknown()).isEmpty();
        assertThat(scan.strayBrace()).isTrue();
    }

    @Test
    void unicodeAndNewlinesAroundVariablesAreFine() {
        TemplateVariables.Scan scan = TemplateVariables.scan("Mã 🐶:\r\n{ma_otp}\n— Pet Care ✓", ALLOWED);

        assertThat(scan.valid()).isTrue();
        assertThat(scan.used()).containsExactly("ma_otp");
    }

    @Test
    void emptyAllowedListMakesEveryTokenUnknown() {
        TemplateVariables.Scan scan = TemplateVariables.scan("{ma_otp}", List.of());

        assertThat(scan.unknown()).containsExactly("{ma_otp}");
    }
}
