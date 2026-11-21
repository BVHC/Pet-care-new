package com.petcare.module.workforce.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Chuỗi không được rỗng <b>sau {@code String.strip()}</b>, tức cùng định nghĩa "trống" với {@code EntityText.require}
 * của entity workforce. Thay {@code @NotBlank} vì Hibernate Validator dùng {@code trim()} (chỉ cắt ký tự ≤ U+0020): lý
 * do chỉ gồm khoảng trắng Unicode như U+3000 lọt qua DTO, bị {@code strip()} thành rỗng rồi làm
 * {@code EntityText.require} ném {@code IllegalArgumentException}, và {@code GlobalExceptionHandler} trả 500 thay vì 400
 * {@code VALIDATION_FAILED} (docs/adr/0138 bước 7, {@code ShiftReasonRequestTest}). {@code null} cũng là không hợp lệ.
 */
@Documented
@Constraint(validatedBy = StrippedNotBlankValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrippedNotBlank {

    String message() default "Không được để trống";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
