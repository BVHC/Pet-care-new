package com.petcare.module.workforce.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link StrippedNotBlank}: hợp lệ khi không {@code null} và {@code strip()} không rỗng (cùng phép với entity). */
public class StrippedNotBlankValidator implements ConstraintValidator<StrippedNotBlank, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value != null && !value.toString().strip().isEmpty();
    }
}
