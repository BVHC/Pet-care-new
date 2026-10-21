package com.petcare.module.appointment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.service.AppointmentActions;

/**
 * {@link Appointment} + tên hiển thị + cờ thao tác → {@link AppointmentResponse} (appointment-v1 schema
 * {@code Appointment}). {@code unmappedTargetPolicy = ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch. Mọi target
 * khai tường minh. {@code visitId} do bên gọi tra theo lô qua {@code VisitQueryApi} (null khi lịch chưa có Visit;
 * docs/adr/0058).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AppointmentMapper {

    /** Tên hiển thị đọc từ module khác trong transaction của use case. */
    record Names(String customerName, String petName, String branchName, String serviceName) {}

    @Mapping(target = "appointmentId", source = "appointment.id")
    @Mapping(target = "code", source = "appointment.code")
    @Mapping(target = "customerId", source = "appointment.customerId")
    @Mapping(target = "customerName", source = "names.customerName")
    @Mapping(target = "petId", source = "appointment.petId")
    @Mapping(target = "petName", source = "names.petName")
    @Mapping(target = "branchId", source = "appointment.branchId")
    @Mapping(target = "branchName", source = "names.branchName")
    @Mapping(target = "serviceId", source = "appointment.serviceId")
    @Mapping(target = "serviceName", source = "names.serviceName")
    @Mapping(target = "serviceGroup", source = "appointment.serviceGroup")
    @Mapping(target = "slotDate", source = "appointment.slotDate")
    @Mapping(target = "slotStart", source = "appointment.slotStart")
    @Mapping(target = "status", source = "appointment.status")
    @Mapping(target = "channel", source = "appointment.channel")
    @Mapping(target = "rescheduleCount", source = "appointment.rescheduleCount")
    @Mapping(target = "lateCancel", source = "appointment.lateCancel")
    @Mapping(target = "cancelSource", source = "appointment.cancelSource")
    @Mapping(target = "cancelReason", source = "appointment.cancelReason")
    @Mapping(target = "cancelledAt", source = "appointment.cancelledAt")
    @Mapping(target = "note", source = "appointment.note")
    @Mapping(target = "visitId", source = "visitId")
    @Mapping(target = "canReschedule", source = "flags.canReschedule")
    @Mapping(target = "canCancel", source = "flags.canCancel")
    AppointmentResponse toResponse(Appointment appointment, Names names, AppointmentActions.Flags flags, Long visitId);
}
