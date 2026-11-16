package com.petcare.module.shop.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.shop.dto.OnlineOrderLineResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.entity.OnlineOrder;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OnlineOrderMapper {

    @Mapping(target = "onlineOrderId", source = "order.id")
    @Mapping(target = "salesOrderId", source = "order.orderId")
    @Mapping(target = "code", source = "order.code")
    @Mapping(target = "status", source = "order.status")
    @Mapping(target = "branchId", source = "order.branchId")
    @Mapping(target = "branchName", source = "branchName")
    @Mapping(target = "receiverName", source = "order.receiverName")
    @Mapping(target = "receiverPhone", source = "order.receiverPhone")
    @Mapping(target = "shipAddress", source = "order.shipAddress")
    @Mapping(target = "lines", source = "lines")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "shippingFee", source = "order.shippingFee")
    @Mapping(target = "total", expression = "java(subtotal + order.getShippingFee())")
    @Mapping(target = "carrier", source = "order.carrier")
    @Mapping(target = "trackingCode", source = "order.trackingCode")
    @Mapping(target = "redeliveryCount", source = "order.redeliveryCount")
    @Mapping(target = "paymentDueAt", source = "order.paymentDueAt")
    @Mapping(target = "paidAt", source = "order.paidAt")
    @Mapping(target = "shippedAt", source = "order.shippedAt")
    @Mapping(target = "deliveredAt", source = "order.deliveredAt")
    @Mapping(target = "completedAt", source = "order.completedAt")
    @Mapping(target = "cancelledAt", source = "order.cancelledAt")
    @Mapping(target = "cancelReason", source = "order.cancelReason")
    @Mapping(target = "createdAt", source = "order.createdAt")
    @Mapping(target = "refundStatus", ignore = true)
    OnlineOrderResponse toResponse(OnlineOrder order, String branchName, List<OnlineOrderLineResponse> lines,
            long subtotal);
}
