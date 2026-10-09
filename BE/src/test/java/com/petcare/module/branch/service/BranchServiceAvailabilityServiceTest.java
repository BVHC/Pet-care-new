package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.dto.BranchServiceItem;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchServiceSetting;
import com.petcare.module.branch.entity.BranchServiceSettingId;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.BranchServiceSettingRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/** UC33: bật / tắt dịch vụ, kể cả loại chuồng, tại chi nhánh (BR-LH-01, BR-SP-04). */
@ExtendWith(MockitoExtension.class)
class BranchServiceAvailabilityServiceTest {

    @Mock BranchRepository branches;
    @Mock BranchServiceSettingRepository settings;
    @Mock CatalogQueryApi catalog;
    @Mock BranchScope scope;

    BranchServiceAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new BranchServiceAvailabilityService(branches, settings, catalog, scope);
    }

    private Branch branch() {
        Branch branch = new Branch("CN", "Địa chỉ", "0281234567", BigDecimal.ONE, BigDecimal.ONE, false);
        ReflectionTestUtils.setField(branch, "id", 3L);
        return branch;
    }

    private static ServiceInfo info(long id, String name, ServiceGroup group, boolean active) {
        return new ServiceInfo(id, name, group, group == ServiceGroup.MEDICAL ? MedicalType.EXAM : null, 100_000,
                active);
    }

    // -------------------------------------------------------------- xem

    @Test
    void listsEveryActiveServiceWithItsFlagAndMissingRowsMeanDisabled() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(catalog.listActiveServices()).thenReturn(List.of(
                info(1, "Khám tổng quát", ServiceGroup.MEDICAL, true),
                info(2, "Spa", ServiceGroup.GROOMING, true),
                info(3, "Chuồng chó nhỏ", ServiceGroup.BOARDING, true)));
        when(settings.findByBranchId(3L)).thenReturn(List.of(
                new BranchServiceSetting(3L, 1L, true), new BranchServiceSetting(3L, 2L, false),
                new BranchServiceSetting(3L, 99L, true)));      // dịch vụ đã ngừng, không có trong danh mục

        List<BranchServiceItem> items = service.listServices(3L);

        assertThat(items).extracting(BranchServiceItem::serviceId).containsExactly(1L, 2L, 3L);
        assertThat(items).extracting(BranchServiceItem::enabled).containsExactly(true, false, false);
        assertThat(items.get(2).group()).isEqualTo(ServiceGroup.BOARDING);
        verify(scope).check(3L);
    }

    @Test
    void listForAnotherBranchIsRefusedByTheScope() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        org.mockito.Mockito.doThrow(new AccessDeniedScopeException("branch:3", "branch:4")).when(scope).check(3L);

        assertThatThrownBy(() -> service.listServices(3L)).isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(catalog, settings);
    }

    @Test
    void listUnknownBranchIsNotFound() {
        when(branches.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listServices(3L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------------- bật / tắt

    @Test
    void enablesAServiceByCreatingTheRow() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        when(catalog.findService(7L)).thenReturn(Optional.of(info(7, "Spa", ServiceGroup.GROOMING, true)));
        when(settings.findById(new BranchServiceSettingId(3L, 7L))).thenReturn(Optional.empty());

        BranchServiceItem item = service.setService(3L, 7L, true);

        assertThat(item.enabled()).isTrue();
        assertThat(item.name()).isEqualTo("Spa");
        ArgumentCaptor<BranchServiceSetting> captor = ArgumentCaptor.forClass(BranchServiceSetting.class);
        verify(settings).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getId()).isEqualTo(new BranchServiceSettingId(3L, 7L));
    }

    @Test
    void disablesAServiceByUpdatingTheExistingRow() {
        BranchServiceSetting existing = new BranchServiceSetting(3L, 7L, true);
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        when(catalog.findService(7L)).thenReturn(Optional.of(info(7, "Chuồng", ServiceGroup.BOARDING, true)));
        when(settings.findById(new BranchServiceSettingId(3L, 7L))).thenReturn(Optional.of(existing));

        BranchServiceItem item = service.setService(3L, 7L, false);

        assertThat(item.enabled()).isFalse();
        assertThat(existing.isEnabled()).isFalse();
        verify(settings).saveAndFlush(existing);
    }

    @Test
    void cannotEnableAServiceThatIsNoLongerSold_BR_LH_01() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        when(catalog.findService(7L)).thenReturn(Optional.of(info(7, "Cũ", ServiceGroup.GROOMING, false)));

        assertThatThrownBy(() -> service.setService(3L, 7L, true)).isInstanceOfSatisfying(
                BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-LH-01"));
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    void canStillDisableAServiceThatIsNoLongerSold() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        when(catalog.findService(7L)).thenReturn(Optional.of(info(7, "Cũ", ServiceGroup.GROOMING, false)));
        when(settings.findById(new BranchServiceSettingId(3L, 7L)))
                .thenReturn(Optional.of(new BranchServiceSetting(3L, 7L, true)));

        assertThat(service.setService(3L, 7L, false).enabled()).isFalse();
    }

    @Test
    void unknownServiceIsNotFound() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
        when(catalog.findService(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setService(3L, 7L, true)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(settings);
    }

    @Test
    void setOnUnknownBranchIsNotFoundAndTakesTheLockFirst() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setService(3L, 7L, true)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(catalog, settings);
    }
}
