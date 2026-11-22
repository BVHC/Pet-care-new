package com.petcare.module.workforce.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.dto.ShiftWeeklySummaryResponse;

/**
 * Thống kê tuần của quản lý (workforce-v1 #16, docs/adr/0138): ghép nhân viên với hai số service đã tính, <b>không chạm
 * DB</b>. Mọi trường đích khai báo tường minh và {@code unmappedTargetPolicy = ERROR}. {@code meetsRequirement} suy ra
 * ngay ở đây từ hai số đứng cạnh nó nên không thể lệch với chúng; {@code online}, {@code branchId}, {@code active} của
 * nguồn không có trường đích nên không ra JSON.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShiftWeeklySummaryMapper {

    @Mapping(target = "staffId", source = "staff.accountId")
    @Mapping(target = "staffName", source = "staff.fullName")
    @Mapping(target = "role", source = "staff.role")
    @Mapping(target = "shiftCount", source = "shiftCount")
    @Mapping(target = "requiredShifts", source = "requiredShifts")
    @Mapping(target = "meetsRequirement", expression = "java(shiftCount >= requiredShifts)")
    ShiftWeeklySummaryResponse toResponse(StaffSummary staff, int shiftCount, int requiredShifts);
}
