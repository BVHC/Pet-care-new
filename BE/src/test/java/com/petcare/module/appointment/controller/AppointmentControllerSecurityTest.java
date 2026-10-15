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
 * Vai trò của từng handler trong {@link AppointmentController} khớp appointment-v1 mục B: #2 đặt lịch A02 + A06
 * (docs/adr/0043); #3 danh sách A02, A05, A06; #4 chi tiết A02, A05–A08 (docs/adr/0044); #5 đổi khung, #6 hủy A02 + A06
 * (docs/adr/0045). Handler mới mà quên
 * {@code @PreAuthorize} hoặc chưa có trong bảng này thì test đỏ.
 */
class AppointmentControllerSecurityTest {

    private static final Map<String, String> EXPECTED = Map.of(
            "bookAppointment", "hasAnyRole('CUSTOMER','RECEPTIONIST')",
            "listAppointments", "hasAnyRole('CUSTOMER','BRANCH_MANAGER','RECEPTIONIST')",
            "getAppointment", "hasAnyRole('CUSTOMER','BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')",
            "rescheduleAppointment", "hasAnyRole('CUSTOMER','RECEPTIONIST')",
            "cancelAppointment", "hasAnyRole('CUSTOMER','RECEPTIONIST')");

    @Test
    void everyHandlerCarriesExactlyTheContractRoles() {
        Map<String, String> actual = Arrays.stream(AppointmentController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, AppointmentControllerSecurityTest::preAuthorize));

        assertThat(actual).isEqualTo(EXPECTED);
        assertThat(AppointmentController.CUSTOMER_OR_RECEPTIONIST).isEqualTo(EXPECTED.get("bookAppointment"));
        assertThat(AppointmentController.LIST_ROLES).isEqualTo(EXPECTED.get("listAppointments"));
        assertThat(AppointmentController.VIEW_ROLES).isEqualTo(EXPECTED.get("getAppointment"));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
