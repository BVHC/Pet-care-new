package com.petcare.module.care.api;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Owner: care (TB, Care Task 🆕) · BE-1. Đây là đầu vào của ST18.
 * Caller: visit (ST04 nhắc tái chủng / tái khám, ghi mũi tiêm), boarding (ST15 quá hạn đón).
 */
public interface CareTaskApi {

    /** Mã ASCII theo erd §0. */
    enum CareTaskType { VACCINE_DUE, VACCINE_OVERDUE, FOLLOW_UP_DUE, PICKUP_OVERDUE }

    /**
     * Care Task#1. {@code sourceId} = vaccinationId (VACCINE_*), visitId của bệnh án (FOLLOW_UP_DUE) hoặc
     * bookingId (PICKUP_OVERDUE). {@code branchId} là chi nhánh phụ trách do caller xác định (BR-TB-02).
     */
    record NewCareTask(CareTaskType type, Long sourceId, Long branchId, Long customerId, Long petId,
                       LocalDate dueDate) {}

    /**
     * Care Task#1: sinh task {@code OPEN}, trả id. <b>Chống trùng:</b> VACCINE_OVERDUE (tối đa 1 / mũi, BR-TB-04) và
     * PICKUP_OVERDUE (tối đa 1 / đặt chỗ) đã có thì trả id cũ, <b>dù task cũ ở trạng thái nào</b> (unique index không
     * phân biệt trạng thái, 06 §8 Q1). VACCINE_DUE và FOLLOW_UP_DUE <b>không</b> chống trùng ở DB: ST04 tự chống bằng
     * {@code due_reminded_at} / {@code follow_up_reminded_at} đặt cùng transaction.
     *
     * <p><b>Nghĩa vụ bên gọi</b> (care không đọc bảng của module khác, docs/adr/0079): (1) trong transaction của mình,
     * <b>READ COMMITTED</b> (REPEATABLE READ làm {@code ON CONFLICT} ném lỗi serialization), không {@code catch};
     * (2) khóa {@code pets FOR SHARE} qua {@code PetQueryApi.findPetForShare} <b>trước</b> dòng nguồn, kiểm thú chưa mất
     * và {@code customerId} là chủ hiện tại (trừ PICKUP_OVERDUE: thú đang lưu trú không đánh dấu mất / chuyển chủ được);
     * (3) {@code branchId} theo BR-TB-02 / BR-LT-10, {@code dueDate} theo erd §11 (tái chủng: {@code next_due_date}; tái
     * khám: {@code follow_up_date}); (4) điều kiện sinh theo loại: DUE chỉ với hồ sơ không email (BR-TB-02),
     * VACCINE_OVERDUE khi quá hạn 7 ngày, chưa tiêm lại, có SĐT (BR-TB-04), PICKUP_OVERDUE khi {@code OVERDUE} ≥ 1 ngày
     * (BR-LT-10); (5) VACCINE_* chỉ cho mũi của lượt {@code COMPLETED} (docs/adr/0075). Mọi trường của task bắt buộc.
     */
    Long create(NewCareTask task);

    /**
     * Care Task#5: hủy task VACCINE_DUE / VACCINE_OVERDUE OPEN của các mũi đã được tiêm lại (BR-TB-03). Chỉ task OPEN
     * đổi; task đã đóng và task của mũi khác giữ nguyên. Tập rỗng không chạm DB; tập của một thú (vài phần tử).
     */
    void cancelForRevaccination(Collection<Long> supersededVaccinationIds);

    /**
     * Care Task#6: hủy task FOLLOW_UP_DUE OPEN của các bệnh án có nhắc tái khám bị hủy (BR-TB-06). Cùng quy ước như
     * {@link #cancelForRevaccination}.
     */
    void cancelFollowUpTasks(Collection<Long> medicalRecordVisitIds);
}
