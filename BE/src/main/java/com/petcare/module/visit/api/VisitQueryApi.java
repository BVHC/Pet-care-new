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

    /** Một lượt chưa kết thúc gán cho nhân viên; {@code status} chỉ là WAITING hoặc IN_PROGRESS. */
    record AssignedVisit(Long visitId, Long branchId, VisitStatus status) {}

    /**
     * Visit WAITING/IN_PROGRESS đang gán cho nhân viên, theo {@code visitId} tăng dần (BR-QT-08 chặn vô hiệu hóa /
     * điều chuyển; BR-QT-11, BR-TN-08 liệt kê khi khóa — lượt IN_PROGRESS là lượt cần gán lại). Đọc thuần, không khóa,
     * chạy trong transaction của bên gọi (docs/adr/0031, 0035). Thay {@code findUnfinishedVisitIdsAssignedTo} từ
     * 2026-10-12 (khóa tài khoản cần chi nhánh và trạng thái của từng lượt).
     */
    List<AssignedVisit> findUnfinishedVisitsAssignedTo(Long accountId);

    /** Thú có Visit WAITING/IN_PROGRESS (BR-KH-05, BR-KH-08). */
    boolean hasOpenVisit(Long petId);

    boolean existsByPet(Long petId);

    List<Long> findVisitIdsByPet(Long petId);

    /** Đã có bệnh án hoặc mũi tiêm → khóa loài (BR-KH-03). */
    boolean hasMedicalHistory(Long petId);
}
