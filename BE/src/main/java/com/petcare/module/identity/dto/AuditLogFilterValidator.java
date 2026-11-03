package com.petcare.module.identity.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * R1 {@code from > to}; R2 {@code entityId} không kèm {@code entityType} (index là {@code (entity_type, entity_id)}).
 * R3 (giới hạn 31 ngày khi lọc {@code action}, docs/adr/0038 mục 3) đã bỏ ở ADR-0098. Mọi luật vi phạm đều được báo,
 * mỗi luật một lỗi.
 */
public class AuditLogFilterValidator implements ConstraintValidator<ValidAuditLogFilter, AuditLogFilter> {

    static final String RANGE_MESSAGE = "Thời điểm kết thúc không được trước thời điểm bắt đầu";
    static final String ENTITY_TYPE_MESSAGE = "Bắt buộc khi lọc theo entityId";

    @Override
    public boolean isValid(AuditLogFilter filter, ConstraintValidatorContext context) {
        if (filter == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            reject(context, "to", RANGE_MESSAGE);
            valid = false;
        }
        if (filter.entityId() != null && filter.entityType() == null) {
            reject(context, "entityType", ENTITY_TYPE_MESSAGE);
            valid = false;
        }
        return valid;
    }

    private static void reject(ConstraintValidatorContext context, String parameter, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(parameter).addConstraintViolation();
    }
}
