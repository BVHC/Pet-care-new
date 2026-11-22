package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã flush</b> trong workforce-v1 #5 (docs/adr/0138 bước 3): INSERT phân ca đã chạy, rồi bước audit hoặc
 * bước dựng response ném lỗi — toàn bộ use case phải rollback (không còn dòng nào) và lời gọi sau đó, khi lỗi đã hết, tạo
 * đúng một dòng. Thêm nhánh vi phạm {@code ux_shift_assignments_scheduled} (lưới đỡ): phải nổi ra ngay tại
 * {@code saveAndFlush} và thành 409 {@code CONCURRENCY_CONFLICT}, không ghi gì, không audit. Tách riêng vì
 * {@code @MockitoBean} thay bean của cả context (khuôn {@code ShiftDefinitionRollbackIT}). Era <b>năm 2052</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentRollbackIT.TestBeans.class})
class ShiftAssignmentRollbackIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2052-03-14T01:00:00Z");
    private static final String DAY = "2052-03-21";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoBean
    private AuditRecorder audit;
    @MockitoBean
    private ShiftAssignmentMapper mapper;
    @MockitoSpyBean
    private ShiftAssignmentRepository assignmentRepository;

    private long branch;
    private long staff;
    private long morning;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        long manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    @Test
    void anAuditFailureAfterTheInsertRollsTheNewAssignmentBack() {
        doThrow(new IllegalStateException("audit down")).when(audit).record(any(AuditEntry.class));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(assignmentCount(branch)).isZero();
    }

    @Test
    void aResponseBuildingFailureAfterTheAuditRollsTheAssignmentBackAndARetryCreatesItOnce() {
        org.mockito.Mockito.when(mapper.toSnapshot(any(ShiftAssignment.class))).thenReturn(
                new ShiftAssignmentAuditSnapshot(staff, morning, DAY, "SCHEDULED", "MANAGER", null));
        doThrow(new IllegalStateException("mapper broke")).when(mapper)
                .toResponse(any(ShiftAssignment.class), any(), any(ShiftDefinition.class), any());

        ResponseEntity<Map<String, Object>> failed = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(assignmentCount(branch)).isZero();
        verify(audit, times(1)).record(any(AuditEntry.class));

        reset(mapper);
        ResponseEntity<Map<String, Object>> retry = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(assignmentCount(branch)).as("tạo đúng một lần sau khi hết lỗi").isEqualTo(1);
        assertThat(assignmentRow(assignmentIdsOfStaff(staff).get(0))).containsEntry("status", "SCHEDULED");
    }

    @Test
    void aUniqueViolationThatSlipsPastTheOverlapCheckIs409AndWritesNothing() {
        data.assignment(branch, staff, morning, LocalDate.of(2052, 3, 21), "SCHEDULED", "MANAGER", null);
        // Mô phỏng lọt khóa: kiểm chồng giờ báo "không có", nên chỉ còn unique index chặn.
        doReturn(List.of()).when(assignmentRepository).findOverlapping(any(), any(), any(), any());

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(assignmentCountOfStaff(staff)).as("chỉ còn dòng có sẵn").isEqualTo(1);
        verify(audit, never()).record(any(AuditEntry.class));
    }
}
