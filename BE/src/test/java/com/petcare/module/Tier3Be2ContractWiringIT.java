package com.petcare.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.sales.api.CashierShiftApi;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CollectMethod;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.shop.api.OnlineOrderApi;

/**
 * Nối dây các interface chéo tầng 3 của BE-2 trong Spring thật (T55c, docs/adr/0117): mỗi interface có đúng một bean (không
 * mơ hồ khi tiêm) và method mới mà bản thật chưa ghi đè ném {@link UnsupportedOperationException} chứ không trả giá trị giả.
 * Với interface đã có bản thật (inventory, sales, customer, catalog, branch, boarding) bean là lớp thật kế thừa thân
 * {@code default}; với ba interface mới (shop, sales, payment) bean là {@code *Placeholder}. Gọi trong transaction vì bản
 * thật của method ghi chạy {@code MANDATORY} (06 §1).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class Tier3Be2ContractWiringIT {

    @Autowired private TransactionTemplate tx;
    @Autowired private StockApi stock;
    @Autowired private StockQueryApi stockQuery;
    @Autowired private OrderQueryApi orders;
    @Autowired private CashierShiftQueryApi shiftQuery;
    @Autowired private CashierShiftApi shifts;
    @Autowired private CustomerQueryApi customers;
    @Autowired private BoardingQueryApi boarding;
    @Autowired private OnlineOrderApi onlineOrders;
    @Autowired private PaymentGatewayClient gateway;

    @Test
    void existingRealBeansInheritThePlaceholderDefaults() {
        StockApi.OrderRef ref = new StockApi.OrderRef(1L, 2L);

        assertPlaceholder(() -> stock.reserve(1L, ref, List.of()));
        assertPlaceholder(() -> stock.transferOut(1L, 2L, List.of(), 3L));
        assertPlaceholder(() -> stockQuery.findAvailableQuantity(1L, Set.of(1L)));
        assertPlaceholder(() -> stockQuery.sumQuantityByBranch(1L));
        assertPlaceholder(() -> orders.findLinesForReturn(1L));
        assertPlaceholder(() -> orders.countOpenByBranch(1L));
        assertPlaceholder(() -> shiftQuery.hasOpenShift(1L));
        assertPlaceholder(() -> customers.findAddress(1L, 2L));
        assertPlaceholder(() -> boarding.countActiveStaysByBranch(1L));
    }

    @Test
    void newInterfacesAreBackedByTheirPlaceholderBean() {
        assertThat(onlineOrders.getClass().getSimpleName()).isEqualTo("OnlineOrderApiPlaceholder");
        assertThat(shifts.getClass().getSimpleName()).isEqualTo("CashierShiftApiPlaceholder");
        assertThat(gateway.getClass().getSimpleName()).isEqualTo("PaymentGatewayClientPlaceholder");

        assertPlaceholder(() -> onlineOrders.markPaid(1L, "TXN-1"));
        assertPlaceholder(() -> shifts.recordRefund(1L, CollectMethod.CASH, 1_000L));
        assertPlaceholder(() -> gateway.queryTransaction("TXN-1"));
    }

    private void assertPlaceholder(Runnable call) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> call.run()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("placeholder T55c");
    }
}
