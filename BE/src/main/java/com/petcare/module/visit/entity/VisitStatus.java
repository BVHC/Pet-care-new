package com.petcare.module.visit.entity;

/**
 * Trạng thái lượt tiếp nhận ({@code visits.status}, 03 §4, BR-TN-09). {@code COMPLETED}, {@code CANCELLED} là trạng
 * thái cuối. Chuyển trạng thái theo {@code VisitTransitionHandler}. Bản công khai cho module khác là
 * {@code VisitQueryApi.VisitStatus} (cùng tên hằng số, docs/adr/0058).
 */
public enum VisitStatus {
    WAITING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
