package com.petcare.module.fees.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.fees.api.ShippingFeeQuote;
import com.petcare.module.fees.dto.SaveShippingFeeConfigRequest;
import com.petcare.module.fees.dto.ShippingFeeConfigResponse;
import com.petcare.module.fees.entity.ShippingFeeConfig;
import com.petcare.module.fees.mapper.ShippingFeeConfigMapperImpl;
import com.petcare.module.fees.repository.ShippingFeeConfigRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** ST09 và cấu hình phí vận chuyển (BR-SP-08, 11; docs/adr/0140). */
class ShippingFeeServiceTest {

    private static final long ACTOR = 7L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-15T03:00:00.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ShippingFeeConfigRepository configs = mock(ShippingFeeConfigRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private ShippingFeeService service;

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACTOR);
        service = new ShippingFeeService(configs, new ShippingFeeConfigMapperImpl(), audit, scope, CLOCK);
    }

    private ShippingFeeConfig config(long baseFee, long freeThreshold) {
        ShippingFeeConfig config = org.springframework.beans.BeanUtils.instantiateClass(
                ShippingFeeConfig.class);
        ReflectionTestUtils.setField(config, "id", 1L);
        ReflectionTestUtils.setField(config, "baseFee", baseFee);
        ReflectionTestUtils.setField(config, "freeThreshold", freeThreshold);
        return config;
    }

    private void stored(long baseFee, long freeThreshold) {
        ShippingFeeConfig config = config(baseFee, freeThreshold);
        when(configs.findById(1L)).thenReturn(Optional.of(config));
        when(configs.findForUpdate()).thenReturn(Optional.of(config));
    }

    // ================================================================ ST09

    @Test
    void belowTheThresholdPaysTheBaseFee() {
        stored(30_000, 500_000);

        assertThat(service.quote(499_999)).isEqualTo(new ShippingFeeQuote(30_000, 30_000, 500_000));
    }

    @Test
    void exactlyTheThresholdIsFree() {
        stored(30_000, 500_000);

        assertThat(service.quote(500_000)).isEqualTo(new ShippingFeeQuote(0, 30_000, 500_000));
    }

    @Test
    void aboveTheThresholdIsFree() {
        stored(30_000, 500_000);

        assertThat(service.quote(2_000_000).fee()).isZero();
    }

    @Test
    void aThresholdOfZeroNeverMakesItFree() {
        stored(30_000, 0);

        assertThat(service.quote(0).fee()).isEqualTo(30_000);
        assertThat(service.quote(Long.MAX_VALUE).fee()).isEqualTo(30_000);
    }

    @Test
    void aBaseFeeOfZeroIsAlwaysFree() {
        stored(0, 500_000);

        assertThat(service.quote(0).fee()).isZero();
        assertThat(service.quote(499_999).fee()).isZero();
    }

    @Test
    void anEmptyCartPaysTheBaseFeeWhenThereIsAThreshold() {
        stored(30_000, 500_000);

        assertThat(service.quote(0).fee()).isEqualTo(30_000);
    }

    @Test
    void aNegativeSubtotalIsACallerBug() {
        assertThatThrownBy(() -> service.quote(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(configs);
    }

    @Test
    void aMissingSeedRowIsAServerError() {
        when(configs.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.get()).isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ save

    @Test
    void saveLocksThenWritesThenAuditsAndRecordsTheActorAndTheClockTime() {
        stored(10_000, 100_000);

        ShippingFeeConfigResponse response = service.save(new SaveShippingFeeConfigRequest(30_000L, 500_000L));

        assertThat(response.baseFee()).isEqualTo(30_000);
        assertThat(response.freeThreshold()).isEqualTo(500_000);
        assertThat(response.updatedAt()).isEqualTo(NOW_MICROS);
        InOrder order = inOrder(configs, audit);
        order.verify(configs).findForUpdate();
        order.verify(configs).replace(30_000L, 500_000L, ACTOR, NOW_MICROS);
        order.verify(audit).record(any(AuditEntry.class));
    }

    @Test
    void saveAuditsBeforeAndAfterValues() {
        stored(10_000, 100_000);

        service.save(new SaveShippingFeeConfigRequest(30_000L, 0L));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("SHIPPING_FEE_CONFIG_UPDATED");
        assertThat(entry.getValue().entityType()).isEqualTo("shipping_fee_configs");
        assertThat(entry.getValue().entityId()).isEqualTo(1L);
        assertThat(entry.getValue().before()).isEqualTo(Map.of("baseFee", 10_000L, "freeThreshold", 100_000L));
        assertThat(entry.getValue().after()).isEqualTo(Map.of("baseFee", 30_000L, "freeThreshold", 0L));
    }

    @Test
    void anIdenticalPutStillWritesAndAudits() {
        stored(10_000, 100_000);

        service.save(new SaveShippingFeeConfigRequest(10_000L, 100_000L));

        verify(configs).replace(10_000L, 100_000L, ACTOR, NOW_MICROS);
        verify(audit).record(any(AuditEntry.class));
    }

    @Test
    void aNegativeBaseFeeIsBrSp08AndWritesNothing() {
        assertThatThrownBy(() -> service.save(new SaveShippingFeeConfigRequest(-1L, 0L)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-08"));

        verifyNoInteractions(configs, audit);
    }

    @Test
    void aNegativeThresholdIsBrSp08AndWritesNothing() {
        assertThatThrownBy(() -> service.save(new SaveShippingFeeConfigRequest(0L, -5L)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-08"));

        verifyNoInteractions(configs, audit);
    }
}
