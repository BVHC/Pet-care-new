package com.petcare.module.workforce.mapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;

/**
 * Đăng ký ca theo tuần (workforce-v1 #17; docs/adr/0138): chỉ ghép các giá trị service đã tính, <b>không chạm DB</b>.
 * Mọi trường đích khai báo tường minh (nhiều tham số nguồn nên MapStruct không tự suy ra) và
 * {@code unmappedTargetPolicy = ERROR}. {@code shiftCount} luôn bằng số phần tử của {@code assignments}, nên không có
 * tham số riêng để hai số không thể lệch nhau.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShiftRegistrationMapper {

    @Mapping(target = "weekStart", source = "weekStart")
    @Mapping(target = "requiredShifts", source = "requiredShifts")
    @Mapping(target = "shiftCount", expression = "java(assignments.size())")
    @Mapping(target = "editableUntil", source = "editableUntil")
    @Mapping(target = "assignments", source = "assignments")
    ShiftRegistrationResponse toResponse(LocalDate weekStart, int requiredShifts, Instant editableUntil,
            List<ShiftAssignmentResponse> assignments);
}
