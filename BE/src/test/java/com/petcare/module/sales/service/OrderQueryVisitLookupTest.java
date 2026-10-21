package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.sales.repository.SalesOrderRepository.VisitOrderRow;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * {@code OrderQueryApi.findOrderIdsByVisitIds} (thêm bởi BE-1, docs/adr/0060): tập rỗng không chạm DB, kết quả là map
 * {@code visitId → orderId} theo đúng chiều, trùng lượt (vi phạm {@code uq_orders_visit_id}) → lỗi. File riêng để không
 * sửa {@code OrderQueryServiceTest} của BE-2.
 */
class OrderQueryVisitLookupTest {

    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final OrderQueryService service = new OrderQueryService(orders, mock(VisitQueryApi.class),
            mock(BoardingQueryApi.class));

    @Test
    void emptySetTouchesNoRepository() {
        assertThat(service.findOrderIdsByVisitIds(List.of())).isEmpty();
        verifyNoInteractions(orders);
    }

    @Test
    void mapsVisitIdToOrderId() {
        doReturn(List.of(row(9001L, 7001L), row(9002L, 7002L))).when(orders).findVisitOrderIds(List.of(9001L, 9002L));

        assertThat(service.findOrderIdsByVisitIds(List.of(9001L, 9002L))).isEqualTo(Map.of(9001L, 7001L, 9002L, 7002L));
    }

    @Test
    void twoOrdersForOneVisitIsAnError() {
        doReturn(List.of(row(9001L, 7001L), row(9001L, 7002L))).when(orders).findVisitOrderIds(List.of(9001L));

        assertThatThrownBy(() -> service.findOrderIdsByVisitIds(List.of(9001L)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static VisitOrderRow row(Long visitId, Long orderId) {
        return new VisitOrderRow() {
            @Override
            public Long getVisitId() {
                return visitId;
            }

            @Override
            public Long getId() {
                return orderId;
            }
        };
    }
}
