package com.petcare.platform.audit;

import org.springframework.data.repository.Repository;

/**
 * Chỉ khai {@code save}: không có {@code delete*}, {@code saveAll} hay truy vấn sửa (BR-QT-16).
 * Màn hình xem audit (UC11) thuộc module QT, sẽ có repository đọc riêng.
 */
interface AuditLogRepository extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity auditLog);
}
