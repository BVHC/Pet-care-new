package com.petcare.module.procurement.mapper;

import com.petcare.module.procurement.dto.PurchaseRequestLineResponse;
import com.petcare.module.procurement.dto.PurchaseRequestResponse;
import com.petcare.module.procurement.entity.PurchaseRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * {@code lines} do Service tự dựng (cần enrich sku cross-module qua
 * {@code ProductService#getProductForCrossModule}) — truyền vào như tham số nguồn thứ 2, MapStruct
 * tự khớp theo tên property {@code lines}, cùng kiểu {@code InventoryBatchMapper.toResponse(batch, today)}.
 */
@Mapper(componentModel = "spring")
public interface PurchaseRequestMapper {

    @Mapping(target = "requestId", source = "purchaseRequest.id")
    PurchaseRequestResponse toResponse(PurchaseRequest purchaseRequest, List<PurchaseRequestLineResponse> lines);
}
