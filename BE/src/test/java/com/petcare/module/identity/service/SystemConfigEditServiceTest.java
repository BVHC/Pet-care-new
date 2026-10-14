package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.module.identity.entity.SystemConfig;
import com.petcare.module.identity.mapper.SystemConfigAdminMapper;
import com.petcare.module.identity.repository.SystemConfigRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC10 — sửa tham số [CFG] (identity-v1 #32; BR-QT-13, 15; docs/adr/0036). Thứ tự: 404 trước khi chạm DB → khóa (key
 * tăng dần, cả khóa chung cặp) → kiểu / min–max / chéo → trùng giá trị → ghi → flush → audit. Mọi nhánh lỗi không ghi,
 * không audit.
 */
class SystemConfigEditServiceTest {

    private static final long ADMIN = 7L;

    private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final SystemConfigAdminMapper mapper = Mappers.getMapper(SystemConfigAdminMapper.class);
    private final SystemConfigEditService service = new SystemConfigEditService(configs, mapper, audit, branchScope);
    private final Map<String, SystemConfig> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ADMIN, "admin@petcare.test", 42L, Role.ADMIN,
                null, false));
        row("otp.ttl_minutes", "5", ConfigValueType.INT, "1", "15", "phút");
        row("visit.late_priority_minutes", "15", ConfigValueType.INT, "0", "30", "phút");
        row("appointment.no_show_after_minutes", "30", ConfigValueType.INT, "15", "120", "phút");
        row("boarding.min_nights", "1", ConfigValueType.INT, "1", "7", "đêm");
        row("boarding.max_nights", "30", ConfigValueType.INT, "1", "90", "đêm");
        row("boarding.checkout_time", "12:00", ConfigValueType.TIME, "06:00", "22:00", "giờ");
        when(configs.findByKeyForUpdate(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
    }

    @Test
    void updatesLocksWritesFlushesThenAudits() {
        SystemConfigResponse response = service.updateConfig("otp.ttl_minutes", "10");

        assertThat(response).isEqualTo(new SystemConfigResponse("otp.ttl_minutes", "10", ConfigValueType.INT, "1",
                "15", "phút", "mô tả otp.ttl_minutes"));
        SystemConfig target = rows.get("otp.ttl_minutes");
        assertThat(target.getValue()).isEqualTo("10");
        assertThat(target.getUpdatedBy()).isEqualTo(ADMIN);
        InOrder order = inOrder(configs, audit);
        order.verify(configs).findByKeyForUpdate("otp.ttl_minutes");
        order.verify(configs).flush();
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        order.verifyNoMoreInteractions();
        assertThat(entry.getValue().action()).isEqualTo("SYSTEM_CONFIG_UPDATED");
        assertThat(entry.getValue().entityType()).isEqualTo("system_configs");
        assertThat(entry.getValue().entityId()).isNull();
        assertThat(entry.getValue().before()).isEqualTo(snapshot("otp.ttl_minutes", "5"));
        assertThat(entry.getValue().after()).isEqualTo(snapshot("otp.ttl_minutes", "10"));
    }

    @Test
    void valueIsStoredCanonical() {
        service.updateConfig("otp.ttl_minutes", " 012 ");

        assertThat(rows.get("otp.ttl_minutes").getValue()).isEqualTo("12");
    }

    @Test
    void unknownKeyIs404BeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.updateConfig("otp.unknown", "5"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tham số #otp.unknown");

        verify(configs, never()).findByKeyForUpdate(anyString());
        verifyNothingWritten();
    }

    @Test
    void keyKnownToCodeButMissingInDatabaseIs404() {
        rows.remove("otp.ttl_minutes");

        assertThatThrownBy(() -> service.updateConfig("otp.ttl_minutes", "5"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNothingWritten();
    }

    @Test
    void outOfRangeShowsAllowedRange() {
        assertThatThrownBy(() -> service.updateConfig("otp.ttl_minutes", "16"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút) (BR-QT-13)");
        assertThatThrownBy(() -> service.updateConfig("otp.ttl_minutes", "0"))
                .hasMessage("Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút) (BR-QT-13)");
        assertThat(rows.get("otp.ttl_minutes").getValue()).isEqualTo("5");
        verifyNothingWritten();
    }

    @Test
    void unparsableValueIsSameRuleWithSameMessage() {
        assertThatThrownBy(() -> service.updateConfig("otp.ttl_minutes", "năm"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút) (BR-QT-13)");
        assertThatThrownBy(() -> service.updateConfig("boarding.checkout_time", "12:00:30"))
                .hasMessage("Giá trị của boarding.checkout_time phải là giờ dạng HH:mm từ 06:00 đến 22:00 (BR-QT-13)");
        verifyNothingWritten();
    }

    @Test
    void crossKeyShowsNarrowedRangeAndReason() {
        assertThatThrownBy(() -> service.updateConfig("visit.late_priority_minutes", "30"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Giá trị của visit.late_priority_minutes phải là số nguyên từ 0 đến 29 (phút): phải nhỏ "
                        + "hơn appointment.no_show_after_minutes đang là 30 (BR-QT-13)");
        assertThatThrownBy(() -> service.updateConfig("appointment.no_show_after_minutes", "15"))
                .hasMessage("Giá trị của appointment.no_show_after_minutes phải là số nguyên từ 16 đến 120 (phút): "
                        + "phải lớn hơn visit.late_priority_minutes đang là 15 (BR-QT-13)");
        rows.get("boarding.max_nights").changeValue("5", null);
        assertThatThrownBy(() -> service.updateConfig("boarding.min_nights", "6"))
                .hasMessage("Giá trị của boarding.min_nights phải là số nguyên từ 1 đến 5 (đêm): không được lớn hơn "
                        + "boarding.max_nights đang là 5 (BR-QT-13)");
        verifyNothingWritten();
    }

    @Test
    void crossKeyBoundariesPass() {
        service.updateConfig("visit.late_priority_minutes", "29");
        service.updateConfig("boarding.min_nights", "7");
        rows.get("boarding.max_nights").changeValue("7", null);
        service.updateConfig("boarding.max_nights", "7");

        assertThat(rows.get("visit.late_priority_minutes").getValue()).isEqualTo("29");
        assertThat(rows.get("boarding.min_nights").getValue()).isEqualTo("7");
    }

    /** docs/adr/0036 mục 4: khóa của mình và khóa chung cặp, theo key tăng dần ("appointment…" < "visit…"). */
    @Test
    void locksPairInAscendingKeyOrder() {
        service.updateConfig("visit.late_priority_minutes", "10");

        InOrder order = inOrder(configs);
        order.verify(configs).findByKeyForUpdate("appointment.no_show_after_minutes");
        order.verify(configs).findByKeyForUpdate("visit.late_priority_minutes");
        order.verify(configs).flush();
    }

    @Test
    void sameValueAfterCanonicalisationWritesNothing() {
        SystemConfigResponse response = service.updateConfig("otp.ttl_minutes", "05");

        assertThat(response.value()).isEqualTo("5");
        assertThat(rows.get("otp.ttl_minutes").getUpdatedBy()).isNull();
        verifyNothingWritten();
    }

    @Test
    void listIsSortedByKeyAndMapped() {
        SystemConfig only = rows.get("otp.ttl_minutes");
        when(configs.findAll(any(Sort.class))).thenReturn(List.of(only));

        assertThat(service.listConfigs()).containsExactly(mapper.toResponse(only));
        verify(configs).findAll(Sort.by("configKey"));
    }

    /** Convention 07 §7.1: một transaction, mọi lỗi rollback (không noRollbackFor); list chỉ đọc. */
    @Test
    void transactionBoundaries() throws NoSuchMethodException {
        Method update = SystemConfigEditService.class.getMethod("updateConfig", String.class, String.class);
        Transactional updateTx = update.getAnnotation(Transactional.class);
        assertThat(updateTx).isNotNull();
        assertThat(updateTx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(updateTx.readOnly()).isFalse();
        assertThat(updateTx.noRollbackFor()).isEmpty();
        assertThat(updateTx.noRollbackForClassName()).isEmpty();

        Transactional listTx = SystemConfigEditService.class.getMethod("listConfigs").getAnnotation(Transactional.class);
        assertThat(listTx).isNotNull();
        assertThat(listTx.readOnly()).isTrue();
    }

    private void verifyNothingWritten() {
        verify(configs, never()).flush();
        verify(configs, never()).save(any());
        verify(audit, never()).record(any());
        verify(audit, never()).recordIndependently(any());
    }

    private void row(String key, String value, ConfigValueType type, String min, String max, String unit) {
        SystemConfig config = BeanUtils.instantiateClass(SystemConfig.class);
        ReflectionTestUtils.setField(config, "configKey", key);
        ReflectionTestUtils.setField(config, "value", value);
        ReflectionTestUtils.setField(config, "valueType", type);
        ReflectionTestUtils.setField(config, "minValue", min);
        ReflectionTestUtils.setField(config, "maxValue", max);
        ReflectionTestUtils.setField(config, "unit", unit);
        ReflectionTestUtils.setField(config, "description", "mô tả " + key);
        rows.put(key, config);
    }

    private static Map<String, Object> snapshot(String key, String value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", key);
        snapshot.put("value", value);
        return snapshot;
    }
}
