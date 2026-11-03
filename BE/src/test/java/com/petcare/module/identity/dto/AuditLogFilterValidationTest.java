package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Bộ lọc của {@code GET /api/audit-logs} (identity-v1 #36; docs/adr/0038 mục 3): R1 {@code from > to} → lỗi ở
 * {@code to}; R2 {@code entityId} không kèm {@code entityType} → lỗi ở {@code entityType}. R3 (giới hạn 31 ngày khi lọc
 * {@code action}) đã bỏ ở ADR-0098. Không có rule {@code BR-…} nào: đây là kiểm tra hình thức.
 */
class AuditLogFilterValidationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

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
    void emptyFilterAndEverySelectiveShapeAreValid() {
        assertThat(errors(filter(null, null, null, null, null, null))).isEmpty();
        assertThat(errors(filter(5L, null, null, null, null, null))).isEmpty();
        assertThat(errors(filter(null, null, "accounts", 9L, null, null))).isEmpty();
        assertThat(errors(filter(null, null, "accounts", null, null, null))).as("chỉ entityType").isEmpty();
        assertThat(errors(filter(null, null, null, null, T0, null))).as("chỉ from").isEmpty();
        assertThat(errors(filter(null, null, null, null, null, T0))).as("chỉ to").isEmpty();
    }

    // ---------------------------------------------------------------- R1

    @Test
    void fromEqualToIsValidClosedRange() {
        assertThat(errors(filter(null, null, null, null, T0, T0))).isEmpty();
    }

    @Test
    void fromAfterToIsRejectedOnTo() {
        assertThat(errors(filter(null, null, null, null, T0.plusNanos(1000), T0)))
                .containsExactly(Map.entry("to", AuditLogFilterValidator.RANGE_MESSAGE));
    }

    // ---------------------------------------------------------------- R2

    @Test
    void entityIdWithoutEntityTypeIsRejectedOnEntityType() {
        assertThat(errors(filter(null, null, null, 9L, null, null)))
                .containsExactly(Map.entry("entityType", AuditLogFilterValidator.ENTITY_TYPE_MESSAGE));
    }

    // ---------------------------------------------------------------- action (R3 đã bỏ, ADR-0098)

    @Test
    void actionAloneNeedsNeitherAnchorNorWindow() {
        assertThat(errors(filter(null, "LOGIN_FAILED", null, null, null, null))).isEmpty();
        assertThat(errors(filter(null, "LOGIN_FAILED", null, null, T0, null))).as("chỉ from").isEmpty();
        assertThat(errors(filter(null, "LOGIN_FAILED", null, null, T0, T0.plus(Duration.ofDays(365))))).as("cả năm")
                .isEmpty();
        assertThat(errors(filter(5L, "LOGIN_FAILED", null, null, null, null))).isEmpty();
        assertThat(errors(filter(null, "STAFF_CREATED", "accounts", 9L, null, null))).isEmpty();
    }

    @Test
    void reversedWindowWithActionReportsOnlyRangeError() {
        assertThat(errors(filter(null, "LOGIN_FAILED", null, null, T0.plusSeconds(1), T0)))
                .containsExactly(Map.entry("to", AuditLogFilterValidator.RANGE_MESSAGE));
    }

    @Test
    void everyViolatedRuleIsReported() {
        assertThat(errors(filter(null, "LOGIN_FAILED", null, 9L, T0.plusSeconds(1), T0))).containsOnly(
                Map.entry("to", AuditLogFilterValidator.RANGE_MESSAGE),
                Map.entry("entityType", AuditLogFilterValidator.ENTITY_TYPE_MESSAGE));
    }

    // ---------------------------------------------------------------- chuẩn hóa

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankActionAndEntityTypeMeanNotSent(String blank) {
        AuditLogFilter filter = filter(null, blank, blank, null, null, null);

        assertThat(filter.action()).isNull();
        assertThat(filter.entityType()).isNull();
        assertThat(errors(filter)).isEmpty();
    }

    @Test
    void nonBlankValuesAreKeptVerbatim() {
        AuditLogFilter filter = filter(5L, " LOGIN_FAILED ", "accounts ", null, null, null);

        assertThat(filter.action()).isEqualTo(" LOGIN_FAILED ");
        assertThat(filter.entityType()).isEqualTo("accounts ");
    }

    @Test
    void offsetDateTimesBecomeTheSameInstant() {
        OffsetDateTime local = OffsetDateTime.of(2026, 10, 5, 8, 30, 0, 0, ZoneOffset.ofHours(7));

        AuditLogFilter filter = AuditLogFilter.of(null, null, null, null, local, local);

        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-05T01:30:00Z")).isEqualTo(filter.to());
        assertThat(AuditLogFilter.of(null, null, null, null, null, null).from()).isNull();
    }

    private static AuditLogFilter filter(Long actor, String action, String entityType, Long entityId, Instant from,
            Instant to) {
        return new AuditLogFilter(actor, action, entityType, entityId, from, to);
    }

    private static Map<String, String> errors(AuditLogFilter filter) {
        return validator.validate(filter).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
