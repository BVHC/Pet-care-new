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
 * Ràng buộc của {@link RejectShiftRequestRequest} (body của từ chối yêu cầu #14, docs/adr/0139). Chạy Hibernate
 * Validator thật, không cần Spring / Docker. Điểm cần chứng minh: lý do từ chối mà entity sẽ {@code strip()} thành rỗng
 * <b>phải</b> bị DTO chặn ở 400 {@code VALIDATION_FAILED}; nếu lọt qua, {@code EntityText.require} ném
 * {@code IllegalArgumentException} và {@code GlobalExceptionHandler} trả 500. Ký tự khoảng trắng viết bằng escape để
 * không phụ thuộc bộ mã hóa của file.
 */
class RejectShiftRequestRequestTest {

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
    void aNoteThatStripsToEmptyIsRejectedForEveryKindOfWhitespace() {
        // Dấu cách, tab, xuống dòng, ideographic space U+3000, em space U+2003, line separator U+2028.
        for (String blank : new String[] {"", " ", "   ", "\t", "\n", "　", " ", " ", " 　 \t"}) {
            assertThat(blank.strip()).as("điều kiện của ca thử").isEmpty();

            Set<ConstraintViolation<RejectShiftRequestRequest>> violations = validator
                    .validate(new RejectShiftRequestRequest(blank));

            assertThat(violations).as("lý do %s phải bị DTO chặn", escape(blank)).isNotEmpty();
        }
    }

    @Test
    void aBlankNoteGivesExactlyOneViolationOnTheDecisionNoteFieldWithTheVietnameseMessage() {
        for (String blank : new String[] {"", "   ", "　", null}) {
            Set<ConstraintViolation<RejectShiftRequestRequest>> violations = validator
                    .validate(new RejectShiftRequestRequest(blank));

            assertThat(violations).as("lý do %s", blank == null ? "null" : escape(blank)).hasSize(1);
            ConstraintViolation<RejectShiftRequestRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("decisionNote");
            assertThat(violation.getMessage()).isEqualTo("Vui lòng nhập lý do từ chối");
        }
    }

    @Test
    void everyCharacterThatStripRemovesIsTreatedAsBlank() {
        for (char c = 0; c < 0x3100; c++) {
            if (Character.isWhitespace(c)) {
                String blank = String.valueOf(c);
                assertThat(validator.validate(new RejectShiftRequestRequest(blank))).as("U+%04x", (int) c)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void aNoteWithTextAroundWhitespaceIsAccepted() {
        assertThat(validator.validate(new RejectShiftRequestRequest("　 Đủ người 　"))).isEmpty();
    }

    @Test
    void theLimitIs300CharactersAndTheOverLimitMessageIsVietnamese() {
        assertThat(validator.validate(new RejectShiftRequestRequest("Ca này đã đủ người nghỉ"))).isEmpty();
        assertThat(validator.validate(new RejectShiftRequestRequest("x".repeat(300)))).isEmpty();
        Set<ConstraintViolation<RejectShiftRequestRequest>> over = validator
                .validate(new RejectShiftRequestRequest("x".repeat(301)));
        assertThat(over).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("decisionNote");
            assertThat(violation.getMessage()).isEqualTo("Lý do từ chối tối đa 300 ký tự");
        });
    }

    @Test
    void theSizeLimitCountsTheRawStringSoPaddingCannotSneakPastTheColumn() {
        // 290 ký tự thật + 20 khoảng trắng hai đầu = 310 > 300: bị chặn dù strip() còn 290 (khuôn ShiftReasonRequest).
        String padded = " ".repeat(10) + "x".repeat(290) + " ".repeat(10);

        assertThat(validator.validate(new RejectShiftRequestRequest(padded))).hasSize(1);
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.chars().forEach(c -> out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", c) : String.valueOf((char) c)));
        return out.append('"').toString();
    }
}
