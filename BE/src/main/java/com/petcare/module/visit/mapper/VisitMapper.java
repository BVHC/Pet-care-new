package com.petcare.module.visit.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.dto.AssignResult;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;

/**
 * {@link Visit} + tên hiển thị + Order của lượt → {@link VisitResponse} (visit-v1 schema {@code Visit};
 * docs/adr/0059). {@code unmappedTargetPolicy = ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch; mọi target khai
 * tường minh. Tên hiển thị đi qua {@link Parties} (không nhận record {@code api/} của module khác), nên không thể vô
 * tình map SĐT / email của khách.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VisitMapper {

    /** Tên hiển thị đọc từ module khác trong transaction của use case; {@code assigneeName} null khi chưa gán. */
    record Parties(String customerName, String petName, Species species, String assigneeName) {}

    @Mapping(target = "visitId", source = "visit.id")
    @Mapping(target = "code", source = "visit.code")
    @Mapping(target = "branchId", source = "visit.branchId")
    @Mapping(target = "customerId", source = "visit.customerId")
    @Mapping(target = "customerName", source = "parties.customerName")
    @Mapping(target = "petId", source = "visit.petId")
    @Mapping(target = "petName", source = "parties.petName")
    @Mapping(target = "species", source = "parties.species")
    @Mapping(target = "appointmentId", source = "visit.appointmentId")
    @Mapping(target = "serviceGroup", source = "visit.serviceGroup")
    @Mapping(target = "status", source = "visit.status")
    @Mapping(target = "priorityClass", source = "visit.priorityClass")
    @Mapping(target = "queueSortKey", source = "visit.queueSortKey")
    @Mapping(target = "assigneeId", source = "visit.assigneeId")
    @Mapping(target = "assigneeName", source = "parties.assigneeName")
    @Mapping(target = "needsReassign", source = "visit.needsReassign")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "checkedInAt", source = "visit.checkedInAt")
    @Mapping(target = "calledAt", source = "visit.calledAt")
    @Mapping(target = "completedAt", source = "visit.completedAt")
    @Mapping(target = "cancelledAt", source = "visit.cancelledAt")
    @Mapping(target = "cancelReason", source = "visit.cancelReason")
    VisitResponse toResponse(Visit visit, Parties parties, Long orderId);

    /** visit-v1 #5 (docs/adr/0061): lượt đã map + cảnh báo offline (BR-TN-06); {@code visit} gán thẳng, không map lại. */
    @Mapping(target = "visit", source = "visit")
    @Mapping(target = "assigneeOffline", source = "assigneeOffline")
    AssignResult toAssignResult(VisitResponse visit, boolean assigneeOffline);
}
