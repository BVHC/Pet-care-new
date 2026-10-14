package com.petcare.platform.audit;

import org.springframework.data.repository.Repository;

/**
 * Chỉ khai {@code save}: không có {@code delete*}, {@code saveAll} hay truy vấn sửa (BR-QT-16).
 * Màn hình xem audit (UC11) thuộc module QT, đọc qua repository riêng
 * {@code module/identity/repository/AuditLogQueryRepository} (docs/adr/0038).
 */
interface AuditLogRepository extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity auditLog);
}
