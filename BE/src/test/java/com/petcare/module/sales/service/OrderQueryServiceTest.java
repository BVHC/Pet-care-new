package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.sales.api.OrderQueryApi.UnpaidOrderRef;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.sales.repository.SalesOrderRepository.UnpaidRow;
import com.petcare.module.visit.api.VisitQueryApi;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @Mock
    SalesOrderRepository orders;
    @Mock
    VisitQueryApi visits;
    @Mock
    BoardingQueryApi boardings;
    @InjectMocks
    OrderQueryService service;

    @Test
    void petWithoutVisitsOrBookingsNeverTouchesOrders() {
        when(visits.findVisitIdsByPet(7L)).thenReturn(List.of());
        when(boardings.findBookingIdsByPet(7L)).thenReturn(List.of());

        assertThat(service.hasPendingOrderForPet(7L)).isFalse();

        verifyNoInteractions(orders);
    }

    @Test
    void pendingVisitOrderBlocksWithoutAskingBoarding() {
        when(visits.findVisitIdsByPet(7L)).thenReturn(List.of(1L, 2L));
        when(orders.existsByStatusAndVisitIdIn(OrderStatus.PENDING, List.of(1L, 2L))).thenReturn(true);

        assertThat(service.hasPendingOrderForPet(7L)).isTrue();

        verify(boardings, never()).findBookingIdsByPet(any());
    }

    @Test
    void pendingBoardingOrderBlocksWhenVisitsHaveNone() {
        when(visits.findVisitIdsByPet(7L)).thenReturn(List.of(1L));
        when(orders.existsByStatusAndVisitIdIn(OrderStatus.PENDING, List.of(1L))).thenReturn(false);
        when(boardings.findBookingIdsByPet(7L)).thenReturn(List.of(5L));
        when(orders.existsByStatusAndBoardingBookingIdIn(OrderStatus.PENDING, List.of(5L))).thenReturn(true);

        assertThat(service.hasPendingOrderForPet(7L)).isTrue();
    }

    @Test
    void unpaidOrdersAreOpenAndPendingOnly() {
        UnpaidRow a = row(10L, 1L);
        UnpaidRow b = row(11L, 2L);
        when(orders.findIdAndBranchByCustomer(3L, List.of(OrderStatus.OPEN, OrderStatus.PENDING)))
                .thenReturn(List.of(a, b));

        assertThat(service.findUnpaidOrdersByCustomer(3L))
                .containsExactly(new UnpaidOrderRef(10L, 1L), new UnpaidOrderRef(11L, 2L));
    }

    private static UnpaidRow row(Long id, Long branchId) {
        return new UnpaidRow() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Long getBranchId() {
                return branchId;
            }
        };
    }
}
