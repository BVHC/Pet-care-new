package com.petcare.module.identity.api;

/**
 * Publisher: identity (BR-QT-06 điều chuyển, BR-QT-09 vô hiệu hóa) · BE-1 — <b>chưa phát</b>: T55b chỉ khai báo lớp
 * (docs/06 §6b hàng 1, docs/adr/0113); nơi phát thêm khi làm workforce (T58).
 * Listener: workforce (BR-NS-09: phân ca {@code SCHEDULED} chưa bắt đầu của nhân viên tại chi nhánh cũ → {@code CANCELLED},
 * yêu cầu {@code PENDING} → {@code REJECTED}, cùng transaction). {@code branchId} là chi nhánh <b>cũ</b> mà nhân viên rời.
 */
public record StaffLeftBranchEvent(Long staffId, Long branchId) {}
