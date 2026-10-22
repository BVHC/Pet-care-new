package com.petcare.module.sales.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.petcare.module.inventory.api.StockApi.OrderSale;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.sales.dto.ShortageLineResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.exception.OrderAmountOverflowException;

/**
 * Phần thuần của thu tiền (docs/adr/0064): dòng nào trừ kho, dòng nào thiếu tồn, tổng tiền. Chỉ dòng {@code GOODS} và
 * {@code DRUG} trừ kho lúc thu; {@code VACCINE} đã trừ khi tiêm (BR-KB-04, BR-BH-04), {@code SERVICE} / {@code BOARDING}
 * không có hàng.
 */
final class PaymentLines {

    private static final Set<OrderLineType> STOCK_TYPES = EnumSet.of(OrderLineType.GOODS, OrderLineType.DRUG);

    private PaymentLines() {
    }

    /** Dòng kho theo từng Order; Order không có dòng kho thì bỏ khỏi danh sách. */
    static List<OrderSale> stockLines(List<SalesOrder> orders) {
        List<OrderSale> sales = new ArrayList<>();
        for (SalesOrder order : orders) {
            List<SaleLine> lines = order.getLines().stream().filter(PaymentLines::isStockLine)
                    .map(line -> new SaleLine(line.getProductId(), line.getQuantity())).toList();
            if (!lines.isEmpty()) {
                sales.add(new OrderSale(order.getId(), lines));
            }
        }
        return sales;
    }

    /**
     * Mỗi dòng của sản phẩm mà tổng số lượng cần (qua mọi Order) lớn hơn tồn khả dụng; theo {@code orderId} rồi
     * {@code lineId} tăng dần. {@code availableByProduct} thiếu key = 0.
     */
    static List<ShortageLineResponse> shortageLines(List<SalesOrder> orders, Map<Long, Integer> availableByProduct) {
        Map<Long, Long> needed = new TreeMap<>();
        for (SalesOrder order : orders) {
            order.getLines().stream().filter(PaymentLines::isStockLine)
                    .forEach(line -> needed.merge(line.getProductId(), (long) line.getQuantity(), Long::sum));
        }
        List<ShortageLineResponse> shortages = new ArrayList<>();
        orders.stream().sorted(Comparator.comparing(SalesOrder::getId)).forEach(order ->
                order.getLines().stream().filter(PaymentLines::isStockLine)
                        .sorted(Comparator.comparing(OrderLine::getId)).forEach(line -> {
                            int available = availableByProduct.getOrDefault(line.getProductId(), 0);
                            if (needed.get(line.getProductId()) > available) {
                                shortages.add(new ShortageLineResponse(order.getId(), line.getId(),
                                        line.getLineType(), line.getProductId(), line.getDescription(),
                                        line.getQuantity(), available));
                            }
                        }));
        return shortages;
    }

    /** Tổng tiền các Order; tràn → 400 như tổng một Order. */
    static long total(List<SalesOrder> orders) {
        long sum = 0;
        try {
            for (SalesOrder order : orders) {
                sum = Math.addExact(sum, order.getTotalAmount());
            }
        } catch (ArithmeticException overflow) {
            throw new OrderAmountOverflowException();
        }
        return sum;
    }

    private static boolean isStockLine(OrderLine line) {
        return STOCK_TYPES.contains(line.getLineType());
    }
}
