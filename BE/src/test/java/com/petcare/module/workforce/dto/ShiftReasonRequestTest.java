package com.petcare.module.workforce.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Ràng buộc của {@link ShiftReasonRequest} (body của hủy phân ca #6 và ghi vắng đột xuất #7, docs/adr/0149). Chạy
 * Hibernate Validator thật, không cần Spring / Docker. Điểm cần chứng minh: lý do mà service sẽ {@code strip()} thành
 * rỗng <b>phải</b> bị DTO chặn ở 400 {@code VALIDATION_FAILED}; nếu lọt qua, {@code EntityText.require} ném
 * {@code IllegalArgumentException} và {@code GlobalExceptionHandler} trả 500.
 */
class ShiftReasonRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @Test
    void aReasonThatStripsToEmptyIsRejectedForEveryKindOfWhitespace() {
        // Ngăn cách: dấu cách thường, tab, xuống dòng, khoảng trắng ideographic (U+3000), em space (U+2003), line separator (U+2028).
        for (String blank : new String[] {"", " ", "   ", "\t", "\n", "　", " ", " ", " 　 \t"}) {
            assertThat(blank.strip()).as("điều kiện của ca thử").isEmpty();

            Set<ConstraintViolation<ShiftReasonRequest>> violations = validator.validate(new ShiftReasonRequest(blank));

            assertThat(violations).as("lý do %s phải bị DTO chặn", escape(blank)).isNotEmpty();
        }
    }

    @Test
    void aBlankReasonGivesExactlyOneViolationOnTheReasonFieldWithTheVietnameseMessage() {
        for (String blank : new String[] {"", "   ", "　"}) {
            Set<ConstraintViolation<ShiftReasonRequest>> violations = validator.validate(new ShiftReasonRequest(blank));

            assertThat(violations).as("lý do %s", escape(blank)).hasSize(1);
            ConstraintViolation<ShiftReasonRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("reason");
            assertThat(violation.getMessage()).isEqualTo("Vui lòng nhập lý do");
        }
    }

    @Test
    void everyCharacterThatStripRemovesIsTreatedAsBlank() {
        // Character.isWhitespace gồm cả U+001C..U+001F (dấu phân tách file / nhóm / bản ghi / đơn vị).
        for (char c = 0; c < 0x3100; c++) {
            if (Character.isWhitespace(c)) {
                String blank = String.valueOf(c);
                assertThat(validator.validate(new ShiftReasonRequest(blank))).as("U+%04x", (int) c).isNotEmpty();
            }
        }
    }

    @Test
    void aReasonWithTextAroundWhitespaceIsAccepted() {
        assertThat(validator.validate(new ShiftReasonRequest("　 Ốm 　"))).isEmpty();
    }

    @Test
    void aNullReasonIsRejected() {
        assertThat(validator.validate(new ShiftReasonRequest(null))).isNotEmpty();
    }

    @Test
    void aRealReasonPassesAndTheLimitIs300Characters() {
        assertThat(validator.validate(new ShiftReasonRequest("Nhân viên báo ốm"))).isEmpty();
        assertThat(validator.validate(new ShiftReasonRequest("x".repeat(300)))).isEmpty();
        assertThat(validator.validate(new ShiftReasonRequest("x".repeat(301)))).isNotEmpty();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.chars().forEach(c -> out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", c) : String.valueOf((char) c)));
        return out.append('"').toString();
    }
}
