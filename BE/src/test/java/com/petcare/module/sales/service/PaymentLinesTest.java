package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inventory.api.StockApi.OrderSale;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.sales.dto.ShortageLineResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.exception.OrderAmountOverflowException;

/** Phần thuần của thu tiền: dòng cần trừ kho, dòng thiếu tồn, tổng tiền (BR-BH-04, BR-TG-02; docs/adr/0064). */
class PaymentLinesTest {

    private static SalesOrder order(long id, OrderLine... lines) {
        SalesOrder order = SalesOrder.openRetail("DH-X-" + id, 1L, 2L, 3L);
        ReflectionTestUtils.setField(order, "id", id);
        long lineId = id * 100;
        for (OrderLine line : lines) {
            ReflectionTestUtils.setField(line, "id", ++lineId);
            order.addLine(line);
        }
        order.markPending(Instant.parse("2026-10-20T03:00:00Z"));
        return order;
    }

    private static OrderLine line(OrderLineType type, Long productId, String name, int quantity, long price) {
        Long serviceId = type == OrderLineType.SERVICE || type == OrderLineType.BOARDING ? 9L : null;
        return OrderLine.of(type, serviceId, productId, name, quantity, price, false, 3L, 3L);
    }

    @Test
    void stockLinesKeepOnlyGoodsAndDrug() {
        SalesOrder order = order(10L, line(OrderLineType.GOODS, 20L, "Hạt", 2, 100),
                line(OrderLineType.DRUG, 21L, "Thuốc", 1, 300), line(OrderLineType.VACCINE, 22L, "Vaccine", 1, 400),
                line(OrderLineType.SERVICE, null, "Khám", 1, 500), line(OrderLineType.BOARDING, null, "Đêm", 3, 50));

        assertThat(PaymentLines.stockLines(List.of(order))).containsExactly(
                new OrderSale(10L, List.of(new SaleLine(20L, 2), new SaleLine(21L, 1))));
    }

    @Test
    void ordersWithoutStockLinesAreLeftOut() {
        SalesOrder services = order(10L, line(OrderLineType.SERVICE, null, "Khám", 1, 500));
        SalesOrder goods = order(11L, line(OrderLineType.GOODS, 20L, "Hạt", 1, 100));

        assertThat(PaymentLines.stockLines(List.of(services, goods))).containsExactly(
                new OrderSale(11L, List.of(new SaleLine(20L, 1))));
    }

    @Test
    void shortageListsEveryLineOfAShortProduct() {
        SalesOrder a = order(10L, line(OrderLineType.GOODS, 20L, "Hạt", 2, 100),
                line(OrderLineType.GOODS, 30L, "Cát", 1, 100));
        SalesOrder b = order(11L, line(OrderLineType.GOODS, 20L, "Hạt", 3, 100));

        List<ShortageLineResponse> shortages = PaymentLines.shortageLines(List.of(b, a), Map.of(20L, 3, 30L, 5));

        assertThat(shortages).containsExactly(
                new ShortageLineResponse(10L, 1001L, OrderLineType.GOODS, 20L, "Hạt", 2, 3),
                new ShortageLineResponse(11L, 1101L, OrderLineType.GOODS, 20L, "Hạt", 3, 3));
    }

    @Test
    void noShortageWhenStockCoversTheTotalOfAllOrders() {
        SalesOrder a = order(10L, line(OrderLineType.GOODS, 20L, "Hạt", 2, 100));
        SalesOrder b = order(11L, line(OrderLineType.DRUG, 20L, "Hạt", 3, 100));

        assertThat(PaymentLines.shortageLines(List.of(a, b), Map.of(20L, 5))).isEmpty();
    }

    @Test
    void totalAddsEveryOrderAndZeroOrdersCount() {
        SalesOrder a = order(10L, line(OrderLineType.GOODS, 20L, "Hạt", 2, 100));
        SalesOrder zero = order(11L, line(OrderLineType.SERVICE, null, "Khám miễn phí", 1, 0));

        assertThat(PaymentLines.total(List.of(a, zero))).isEqualTo(200L);
    }

    @Test
    void totalOverflowIsRefused() {
        SalesOrder a = order(10L, line(OrderLineType.GOODS, 20L, "Hạt", 1, Long.MAX_VALUE));
        SalesOrder b = order(11L, line(OrderLineType.GOODS, 20L, "Hạt", 1, 1));

        assertThatThrownBy(() -> PaymentLines.total(List.of(a, b))).isInstanceOf(OrderAmountOverflowException.class);
    }
}
