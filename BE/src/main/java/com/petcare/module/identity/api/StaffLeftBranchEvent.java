package com.petcare.module.identity.api;

/**
 * Publisher: identity ({@code StaffAccountService.disableStaff} và {@code transferStaff}, BR-QT-09 vô hiệu hóa,
 * BR-QT-06 điều chuyển) · BE-1. Phát đồng bộ, <b>sau mọi guard và sau {@code flush}</b>, trong transaction của thao tác
 * gốc (docs/06 §3, docs/adr/0149 bước 5). Không phát cho nhân viên không có chi nhánh (SUPER_MANAGER).
 * Listener: workforce ({@code StaffLeftBranchListener}, BR-NS-09: phân ca {@code SCHEDULED} chưa bắt đầu của nhân viên tại
 * chi nhánh cũ → {@code CANCELLED}, yêu cầu {@code PENDING} → {@code REJECTED}, cùng transaction). {@code branchId} là chi
 * nhánh <b>cũ</b> mà nhân viên rời, lấy từ hồ sơ đã khóa chứ không từ body của request.
 */
public record StaffLeftBranchEvent(Long staffId, Long branchId) {}
