package com.petcare.module.identity.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * R1 {@code from > to}; R2 {@code entityId} không kèm {@code entityType} (index là {@code (entity_type, entity_id)});
 * R3 {@code action} không có mốc chọn lọc thì phải có {@code from}, {@code to} cách nhau ≤ 31 ngày (không có index trên
 * {@code action} — docs/adr/0038 mục 3, 6). Mọi luật vi phạm đều được báo, mỗi luật một lỗi.
 */
public class AuditLogFilterValidator implements ConstraintValidator<ValidAuditLogFilter, AuditLogFilter> {

    static final String RANGE_MESSAGE = "Thời điểm kết thúc không được trước thời điểm bắt đầu";
    static final String ENTITY_TYPE_MESSAGE = "Bắt buộc khi lọc theo entityId";
    static final String ACTION_WINDOW_MESSAGE = "Lọc theo hành động cần from và to cách nhau tối đa 31 ngày, "
            + "hoặc kèm actorAccountId hay entityType + entityId";

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
        if (filter.action() != null && !filter.hasSelectiveAnchor() && !filter.hasBoundedWindow()) {
            reject(context, "from", ACTION_WINDOW_MESSAGE);
            valid = false;
        }
        return valid;
    }

    private static void reject(ConstraintValidatorContext context, String parameter, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(parameter).addConstraintViolation();
    }
}
