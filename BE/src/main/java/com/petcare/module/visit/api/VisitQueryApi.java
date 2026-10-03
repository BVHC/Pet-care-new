package com.petcare.module.visit.api;

import com.petcare.module.catalog.api.ServiceGroup;

import java.util.List;
import java.util.Optional;

/** Owner: visit (TN, KB) · BE-1. */
public interface VisitQueryApi {

    enum VisitStatus { WAITING, IN_PROGRESS, COMPLETED, CANCELLED }

    record VisitSummary(Long visitId, Long branchId, Long customerId, Long petId, ServiceGroup group,
                        VisitStatus status, Long assigneeId) {}

    /** Sales dùng để kiểm quyền thêm/xóa dòng theo người phụ trách (BR-BH-02, BR-TN-01). */
    Optional<VisitSummary> findVisit(Long visitId);

    /** Visit WAITING/IN_PROGRESS đang gán cho nhân viên (BR-QT-08 chặn vô hiệu hóa, BR-QT-11 liệt kê). */
    List<Long> findUnfinishedVisitIdsAssignedTo(Long accountId);

    /** Thú có Visit WAITING/IN_PROGRESS (BR-KH-05, BR-KH-08). */
    boolean hasOpenVisit(Long petId);

    boolean existsByPet(Long petId);

    List<Long> findVisitIdsByPet(Long petId);

    /** Đã có bệnh án hoặc mũi tiêm → khóa loài (BR-KH-03). */
    boolean hasMedicalHistory(Long petId);
}
