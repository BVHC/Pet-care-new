package com.petcare.module.appointment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
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
 * Body đặt lịch (appointment-v1 {@code BookAppointmentRequest}; docs/adr/0043): 5 trường bắt buộc, id phải dương
 * ({@code lockPet} từ chối id ≤ 0 bằng 500), ghi chú ≤ 500 (UTF-16, chặt hơn {@code VARCHAR(500)}). Lỗi gắn đúng tên
 * trường, message tiếng Việt.
 */
class BookAppointmentRequestValidationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final LocalTime START = LocalTime.of(8, 30);

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
    void validRequestPasses() {
        assertThat(validator.validate(new BookAppointmentRequest(1L, 2L, 3L, DAY, START, null))).isEmpty();
        assertThat(validator.validate(new BookAppointmentRequest(1L, 2L, 3L, DAY, START, "đ".repeat(500)))).isEmpty();
    }

    @Test
    void everyMissingFieldIsReportedOnItsOwnName() {
        assertThat(errors(new BookAppointmentRequest(null, null, null, null, null, null))).containsExactlyInAnyOrderEntriesOf(
                Map.of("petId", "Thiếu thú cưng", "branchId", "Thiếu chi nhánh", "serviceId", "Thiếu dịch vụ",
                        "slotDate", "Thiếu ngày hẹn", "slotStart", "Thiếu giờ bắt đầu của khung"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void nonPositiveIdsAreRejected(long id) {
        assertThat(errors(new BookAppointmentRequest(id, id, id, DAY, START, null))).containsExactlyInAnyOrderEntriesOf(
                Map.of("petId", "Mã thú cưng không hợp lệ", "branchId", "Mã chi nhánh không hợp lệ",
                        "serviceId", "Mã dịch vụ không hợp lệ"));
    }

    @Test
    void noteLongerThan500IsRejected() {
        assertThat(errors(new BookAppointmentRequest(1L, 2L, 3L, DAY, START, "x".repeat(501))))
                .containsExactlyEntriesOf(Map.of("note", "Ghi chú tối đa 500 ký tự"));
        assertThat(errors(new BookAppointmentRequest(1L, 2L, 3L, DAY, START, "😀".repeat(251))))
                .as("emoji là 2 đơn vị UTF-16").containsKey("note");
    }

    private static Map<String, String> errors(BookAppointmentRequest request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
