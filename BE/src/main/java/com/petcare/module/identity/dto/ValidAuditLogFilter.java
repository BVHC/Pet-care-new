package com.petcare.module.identity.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Ba luật chéo tham số của {@link AuditLogFilter} (docs/adr/0038 mục 3). Lỗi gắn vào đúng tên tham số query để FE hiện
 * dưới ô lọc: R1 → {@code to}, R2 → {@code entityType}, R3 → {@code from}.
 */
@Documented
@Constraint(validatedBy = AuditLogFilterValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAuditLogFilter {

    String message() default "Bộ lọc nhật ký audit không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
