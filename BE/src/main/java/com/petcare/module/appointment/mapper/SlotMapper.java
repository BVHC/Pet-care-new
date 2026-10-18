package com.petcare.module.appointment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.appointment.dto.SlotResponse;
import com.petcare.module.appointment.service.SlotAvailability;

/**
 * {@link SlotAvailability} → {@link SlotResponse} (appointment-v1 schema {@code Slot}). {@code unmappedTargetPolicy =
 * ERROR}: thiếu một trường của hợp đồng là lỗi biên dịch. {@code slotEnd} = giờ bắt đầu + 30 phút; {@code status} theo
 * {@link SlotAvailability#full()} (docs/adr/0042).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SlotMapper {

    @Mapping(target = "slotStart", source = "start")
    @Mapping(target = "slotEnd",
            expression = "java(slot.start().plusMinutes(com.petcare.module.appointment.service.SlotGrid.SLOT_MINUTES))")
    @Mapping(target = "status", expression = "java(slot.full() ? com.petcare.module.appointment.dto.SlotResponse"
            + ".SlotStatus.FULL : com.petcare.module.appointment.dto.SlotResponse.SlotStatus.AVAILABLE)")
    SlotResponse toSlotResponse(SlotAvailability slot);
}
