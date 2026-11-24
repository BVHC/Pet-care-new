package com.petcare.module.workforce.dto;

/**
 * Ảnh chụp phân ca cho {@code before} / {@code after} của audit (BR-NS-10, docs/adr/0149). Ngày và enum là chuỗi để bản
 * ghi audit không phụ thuộc cấu hình Jackson; không có khóa nào chứa password / otp / token / secret / cccd / pin. Dùng
 * lại được cho hủy phân ca và ghi vắng mà không đổi cấu trúc.
 */
public record ShiftAssignmentAuditSnapshot(Long staffId, Long shiftDefinitionId, String workDate, String status,
        String source, String reason) {
}
