package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
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

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.dto.ShiftDefinitionAuditSnapshot;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftDefinitionMapper;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã flush</b> trong workforce-v1 #2 / #3 (docs/adr/0149, plan mục 8 T2): INSERT / UPDATE ca đã chạy, rồi
 * bước audit hoặc bước dựng response ném lỗi. Toàn bộ use case phải rollback (ca về giá trị cũ, không có dòng mới) và
 * lời gọi sau đó, khi lỗi đã hết, thành công đúng một lần. Tách riêng vì {@code @MockitoBean} thay bean của cả context
 * (khuôn {@code CareTaskDeskRollbackIT}). Era <b>năm 2048</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftDefinitionRollbackIT.TestBeans.class})
class ShiftDefinitionRollbackIT extends ShiftDefinitionItSupport {

    private static final Instant NOW = Instant.parse("2048-03-14T01:00:00Z");
    private static final String CREATE_BODY = "{'name':'Ca rollback','startTime':'08:00','endTime':'12:00'}";

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
    private ShiftDefinitionMapper mapper;

    private long branch;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        token = token(data.staff("BRANCH_MANAGER", branch));
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    @Test
    void anAuditFailureAfterTheInsertRollsTheNewShiftBack() {
        doThrow(new IllegalStateException("audit down")).when(audit).record(any(AuditEntry.class));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, token, CREATE_BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(definitionCount(branch)).isZero();
    }

    @Test
    void aResponseBuildingFailureAfterTheAuditRollsTheNewShiftBackAndARetryCreatesItOnce() {
        doThrow(new IllegalStateException("mapper broke")).when(mapper).toResponse(any(ShiftDefinition.class));

        ResponseEntity<Map<String, Object>> failed = call(HttpMethod.POST, PATH, token, CREATE_BODY);
        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(definitionCount(branch)).isZero();

        org.mockito.Mockito.reset(mapper);
        ResponseEntity<Map<String, Object>> retry = call(HttpMethod.POST, PATH, token, CREATE_BODY);

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(definitionCount(branch)).as("tạo đúng một lần sau khi hết lỗi").isEqualTo(1);
    }

    @Test
    void anAuditFailureAfterTheUpdateRestoresTheOldValuesAndARetryAuditsOnce() {
        long id = definition(branch, "Ca", "08:00", "12:00", true);
        String xmin = xmin(id);
        Map<String, Object> before = row(id);
        doThrow(new IllegalStateException("audit down")).when(audit).record(any(AuditEntry.class));

        ResponseEntity<Map<String, Object>> failed = call(HttpMethod.PATCH, PATH + "/" + id, token,
                "{'name':'Tên mới','active':false}");

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(row(id)).isEqualTo(before);
        assertThat(xmin(id)).as("không có phiên bản dòng mới được commit").isEqualTo(xmin);

        org.mockito.Mockito.reset(audit);
        ResponseEntity<Map<String, Object>> retry = call(HttpMethod.PATCH, PATH + "/" + id, token,
                "{'name':'Tên mới','active':false}");

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(row(id)).containsEntry("name", "Tên mới").containsEntry("is_active", false);
        verify(audit, times(1)).record(any(AuditEntry.class));
    }

    @Test
    void aResponseBuildingFailureAfterTheUpdateAndAuditRollsBothBack() {
        long id = definition(branch, "Ca", "08:00", "12:00", true);
        String xmin = xmin(id);
        Map<String, Object> before = row(id);
        doThrow(new IllegalStateException("mapper broke")).when(mapper).toResponse(any(ShiftDefinition.class));
        org.mockito.Mockito.when(mapper.toSnapshot(any(ShiftDefinition.class)))
                .thenReturn(new ShiftDefinitionAuditSnapshot("x", "08:00", "12:00", true));

        ResponseEntity<Map<String, Object>> failed = call(HttpMethod.PATCH, PATH + "/" + id, token,
                "{'startTime':'07:00'}");

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(row(id)).isEqualTo(before);
        assertThat(xmin(id)).isEqualTo(xmin);
        verify(audit, times(1)).record(any(AuditEntry.class));
    }
}
