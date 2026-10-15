package com.petcare.module.appointment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
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
 * Body đổi khung ({@code RescheduleRequest}) và hủy ({@code CancelAppointmentRequest}) của appointment-v1 #5, #6
 * (docs/adr/0045): hai trường khung bắt buộc; lý do không bắt buộc, ≤ 300 (UTF-16, chặt hơn {@code VARCHAR(300)}). Lỗi
 * gắn đúng tên trường, message tiếng Việt.
 */
class RescheduleCancelRequestValidationTest {

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
    void rescheduleNeedsBothSlotFields() {
        assertThat(validator.validate(new RescheduleAppointmentRequest(LocalDate.of(2026, 10, 20), LocalTime.of(9, 0))))
                .isEmpty();
        assertThat(errors(new RescheduleAppointmentRequest(null, null))).containsExactlyInAnyOrderEntriesOf(
                Map.of("slotDate", "Thiếu ngày hẹn", "slotStart", "Thiếu giờ bắt đầu của khung"));
    }

    @Test
    void cancelReasonIsOptionalUpTo300() {
        assertThat(validator.validate(new CancelAppointmentRequest(null))).isEmpty();
        assertThat(validator.validate(new CancelAppointmentRequest(""))).isEmpty();
        assertThat(validator.validate(new CancelAppointmentRequest("đ".repeat(300)))).isEmpty();
        assertThat(errors(new CancelAppointmentRequest("x".repeat(301))))
                .containsExactlyEntriesOf(Map.of("reason", "Lý do tối đa 300 ký tự"));
    }

    private static <T> Map<String, String> errors(T request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
