package com.petcare.module.order.mapper;

import com.petcare.module.order.dto.OrderItemResponse;
import com.petcare.module.order.dto.OrderResponse;
import com.petcare.module.order.entity.Order;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * {@code items} do Service tự dựng (cần enrich sku cross-module qua
 * {@code ProductService#getProductForCrossModule}) — truyền vào như tham số nguồn thứ 2, MapStruct
 * tự khớp theo tên property, cùng kiểu {@code PurchaseRequestMapper.toResponse(request, lines)}.
 * {@code subtotal}/{@code discountAmount}/{@code totalAmount}/{@code totalRefundedAmount} là chuỗi
 * thập phân 2 chữ số trên wire (chống sai số IEEE-754), MapStruct tự áp {@link #map(BigDecimal)}
 * cho field khớp tên, cùng kiểu {@code PurchaseOrderMapper}.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "orderId", source = "order.id")
    OrderResponse toResponse(Order order, List<OrderItemResponse> items);

    default String map(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
