package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức của body tiếp nhận (visit-v1 {@code CreateVisitRequest}, docs/adr/0059 D3): chỉ {@code petId} bắt buộc, mọi
 * id phải dương; walk-in thiếu dịch vụ là BR-TN-01 ở service, không phải lỗi hình thức.
 */
class CreateVisitRequestValidationTest {

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
    void onlyThePetIsRequired() {
        assertThat(validator.validate(new CreateVisitRequest(12L, null, null, null))).isEmpty();
        assertThat(validator.validate(new CreateVisitRequest(12L, 501L, 7L, true))).isEmpty();
        assertThat(errors(new CreateVisitRequest(null, 501L, null, null)))
                .containsExactlyEntriesOf(Map.of("petId", "Thiếu thú cưng"));
    }

    @Test
    void everyIdMustBePositive() {
        assertThat(errors(new CreateVisitRequest(0L, -1L, 0L, null))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "petId", "Mã thú cưng không hợp lệ",
                "appointmentId", "Mã lịch hẹn không hợp lệ",
                "serviceId", "Mã dịch vụ không hợp lệ"));
    }

    @Test
    void missingEmergencyFlagMeansNotEmergency() {
        assertThat(new CreateVisitRequest(12L, null, 7L, null).emergency()).isFalse();
        assertThat(new CreateVisitRequest(12L, null, 7L, false).emergency()).isFalse();
        assertThat(new CreateVisitRequest(12L, null, 7L, true).emergency()).isTrue();
    }

    private static Map<String, String> errors(CreateVisitRequest request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
