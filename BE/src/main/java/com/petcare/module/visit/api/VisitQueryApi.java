package com.petcare.module.visit.api;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.Species;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Visit của các lịch hẹn, theo lô: {@code appointmentId → visitId}; lịch chưa có Visit thì không có khóa trong map
     * (appointment-v1 {@code Appointment.visitId}, nợ D028; docs/adr/0058). Một câu SQL bất kể số id; tập rỗng → map
     * rỗng, không chạy SQL. Bên gọi truyền tối đa một trang (≤ 100 id).
     */
    Map<Long, Long> findVisitIdsByAppointmentIds(Collection<Long> appointmentIds);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55b, docs/06 §6b, docs/adr/0113): ba method dưới đây là placeholder dạng default — ném
    // UnsupportedOperationException tới khi VisitQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Nhóm tuổi của thú theo docs/07 §7.3 (L315): JUNIOR &lt; 52 tuần; SENIOR từ ngưỡng theo loài (ai.reco.senior_age_years_*);
     * ADULT ở giữa; UNKNOWN thiếu ngày sinh. Kiểu này do visit giữ để {@link #countCompletedByService} không phải import
     * module {@code ai}; {@code boarding} (I-7) chọn dùng lại hay tự khai báo thì thống nhất ở T78 (docs/adr/0113).
     */
    enum AgeGroup { JUNIOR, ADULT, SENIOR, UNKNOWN }

    /**
     * I-6 (docs/07 §11): {@code serviceId → ngày dùng gần nhất} trong các Visit {@code COMPLETED} của thú. Dịch vụ chưa dùng
     * thì vắng mặt. Đọc không khóa. Bản thật trước 7/12 (T78).
     */
    default Map<Long, LocalDate> findLastServiceDates(Long petId) {
        throw placeholder("findLastServiceDates", "T78");
    }

    /**
     * I-6 (docs/07 §11): {@code serviceId → số lượt Visit COMPLETED} từ {@code since} (ngày hoàn tất, tính cả ngày đó) của
     * các thú thuộc {@code species} và {@code ageGroup}, toàn chuỗi; dịch vụ không có lượt thì vắng mặt. Đọc không khóa.
     * Bản thật trước 7/12 (T78).
     */
    default Map<Long, Long> countCompletedByService(Species species, AgeGroup ageGroup, LocalDate since) {
        throw placeholder("countCompletedByService", "T78");
    }

    /**
     * BR-CN-07, BR-CN-08 (docs/08 §F.1): số Visit {@code WAITING} / {@code IN_PROGRESS} của chi nhánh. Đọc không khóa.
     * Bản thật trước 7/12 (T75).
     */
    default long countOpenByBranch(Long branchId) {
        throw placeholder("countOpenByBranch", "T75");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("VisitQueryApi." + method + " chưa cài (placeholder T55b, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
