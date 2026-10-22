package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Facade UC10 (docs/adr/0036 mục 2): không transaction; reload cache chỉ <b>sau</b> khi bean có transaction trả về
 * (đã commit) — kể cả nhánh trùng giá trị —, không reload khi bean đó ném lỗi (đã rollback); reload lỗi thì ném lại.
 */
class SystemConfigAdminServiceTest {

    private static final SystemConfigResponse RESPONSE = new SystemConfigResponse("otp.ttl_minutes", "10",
            ConfigValueType.INT, "1", "15", "phút", "mô tả");

    private final SystemConfigEditService edits = mock(SystemConfigEditService.class);
    private final SystemConfigService configs = mock(SystemConfigService.class);
    private final SystemConfigAdminService facade = new SystemConfigAdminService(edits, configs);

    @Test
    void facadeIsNotTransactional() {
        assertThat(SystemConfigAdminService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(SystemConfigAdminService.class.getDeclaredMethods())
                .filter((Method method) -> method.isAnnotationPresent(Transactional.class))).isEmpty();
    }

    @Test
    void reloadsOnlyAfterTheTransactionalCallReturned() {
        when(edits.updateConfig("otp.ttl_minutes", "10")).thenReturn(RESPONSE);

        assertThat(facade.updateConfig("otp.ttl_minutes", "10")).isSameAs(RESPONSE);

        InOrder order = inOrder(edits, configs);
        order.verify(edits).updateConfig("otp.ttl_minutes", "10");
        order.verify(configs).reload();
        order.verifyNoMoreInteractions();
    }

    @Test
    void failedTransactionNeverReloads() {
        when(edits.updateConfig("otp.ttl_minutes", "99"))
                .thenThrow(new BusinessRuleViolationException("BR-QT-13", "ngoài khoảng"));

        assertThatThrownBy(() -> facade.updateConfig("otp.ttl_minutes", "99"))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(configs, never()).reload();
    }

    @Test
    void reloadFailureAfterCommitIsRethrown() {
        when(edits.updateConfig("otp.ttl_minutes", "10")).thenReturn(RESPONSE);
        doThrow(new IllegalStateException("db down")).when(configs).reload();

        assertThatThrownBy(() -> facade.updateConfig("otp.ttl_minutes", "10"))
                .isInstanceOf(IllegalStateException.class).hasMessage("db down");
    }

    @Test
    void listDelegatesWithoutReload() {
        when(edits.listConfigs()).thenReturn(List.of(RESPONSE));

        assertThat(facade.listConfigs()).containsExactly(RESPONSE);
        verify(configs, never()).reload();
    }
}
