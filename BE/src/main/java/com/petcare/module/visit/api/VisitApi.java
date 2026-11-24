package com.petcare.module.visit.api;

import java.util.Optional;

/**
 * Lệnh ghi lên Visit cho module khác (docs/06 §6b hàng 10). Hiện có hai lệnh cho nội trú: Visit điều trị (docs/09 §C,
 * BR-LT-13b; docs/adr/0148 mục 5, docs/adr/0149 mục 12) và khóa lượt khám trước khi nhập viện (docs/adr/0149 mục 16).
 * <p>
 * Chạy trong transaction của bên gọi ({@code MANDATORY}); bên gọi không bắt ngoại lệ của nó.
 */
public interface VisitApi {

    /** Kết quả của Visit#7: lượt điều trị và Order {@code VISIT} của nó. */
    record TreatmentVisitRef(Long visitId, Long orderId) {}

    /**
     * Visit#7 ({@code — → IN_PROGRESS}) kéo theo Order#14 ({@code — → OPEN}, nguồn {@code VISIT}, <b>không có dòng dịch vụ
     * tự sinh</b>): lượt không qua hàng đợi, không lịch hẹn, giao thẳng cho {@code vetId}; không audit, không thông báo;
     * bệnh án tạo khi VET ghi lần đầu, như lượt thường.
     * <p>
     * <b>Bên gọi (inpatient) chịu trách nhiệm</b> mọi điều kiện của BR-LT-13b trước khi gọi: đã khóa thú {@code FOR SHARE},
     * thú chưa mất và đang có đặt chỗ {@code CHECKED_IN} / {@code OVERDUE} của {@code customerId} tại {@code branchId},
     * {@code vetId} là VET đang làm việc ở chi nhánh đó. Method này không kiểm lại các điều kiện đó (docs/adr/0149 mục 12).
     * Thứ tự khóa bên trong: {@code document_counters} (visits) → INSERT {@code visits} → {@code document_counters} (ORDER)
     * → INSERT {@code orders}.
     *
     * @throws IllegalArgumentException tham số null (lỗi lập trình của bên gọi)
     */
    TreatmentVisitRef startTreatmentVisit(Long petId, Long customerId, Long branchId, Long vetId);

    /**
     * Khóa lượt {@code FOR NO KEY UPDATE} rồi đọc lại (nhập viện từ lượt khám, BR-LT-13a; docs/adr/0149 mục 16). Cùng
     * khóa với hoàn tất (Visit#5), gán lại (Visit#4) và mọi thao tác trên lượt, nên trạng thái và người phụ trách trả về
     * đứng yên tới khi transaction của bên gọi kết thúc. Bên gọi đã khóa thú {@code FOR SHARE} trước (thứ tự khóa của
     * visit: {@code pets → visits}) và tự kiểm quyền, chi nhánh, trạng thái, người phụ trách; method này không kiểm.
     *
     * @return rỗng nếu lượt không tồn tại
     * @throws IllegalArgumentException {@code visitId} null
     */
    Optional<VisitQueryApi.VisitSummary> lockVisitForAdmission(Long visitId);
}
