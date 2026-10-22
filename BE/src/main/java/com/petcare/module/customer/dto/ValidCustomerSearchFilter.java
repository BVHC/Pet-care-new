package com.petcare.module.customer.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** {@link CustomerSearchFilter} phải có ít nhất một điều kiện tìm (customer-v1 #8: 400 "Không có điều kiện tìm nào"). */
@Documented
@Constraint(validatedBy = CustomerSearchFilterValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCustomerSearchFilter {

    String message() default "Cần ít nhất một điều kiện tìm: phone, name, email hoặc petName";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
