package com.petcare.module.inventory.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inventory.dto.AdjustmentLineResponse;
import com.petcare.module.inventory.dto.StockAdjustmentResponse;
import com.petcare.module.inventory.entity.StockAdjustment;
import com.petcare.module.inventory.entity.StockAdjustmentLine;

/** Phiếu điều chỉnh (inventory-v1 {@code StockAdjustment}, {@code AdjustmentLine}); sản phẩm / số lô / số dư sau do service tra và truyền vào. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockAdjustmentMapper {

    @Mapping(target = "stockLotId", source = "line.stockLotId")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "productName", source = "productName")
    @Mapping(target = "lotNumber", source = "lotNumber")
    @Mapping(target = "quantityDelta", source = "line.quantityDelta")
    @Mapping(target = "balanceAfter", source = "balanceAfter")
    AdjustmentLineResponse toLine(StockAdjustmentLine line, Long productId, String productName, String lotNumber,
            int balanceAfter);

    @Mapping(target = "adjustmentId", source = "adjustment.id")
    @Mapping(target = "code", source = "adjustment.code")
    @Mapping(target = "branchId", source = "adjustment.branchId")
    @Mapping(target = "reason", source = "adjustment.reason")
    @Mapping(target = "note", source = "adjustment.note")
    @Mapping(target = "lines", source = "lines")
    @Mapping(target = "createdBy", source = "adjustment.createdBy")
    @Mapping(target = "createdAt", source = "adjustment.createdAt")
    StockAdjustmentResponse toResponse(StockAdjustment adjustment, List<AdjustmentLineResponse> lines);
}
