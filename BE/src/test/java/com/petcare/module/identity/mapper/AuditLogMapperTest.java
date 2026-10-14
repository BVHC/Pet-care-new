package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.identity.dto.AuditLogEntryResponse;
import com.petcare.module.identity.repository.AuditLogRow;

/**
 * Nhật ký audit (identity-v1 #36; docs/adr/0038 mục 7): mỗi trường một giá trị khác nhau, so cả record, để map nhầm nguồn
 * là đỏ; trường nullable giữ {@code null}.
 */
class AuditLogMapperTest {

    private final AuditLogMapper mapper = Mappers.getMapper(AuditLogMapper.class);

    @Test
    void everyFieldComesFromItsOwnColumn() {
        Instant createdAt = Instant.parse("2026-10-13T16:55:13.123456Z");
        AuditLogRow row = new AuditLogRow(11L, 22L, "admin@petcare.test", "STAFF_TRANSFERRED", "accounts", 33L,
                "{\"branchId\": 1}", "{\"branchId\": 2}", "lý do", "203.0.113.9", createdAt);

        assertThat(mapper.toResponse(row)).isEqualTo(new AuditLogEntryResponse(11L, 22L, "admin@petcare.test",
                "STAFF_TRANSFERRED", "accounts", 33L, "{\"branchId\": 1}", "{\"branchId\": 2}", "lý do",
                "203.0.113.9", createdAt));
    }

    @Test
    void nullableFieldsStayNull() {
        Instant createdAt = Instant.parse("2026-10-13T00:00:00Z");
        AuditLogRow row = new AuditLogRow(1L, null, null, "LOGIN_FAILED", null, null, null, null, null, null,
                createdAt);

        assertThat(mapper.toResponse(row)).isEqualTo(new AuditLogEntryResponse(1L, null, null, "LOGIN_FAILED", null,
                null, null, null, null, null, createdAt));
    }
}
