package com.petcare.module.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Vai trò của appointment-v1 #7–9 (docs/adr/0055): #7 chỉ khách; #8, #9 chỉ lễ tân và BRANCH_MANAGER (BR-LH-09). Bản sao
 * độc lập của bảng vai trò — đổi annotation mà không đổi ở đây thì đỏ. HTTP thật: {@code BookingRestrictionIT.roleMatrix}.
 */
class BookingRestrictionControllerSecurityTest {

    private static final Map<String, String> EXPECTED = Map.of(
            "getMyBookingRestriction", "hasRole('CUSTOMER')",
            "getCustomerBookingRestriction", "hasAnyRole('RECEPTIONIST','BRANCH_MANAGER')",
            "liftBookingRestriction", "hasAnyRole('RECEPTIONIST','BRANCH_MANAGER')");

    @Test
    void everyHandlerCarriesExactlyTheContractRoles() {
        Map<String, String> actual = Arrays.stream(BookingRestrictionController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, BookingRestrictionControllerSecurityTest::preAuthorize));

        assertThat(actual).isEqualTo(EXPECTED);
        assertThat(BookingRestrictionController.CUSTOMER_ONLY).isEqualTo(EXPECTED.get("getMyBookingRestriction"));
        assertThat(BookingRestrictionController.RECEPTIONIST_OR_BRANCH_MANAGER)
                .isEqualTo(EXPECTED.get("liftBookingRestriction"));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
