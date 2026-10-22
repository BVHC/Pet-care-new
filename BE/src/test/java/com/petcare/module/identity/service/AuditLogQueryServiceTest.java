package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.AuditLogEntryResponse;
import com.petcare.module.identity.dto.AuditLogFilter;
import com.petcare.module.identity.mapper.AuditLogMapper;
import com.petcare.module.identity.repository.AuditLogQueryRepository;
import com.petcare.module.identity.repository.AuditLogRow;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * identity-v1 #36 (UC11; docs/adr/0038): validate trước mọi câu SQL, đếm rồi mới lấy trang, kẹp phân trang như #28,
 * transaction readOnly + REPEATABLE READ, không ghi audit (Q5).
 */
class AuditLogQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private static ValidatorFactory factory;
    private static Validator validator;

    private final AuditLogQueryRepository repository = mock(AuditLogQueryRepository.class);
    private final AuditLogMapper mapper = Mappers.getMapper(AuditLogMapper.class);
    private final AuditLogQueryService service = new AuditLogQueryService(repository, mapper, validator);

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
    void countsThenReadsThePageAndMapsEveryRow() {
        AuditLogFilter filter = new AuditLogFilter(5L, null, null, null, null, null);
        AuditLogRow row = new AuditLogRow(1L, 5L, "a@petcare.test", "LOGIN_SUCCEEDED", "accounts", 5L, null,
                "{\"sessionId\": 3}", null, "203.0.113.9", T0);
        when(repository.count(filter)).thenReturn(41L);
        when(repository.findPage(filter, 20L, 20)).thenReturn(List.of(row));

        PageResponse<AuditLogEntryResponse> page = service.listAuditLogs(filter, 1, 20);

        assertThat(page.content()).containsExactly(mapper.toResponse(row));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41L);
        assertThat(page.totalPages()).isEqualTo(3);
        InOrder order = inOrder(repository);
        order.verify(repository).count(filter);
        order.verify(repository).findPage(filter, 20L, 20);
    }

    @Test
    void pageAndSizeAreClampedLikeAccountSearch() {
        AuditLogFilter filter = new AuditLogFilter(null, null, null, null, null, null);
        when(repository.count(any())).thenReturn(0L);
        when(repository.findPage(any(), anyLong(), anyInt())).thenReturn(List.of());

        PageResponse<AuditLogEntryResponse> big = service.listAuditLogs(filter, -3, 1000);
        PageResponse<AuditLogEntryResponse> small = service.listAuditLogs(filter, 2, 0);

        assertThat(big.page()).isZero();
        assertThat(big.size()).isEqualTo(AuditLogQueryService.MAX_PAGE_SIZE);
        assertThat(small.size()).isEqualTo(1);
        verify(repository).findPage(filter, 0L, 100);
        verify(repository).findPage(filter, 2L, 1);
    }

    @Test
    void invalidFilterThrowsBeforeAnyQueryInRuleOrder() {
        AuditLogFilter filter = new AuditLogFilter(null, "LOGIN_FAILED", null, 9L, T0.plusSeconds(1), T0);

        assertThatThrownBy(() -> service.listAuditLogs(filter, 0, 20))
                .isInstanceOfSatisfying(ConstraintViolationException.class, ex -> assertThat(
                        ex.getConstraintViolations().stream().map(v -> v.getPropertyPath().toString()).toList())
                        .as("R1 → R2 (R3 không áp vì khoảng ngược đã là R1)").containsExactly("to", "entityType"));
        verifyNoInteractions(repository);
    }

    @Test
    void allThreeRulesComeOutInFixedOrder() {
        AuditLogFilter r2AndR3 = new AuditLogFilter(null, "LOGIN_FAILED", null, 9L, null, null);

        assertThatThrownBy(() -> service.listAuditLogs(r2AndR3, 0, 20))
                .isInstanceOfSatisfying(ConstraintViolationException.class, ex -> assertThat(
                        ex.getConstraintViolations().stream().map(ConstraintViolation::getPropertyPath)
                                .map(Object::toString).toList())
                        .containsExactly("entityType", "from"));
        verifyNoInteractions(repository);
    }

    @Test
    void transactionIsReadOnlyRepeatableReadWithoutRollbackExceptions() throws NoSuchMethodException {
        Transactional tx = AuditLogQueryService.class
                .getMethod("listAuditLogs", AuditLogFilter.class, int.class, int.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(AuditLogQueryService.class.getAnnotation(Transactional.class)).as("chỉ đặt trên method").isNull();
    }

    @Test
    void viewingWritesNoAuditByConstruction() {
        Constructor<?>[] constructors = AuditLogQueryService.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Arrays.asList(constructors[0].getParameterTypes())).doesNotContain(AuditRecorder.class)
                .containsExactly(AuditLogQueryRepository.class, AuditLogMapper.class, Validator.class);
    }
}
