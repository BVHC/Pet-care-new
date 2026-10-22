package com.petcare.module.care.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * UC88 cho mọi role đã đăng nhập (care-v1 #5–8; docs/adr/0039): mọi handler mang
 * {@code @PreAuthorize("isAuthenticated()")}, không handler nào nhận body hay tham số chọn tài khoản — chủ luôn là token.
 */
class MyNotificationControllerSecurityTest {

    @Test
    void everyHandlerRequiresSignInAndTakesNoAccountFromTheRequest() {
        List<Method> handlers = Arrays.stream(MyNotificationController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).hasSize(4);
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName()).isEqualTo("isAuthenticated()")
                    .isEqualTo(MyNotificationController.ANY_SIGNED_IN);
            assertThat(Arrays.stream(handler.getParameters()))
                    .as(handler.getName() + " không nhận body / accountId")
                    .noneMatch(p -> p.isAnnotationPresent(RequestBody.class)
                            || p.getName().toLowerCase().contains("account"));
        }
    }
}
