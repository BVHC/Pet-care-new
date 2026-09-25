package com.petcare.module.inventory.mapper;

import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.entity.InventoryItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@code sku} không nằm trên {@link InventoryItem} (cross-module, đọc qua
 * {@code ProductService#getProductForCrossModule}) — truyền vào như tham số nguồn thứ 2, MapStruct
 * tự khớp theo tên property {@code sku} trên {@link InventoryItemResponse}, cùng cách
 * {@code lowStock} lấy qua expression gọi thẳng {@link InventoryItem#isLowStock()} (khác
 * {@code ProductMapper.isActive} do đây là field tính toán từ 2 field khác, không phải property
 * đơn lệch tên).
 */
@Mapper(componentModel = "spring")
public interface InventoryItemMapper {

    @Mapping(target = "lowStock", expression = "java(item.isLowStock())")
    InventoryItemResponse toResponse(InventoryItem item, String sku);
}
