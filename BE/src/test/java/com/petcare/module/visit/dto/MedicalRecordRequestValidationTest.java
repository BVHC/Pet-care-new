package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức body của visit-v1 #13 {@code UpdateMedicalRecordRequest} và #15 {@code AddendumRequest} (docs/adr/0064):
 * chuỗi bỏ khoảng trắng hai đầu (cả Unicode); bệnh án đổi chuỗi rỗng thành {@code null} (PUT thay toàn bộ); nội dung
 * bản bổ sung bắt buộc, message tiếng Việt.
 */
class MedicalRecordRequestValidationTest {

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
    void medicalRecordFieldsAreStrippedAndBlankBecomesNull() {
        UpdateMedicalRecordRequest request = new UpdateMedicalRecordRequest("　 khám \n", "  ", null, "\t nội bộ ",
                LocalDate.of(2026, 11, 5));

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.examination()).isEqualTo("khám");
        assertThat(request.diagnosis()).as("chẩn đoán chỉ khoảng trắng = chưa có (BR-KB-02)").isNull();
        assertThat(request.treatmentPlan()).isNull();
        assertThat(request.internalNote()).isEqualTo("nội bộ");
        assertThat(request.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void weightIsRequiredWithAVietnameseMessageAndRangeIsLeftToTheCustomerRule() {
        assertThat(validator.validate(new RecordWeightRequest(null))).extracting(ConstraintViolation::getMessage)
                .containsExactly("Bắt buộc nhập cân nặng");
        assertThat(validator.validate(new RecordWeightRequest(java.math.BigDecimal.ZERO)))
                .as("≤ 0 là 400 BR-KH-04 của customer, không phải Bean Validation").isEmpty();
    }

    @Test
    void emptyMedicalRecordIsValid() {
        assertThat(validator.validate(new UpdateMedicalRecordRequest(null, null, null, null, null))).isEmpty();
    }

    @Test
    void addendumIsStrippedAndPublicByDefault() {
        AddendumRequest request = new AddendumRequest("　 Bổ sung kết quả \n", null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.content()).isEqualTo("Bổ sung kết quả");
        assertThat(request.internal()).isFalse();
        assertThat(new AddendumRequest("x", true).internal()).isTrue();
        assertThat(new AddendumRequest("x", false).internal()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "　"})
    void addendumContentIsRequired(String content) {
        assertThat(errors(validator.validate(new AddendumRequest(content, false))))
                .containsExactlyEntriesOf(Map.of("content", "Bắt buộc nhập nội dung bổ sung"));
    }

    private static <T> Map<String, String> errors(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage));
    }
}
