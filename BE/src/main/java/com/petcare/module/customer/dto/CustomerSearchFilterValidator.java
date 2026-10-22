package com.petcare.module.customer.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Lỗi gắn vào tham số {@code phone} (tham số đầu tiên) để FE hiện dưới ô tra cứu. */
public class CustomerSearchFilterValidator implements ConstraintValidator<ValidCustomerSearchFilter, CustomerSearchFilter> {

    @Override
    public boolean isValid(CustomerSearchFilter filter, ConstraintValidatorContext context) {
        if (filter == null || filter.hasAnyCondition()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("phone").addConstraintViolation();
        return false;
    }
}
