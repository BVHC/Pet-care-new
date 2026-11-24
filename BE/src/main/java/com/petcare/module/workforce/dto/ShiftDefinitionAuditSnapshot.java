package com.petcare.module.workforce.dto;

/**
 * Ảnh chụp ca làm việc cho {@code before} / {@code after} của audit (BR-NS-10, docs/adr/0149). Giờ là chuỗi
 * {@code HH:mm} để bản ghi audit không phụ thuộc cấu hình Jackson; không có khóa nào chứa password / token / cccd…
 */
public record ShiftDefinitionAuditSnapshot(String name, String startTime, String endTime, boolean active) {
}
