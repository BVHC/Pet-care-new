package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.api.VisitApi.TreatmentVisitRef;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitQueryMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Visit#7 + Order#14 (docs/adr/0149 mục 12): kiểm FSM → mã lượt → lưu Visit → mở Order rỗng, đúng thứ tự; tham số thiếu
 * hoặc FSM từ chối thì không ghi gì. {@code lockVisitForAdmission} (mục 16): khóa trước, đọc projection sau.
 */
class TreatmentVisitServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T03:15:30.123456789Z");
    private static final long PET = 12L;
    private static final long CUSTOMER = 88L;
    private static final long BRANCH = 3L;
    private static final long VET = 45L;

    private VisitCodeGenerator codes;
    private VisitRepository visits;
    private VisitOrderApi visitOrders;
    private VisitTransitionHandler transitions;
    private TreatmentVisitService service;

    @BeforeEach
    void setUp() {
        codes = mock(VisitCodeGenerator.class);
        visits = mock(VisitRepository.class);
        visitOrders = mock(VisitOrderApi.class);
        transitions = spy(new VisitTransitionHandler());
        service = new TreatmentVisitService(codes, visits, visitOrders, transitions,
                Mappers.getMapper(VisitQueryMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
        when(codes.nextCode()).thenReturn("TN-2611-00007");
        when(visits.saveAndFlush(any(Visit.class))).thenAnswer(invocation -> {
            Visit visit = invocation.getArgument(0);
            ReflectionTestUtils.setField(visit, "id", 9001L);
            return visit;
        });
        when(visitOrders.openTreatmentOrder(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(7001L);
    }

    @Test
    void opensAnInProgressVisitThenItsEmptyOrderInOrder() {
        TreatmentVisitRef ref = service.startTreatmentVisit(PET, CUSTOMER, BRANCH, VET);

        assertThat(ref).isEqualTo(new TreatmentVisitRef(9001L, 7001L));
        InOrder order = inOrder(transitions, codes, visits, visitOrders);
        order.verify(transitions).validateInitial(VisitStatus.IN_PROGRESS);
        order.verify(codes).nextCode();
        ArgumentCaptor<Visit> saved = ArgumentCaptor.forClass(Visit.class);
        order.verify(visits).saveAndFlush(saved.capture());
        order.verify(visitOrders).openTreatmentOrder(9001L, BRANCH, CUSTOMER, VET);
        order.verifyNoMoreInteractions();

        Visit visit = saved.getValue();
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getCode()).isEqualTo("TN-2611-00007");
        assertThat(visit.getPetId()).isEqualTo(PET);
        assertThat(visit.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(visit.getBranchId()).isEqualTo(BRANCH);
        assertThat(visit.getAssigneeId()).isEqualTo(VET);
        assertThat(visit.getCheckedInBy()).isEqualTo(VET);
        assertThat(visit.getServiceGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.WALK_IN);
        assertThat(visit.getAppointmentId()).isNull();
        Instant micros = Instant.parse("2026-11-23T03:15:30.123456Z");
        assertThat(visit.getCheckedInAt()).isEqualTo(micros);
        assertThat(visit.getCalledAt()).isEqualTo(micros);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void aMissingArgumentWritesNothing(int missing) {
        Long[] args = {PET, CUSTOMER, BRANCH, VET};
        args[missing] = null;

        assertThatThrownBy(() -> service.startTreatmentVisit(args[0], args[1], args[2], args[3]))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(codes, visits, visitOrders);
    }

    @Test
    void aRejectedInitialStateWritesNothing() {
        doThrow(new InvalidStateTransitionException("lượt tiếp nhận", null, "IN_PROGRESS"))
                .when(transitions).validateInitial(VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.startTreatmentVisit(PET, CUSTOMER, BRANCH, VET))
                .isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(codes, visits, visitOrders);
    }

    // ================================================================ lockVisitForAdmission (docs/adr/0149 mục 16)

    @Test
    void lockVisitForAdmissionLocksBeforeReadingTheProjection() {
        when(visits.lockById(9001L)).thenReturn(Optional.of(9001L));
        when(visits.findSummary(9001L)).thenReturn(Optional.of(new SummaryRow(9001L, BRANCH, CUSTOMER, PET,
                ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, VET)));

        Optional<VisitSummary> summary = service.lockVisitForAdmission(9001L);

        assertThat(summary).contains(new VisitSummary(9001L, BRANCH, CUSTOMER, PET, ServiceGroup.MEDICAL,
                com.petcare.module.visit.api.VisitQueryApi.VisitStatus.IN_PROGRESS, VET));
        InOrder order = inOrder(visits);
        order.verify(visits).lockById(9001L);
        order.verify(visits).findSummary(9001L);
        verifyNoInteractions(codes, visitOrders);
    }

    @Test
    void lockVisitForAdmissionOfAMissingVisitIsEmptyAndReadsNothing() {
        when(visits.lockById(9001L)).thenReturn(Optional.empty());

        assertThat(service.lockVisitForAdmission(9001L)).isEmpty();

        verify(visits, never()).findSummary(anyLong());
    }

    @Test
    void lockVisitForAdmissionRejectsANullId() {
        assertThatThrownBy(() -> service.lockVisitForAdmission(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(visits);
    }
}
