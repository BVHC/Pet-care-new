package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Body của {@code PUT /api/notification-templates/{code}} (identity-v1 {@code UpdateTemplateRequest}; docs/adr/0037):
 * chỉ hình thức — tiêu đề bắt buộc (mọi kênh) và ≤ 255, nội dung bắt buộc, không giới hạn độ dài, không cấm xuống dòng.
 * Biến kiểm ở service (BR-QT-14).
 */
class UpdateTemplateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void validContentPasses() {
        assertThat(validator.validate(new UpdateTemplateRequest("Tiêu đề {ma_otp}", "Mã {ma_otp}"))).isEmpty();
        assertThat(validator.validate(new UpdateTemplateRequest("ê".repeat(255), "x".repeat(100_000)))).isEmpty();
        assertThat(validator.validate(new UpdateTemplateRequest("Dòng 1\r\nDòng 2", "{lạ} {"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankSubjectOrBodyIsRejectedPerField(String blank) {
        assertThat(errors(new UpdateTemplateRequest(blank, "Nội dung")))
                .containsExactly(Map.entry("subject", "Vui lòng nhập tiêu đề"));
        assertThat(errors(new UpdateTemplateRequest("Tiêu đề", blank)))
                .containsExactly(Map.entry("body", "Vui lòng nhập nội dung"));
    }

    @Test
    void missingFieldsAreReportedOnTheirOwnNames() {
        assertThat(errors(new UpdateTemplateRequest(null, null))).containsOnly(
                Map.entry("subject", "Vui lòng nhập tiêu đề"), Map.entry("body", "Vui lòng nhập nội dung"));
    }

    @Test
    void subjectOver255IsRejected() {
        assertThat(errors(new UpdateTemplateRequest("x".repeat(256), "Nội dung")))
                .containsExactly(Map.entry("subject", "Tiêu đề tối đa 255 ký tự"));
    }

    private static Map<String, String> errors(UpdateTemplateRequest request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
