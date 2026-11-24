package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Chuồng#6, #7 (docs/09 §C, docs/adr/0148): chuồng điều trị của nội trú. Mã chuồng theo lô cho danh sách ca nội trú
 * (docs/adr/0149 mục 19): tập rỗng không chạy SQL, id vắng thì vắng trong map.
 */
class KennelTreatmentServiceTest {

    private static final long KENNEL = 9L;
    private static final long BRANCH = 7L;

    private KennelRepository kennels;
    private KennelTreatmentService service;

    @BeforeEach
    void setUp() {
        kennels = mock(KennelRepository.class);
        service = new KennelTreatmentService(kennels, new KennelTransitionHandler());
    }

    private Kennel kennel(KennelStatus status, long branchId) {
        Kennel kennel = Kennel.create(branchId, 5L, "C-01", null);
        ReflectionTestUtils.setField(kennel, "id", KENNEL);
        ReflectionTestUtils.setField(kennel, "status", status);
        when(kennels.findByIdForUpdate(KENNEL)).thenReturn(Optional.of(kennel));
        return kennel;
    }

    @Test
    void occupyForTreatmentLocksTheKennelThenTakesAnAvailableKennelOfTheSameBranch() {
        Kennel kennel = kennel(KennelStatus.AVAILABLE, BRANCH);

        service.occupyForTreatment(KENNEL, BRANCH);

        InOrder inOrder = inOrder(kennels);
        inOrder.verify(kennels).findByIdForUpdate(KENNEL);
        inOrder.verify(kennels).saveAndFlush(kennel);
        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.OCCUPIED);
    }

    @Test
    void occupyForTreatmentRefusesAKennelOfAnotherBranchWithoutWriting() {
        Kennel kennel = kennel(KennelStatus.AVAILABLE, BRANCH + 1);

        assertThatThrownBy(() -> service.occupyForTreatment(KENNEL, BRANCH))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-13"));

        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.AVAILABLE);
        verify(kennels, never()).saveAndFlush(any());
    }

    @Test
    void occupyForTreatmentRefusesAKennelThatIsNotAvailable() {
        for (KennelStatus status : new KennelStatus[] {KennelStatus.OCCUPIED, KennelStatus.MAINTENANCE}) {
            Kennel kennel = kennel(status, BRANCH);

            assertThatThrownBy(() -> service.occupyForTreatment(KENNEL, BRANCH)).as(status.name())
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-13"));

            assertThat(kennel.getStatus()).isEqualTo(status);
        }
        verify(kennels, never()).saveAndFlush(any());
    }

    @Test
    void unknownKennelIsNotFound() {
        when(kennels.findByIdForUpdate(KENNEL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.occupyForTreatment(KENNEL, BRANCH))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.release(KENNEL)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void releaseFreesAnOccupiedKennel() {
        Kennel kennel = kennel(KennelStatus.OCCUPIED, BRANCH);

        service.release(KENNEL);

        InOrder inOrder = inOrder(kennels);
        inOrder.verify(kennels).findByIdForUpdate(KENNEL);
        inOrder.verify(kennels).saveAndFlush(kennel);
        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.AVAILABLE);
    }

    /** Chuồng#7 chỉ đi từ OCCUPIED: giải phóng chuồng đang bảo trì hoặc đã trống là 409. */
    @Test
    void releaseRefusesAKennelThatIsNotOccupied() {
        for (KennelStatus status : new KennelStatus[] {KennelStatus.AVAILABLE, KennelStatus.MAINTENANCE}) {
            Kennel kennel = kennel(status, BRANCH);

            assertThatThrownBy(() -> service.release(KENNEL)).as(status.name())
                    .isInstanceOf(InvalidStateTransitionException.class);

            assertThat(kennel.getStatus()).isEqualTo(status);
        }
        verify(kennels, never()).saveAndFlush(any());
    }

    @Test
    void everyApiMethodRunsInTheCallersTransaction() {
        for (Method method : KennelApi.class.getDeclaredMethods()) {
            Method impl = org.springframework.util.ReflectionUtils.findMethod(KennelTreatmentService.class,
                    method.getName(), method.getParameterTypes());
            assertThat(impl).as(method.getName()).isNotNull();
            Transactional tx = impl.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }

    // ================================================================ mã chuồng theo lô (inpatient-v1 #2)

    private static KennelRepository.IdCode row(long id, String code) {
        return new KennelRepository.IdCode() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getCode() {
                return code;
            }
        };
    }

    @Test
    void codesByKennelIdsMapEachFoundKennelAndSkipMissingOnes() {
        when(kennels.findCodesByIdIn(List.of(9L, 10L))).thenReturn(List.of(row(9L, "C-01")));

        assertThat(service.findCodesByKennelIds(List.of(9L, 10L))).isEqualTo(Map.of(9L, "C-01"));
    }

    @Test
    void codesByBookingIdsMapEachBookingToItsKennelCode() {
        when(kennels.findCodesByBookingIdIn(List.of(301L, 302L)))
                .thenReturn(List.of(row(301L, "C-01"), row(302L, "M-02")));

        assertThat(service.findCodesByBookingIds(List.of(301L, 302L)))
                .isEqualTo(Map.of(301L, "C-01", 302L, "M-02"));
    }

    @Test
    void anEmptyOrNullIdSetRunsNoSql() {
        assertThat(service.findCodesByKennelIds(List.of())).isEmpty();
        assertThat(service.findCodesByBookingIds(List.of())).isEmpty();
        assertThat(service.findCodesByKennelIds(null)).isEmpty();
        assertThat(service.findCodesByBookingIds(null)).isEmpty();

        verifyNoInteractions(kennels);
    }
}
