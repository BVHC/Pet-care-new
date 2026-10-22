package com.petcare.module.inventory.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.inventory.dto.ReceiptLineResponse;
import com.petcare.module.inventory.dto.StockReceiptResponse;
import com.petcare.module.inventory.entity.StockReceipt;
import com.petcare.module.inventory.entity.StockReceiptLine;

/** Phiếu nhập (inventory-v1 {@code StockReceipt}, {@code ReceiptLine}); tên nhà cung cấp / sản phẩm do service tra và truyền vào. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockReceiptMapper {

    @Mapping(target = "lineId", source = "line.id")
    @Mapping(target = "productId", source = "line.productId")
    @Mapping(target = "productName", source = "productName")
    @Mapping(target = "quantity", source = "line.quantity")
    @Mapping(target = "unitCost", source = "line.unitCost")
    @Mapping(target = "lotNumber", source = "line.lotNumber")
    @Mapping(target = "expiryDate", source = "line.expiryDate")
    @Mapping(target = "stockLotId", source = "line.stockLotId")
    ReceiptLineResponse toLine(StockReceiptLine line, String productName);

    @Mapping(target = "receiptId", source = "receipt.id")
    @Mapping(target = "code", source = "receipt.code")
    @Mapping(target = "branchId", source = "receipt.branchId")
    @Mapping(target = "supplierId", source = "receipt.supplierId")
    @Mapping(target = "supplierName", source = "supplierName")
    @Mapping(target = "receiptDate", source = "receipt.receiptDate")
    @Mapping(target = "status", source = "receipt.status")
    @Mapping(target = "note", source = "receipt.note")
    @Mapping(target = "lines", source = "lines")
    @Mapping(target = "createdBy", source = "receipt.createdBy")
    @Mapping(target = "confirmedBy", source = "receipt.confirmedBy")
    @Mapping(target = "confirmedAt", source = "receipt.confirmedAt")
    @Mapping(target = "cancelledBy", source = "receipt.cancelledBy")
    @Mapping(target = "cancelledAt", source = "receipt.cancelledAt")
    @Mapping(target = "cancelReason", source = "receipt.cancelReason")
    StockReceiptResponse toResponse(StockReceipt receipt, String supplierName, List<ReceiptLineResponse> lines);
}
