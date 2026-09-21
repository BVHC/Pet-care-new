package com.petcare.module.catalog.mapper;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * docs/api/openapi/catalog-v1.yaml #Money — basePrice/costPrice là chuỗi thập phân 2 chữ số
 * trên wire (chống sai số IEEE-754); {@link #map(BigDecimal)} là default method MapStruct tự
 * áp dụng cho mọi field BigDecimal -> String khớp tên (basePrice, costPrice).
 */
@Mapper(componentModel = "spring")
public interface ProductMapper {

    // isActive: MapStruct không tự khớp Product.isActive() (Lombok, property "active" sau khi bóc
    // tiền tố "is") với ProductResponse record component "isActive" (record accessor giữ nguyên
    // tên, không bóc "is") — 2 bên lệch tên property, generated code để mặc định false nếu không
    // map tường minh bằng expression gọi thẳng getter.
    @Mapping(target = "productId", source = "id")
    @Mapping(target = "isActive", expression = "java(product.isActive())")
    ProductResponse toResponse(Product product);

    default String map(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
