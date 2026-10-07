package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.service.NotificationTemplateRenderer.Rendered;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/** Thay biến của mẫu (BR-QT-14, docs/adr/0012). */
class NotificationTemplateRendererTest {

    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    private static final TemplateView OTP = new TemplateView("OTP_REGISTER", "EMAIL", "Mã {ma_otp}",
            "Chào {ten_khach},\nMã: {ma_otp}, hạn {thoi_han_phut} phút. {la}",
            List.of("ten_khach", "ma_otp", "thoi_han_phut"), List.of("ma_otp"));

    @Test
    void replacesEveryOccurrenceOfAllowedVariables() {
        Rendered rendered = renderer.render(OTP, Map.of("ten_khach", "An", "ma_otp", "123456", "thoi_han_phut", 5));

        assertThat(rendered.subject()).isEqualTo("Mã 123456");
        assertThat(rendered.body()).isEqualTo("Chào An,\nMã: 123456, hạn 5 phút. {la}");
    }

    @Test
    void missingOptionalVariableBecomesEmpty() {
        Rendered rendered = renderer.render(OTP, Map.of("ma_otp", "123456", "thoi_han_phut", 5));

        assertThat(rendered.body()).startsWith("Chào ,");
        assertThat(rendered.body()).doesNotContain("{ten_khach}");
    }

    @Test
    void reportsMissingOrNullRequiredVariables() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("ma_otp", null);

        assertThat(renderer.missingRequired(OTP, Map.of("thoi_han_phut", 5))).containsExactly("ma_otp");
        assertThat(renderer.missingRequired(OTP, withNull)).containsExactly("ma_otp");
        assertThat(renderer.missingRequired(OTP, Map.of("ma_otp", "1"))).isEmpty();
    }

    /** Thay chuỗi thuần, không regex: {@code $1} và {@code \} trong giá trị giữ nguyên. */
    @Test
    void valuesWithRegexCharactersAreInsertedLiterally() {
        Rendered rendered = renderer.render(OTP, Map.of("ten_khach", "A$1\\B", "ma_otp", "1", "thoi_han_phut", 5));

        assertThat(rendered.body()).startsWith("Chào A$1\\B,");
    }

    @Test
    void nullSubjectRendersEmpty() {
        TemplateView noSubject = new TemplateView("X", "EMAIL", null, "b", List.of(), List.of());

        assertThat(renderer.render(noSubject, Map.of()).subject()).isEmpty();
    }
}
