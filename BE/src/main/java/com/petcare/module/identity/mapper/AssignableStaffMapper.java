package com.petcare.module.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.dto.AssignableStaffResponse;

/**
 * {@code GET /api/staff/assignable} (identity-v1 #38; docs/adr/0089). {@code unmappedTargetPolicy = ERROR}: thiếu một
 * trường của contract là lỗi biên dịch; {@code branchId} và {@code active} của {@link StaffSummary} cố ý không ra ngoài.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssignableStaffMapper {

    AssignableStaffResponse toResponse(StaffSummary staff);
}
