package com.petcare.module.shop.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
import com.petcare.module.shop.dto.CartItemResponse;
import com.petcare.module.shop.entity.CartItem;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CartMapper {

    /** {@code display} null (sản phẩm không có dữ liệu hiển thị): đơn vị rỗng, không ảnh. */
    @Mapping(target = "productId", source = "item.productId")
    @Mapping(target = "name", source = "info.name")
    @Mapping(target = "unit", expression = "java(display == null ? \"\" : display.unit())")
    @Mapping(target = "unitPrice", source = "info.price")
    @Mapping(target = "quantity", source = "item.quantity")
    @Mapping(target = "imageUrl", expression = "java(display == null ? null : display.imageUrl())")
    @Mapping(target = "available", source = "available")
    CartItemResponse toItem(CartItem item, ProductInfo info, ProductSaleDisplay display, boolean available);
}
