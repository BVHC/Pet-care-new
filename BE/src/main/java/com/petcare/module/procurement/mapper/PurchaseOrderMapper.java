package com.petcare.module.procurement.mapper;

import com.petcare.module.procurement.dto.PurchaseOrderLineResponse;
import com.petcare.module.procurement.dto.PurchaseOrderResponse;
import com.petcare.module.procurement.entity.PurchaseOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * {@code supplierName}/{@code lines} do Service tự dựng (cross-module + cross-repository), truyền
 * vào như tham số nguồn phụ, cùng kiểu {@code InventoryItemMapper.toResponse(item, sku)}.
 * {@code totalAmount} là chuỗi thập phân 2 chữ số trên wire (chống sai số IEEE-754), MapStruct tự
 * áp {@link #map(BigDecimal)} cho field khớp tên.
 */
@Mapper(componentModel = "spring")
public interface PurchaseOrderMapper {

    @Mapping(target = "orderId", source = "purchaseOrder.id")
    PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder, String supplierName, List<PurchaseOrderLineResponse> lines);

    default String map(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
