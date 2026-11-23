package com.petcare.module.workforce.dto;

/**
 * Ảnh chụp yêu cầu nghỉ / đổi ca cho {@code before} / {@code after} của audit (BR-NS-10, docs/adr/0150). Enum là chuỗi
 * để bản ghi audit không phụ thuộc cấu hình Jackson; không có khóa nào chứa password / otp / token / secret / cccd /
 * pin. Dùng lại được cho duyệt yêu cầu (#13) mà không đổi cấu trúc.
 */
public record ShiftRequestAuditSnapshot(Long shiftAssignmentId, Long requesterId, String type,
        Long targetShiftDefinitionId, String status, Long decidedBy, String decisionNote) {
}
