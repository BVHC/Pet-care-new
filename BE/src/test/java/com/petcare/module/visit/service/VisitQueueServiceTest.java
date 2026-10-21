package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VisitQueueService} (docs/adr/0060): role kiểm lại trước mọi lần đọc (D-F1), phạm vi trước khi đọc, 404 trước 403
 * phạm vi, rỗng không gọi module khác. Bộ lọc A1 và thứ tự BR-TN-04 trên DB thật ở {@code VisitQueueIT}.
 */
class VisitQueueServiceTest {

    private static final Instant T = Instant.parse("2026-10-20T02:00:00Z");
    private static final VisitQueueFilter NO_FILTER = new VisitQueueFilter(null, null, null, null, null);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final VisitQueueService service = new VisitQueueService(scope, visits, orders, pets, customers, staff,
            Mappers.getMapper(VisitMapper.class), Clock.fixed(T, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(70L).when(principal).accountId();
        doReturn(3L).when(scope).resolve(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER", "CUSTOMER"})
    void chainRolesNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.listQueue(NO_FILTER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.getVisit(9001L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::listMyQueue).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, orders, pets, customers, staff);
        verify(scope, never()).resolve(any());
        verify(scope, never()).check(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "BRANCH_MANAGER"})
    void myQueueIsOnlyForVetAndCaretaker(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(service::listMyQueue).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits);
    }

    @Test
    void queueResolvesTheCallersBranchBeforeReadingAndEmptyCallsNoModule() {
        doReturn("CARETAKER").when(principal).role();
        doReturn(List.of()).when(visits).findAll(any(Specification.class));

        assertThat(service.listQueue(NO_FILTER)).isEmpty();

        InOrder order = inOrder(scope, visits);
        order.verify(scope).resolve(null);
        order.verify(visits).findAll(any(Specification.class));
        verifyNoInteractions(orders, pets, customers, staff);
    }

    @Test
    void myQueueReadsOnlyMyUnfinishedVisits() {
        doReturn("VET").when(principal).role();
        doReturn(List.of()).when(visits).findByAssigneeIdAndStatusIn(anyLong(), anyCollection());

        assertThat(service.listMyQueue()).isEmpty();

        verify(visits).findByAssigneeIdAndStatusIn(70L, VisitQueueService.UNFINISHED);
        verify(scope, never()).resolve(any());
    }

    @Test
    void unknownVisitIs404BeforeAnyScopeCheck() {
        doReturn("VET").when(principal).role();
        doReturn(Optional.empty()).when(visits).findById(9001L);

        assertThatThrownBy(() -> service.getVisit(9001L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy lượt tiếp nhận #9001");

        verify(scope, never()).check(any());
    }

    @Test
    void otherBranchVisitIs403BeforeAnyNameLookup() {
        doReturn("RECEPTIONIST").when(principal).role();
        Visit visit = Visit.checkIn("TN-2610-00001", 4L, 88L, 12L, null, ServiceGroup.MEDICAL, PriorityClass.WALK_IN, T,
                71L, T);
        ReflectionTestUtils.setField(visit, "id", 9001L);
        doReturn(Optional.of(visit)).when(visits).findById(9001L);
        doThrow(new AccessDeniedScopeException("branch:4", "branch:3")).when(scope).check(4L);

        assertThatThrownBy(() -> service.getVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(orders, pets, customers, staff);
    }

    @Test
    void queueSpecWithoutBranchIsAProgrammingError() {
        assertThatThrownBy(() -> VisitQueueService.queueSpec(null, NO_FILTER, LocalDate.of(2026, 10, 20)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Báo lỗi sớm; bằng chứng chính là {@code VisitQueueIT} (docs/adr/0060 F4). */
    @Test
    void everyUseCaseIsReadOnlyRepeatableRead() throws Exception {
        for (Method method : List.of(VisitQueueService.class.getMethod("listQueue", VisitQueueFilter.class),
                VisitQueueService.class.getMethod("listMyQueue"),
                VisitQueueService.class.getMethod("getVisit", Long.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.readOnly()).as(method.getName()).isTrue();
            assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }

    @Test
    void unfinishedMeansWaitingAndInProgress() {
        assertThat(VisitQueueService.UNFINISHED).containsExactlyInAnyOrder(VisitStatus.WAITING,
                VisitStatus.IN_PROGRESS);
    }
}
