package com.petcare.module.branch.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.petcare.module.branch.dto.BranchResponse;
import com.petcare.module.branch.dto.HolidayResponse;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.Holiday;

@Mapper(componentModel = "spring")
public interface BranchMapper {

    @Mapping(target = "branchId", source = "id")
    BranchResponse toResponse(Branch branch);

    @Mapping(target = "holidayId", source = "id")
    HolidayResponse toResponse(Holiday holiday);
}
