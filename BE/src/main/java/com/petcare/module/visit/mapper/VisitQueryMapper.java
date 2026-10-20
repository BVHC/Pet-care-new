package com.petcare.module.visit.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.repository.VisitRepository.AssignedRow;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;

/**
 * Projection của {@code VisitRepository} → record của {@link VisitQueryApi}. Enum trạng thái map theo tên: MapStruct báo
 * lỗi biên dịch nếu một hằng số của {@link VisitStatus} không có trong {@link VisitQueryApi.VisitStatus}
 * (docs/adr/0058).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VisitQueryMapper {

    VisitQueryApi.VisitSummary toSummary(SummaryRow row);

    VisitQueryApi.AssignedVisit toAssigned(AssignedRow row);

    VisitQueryApi.VisitStatus toApiStatus(VisitStatus status);
}
