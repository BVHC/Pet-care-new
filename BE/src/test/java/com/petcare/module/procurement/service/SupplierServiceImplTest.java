package com.petcare.module.procurement.service;

import com.petcare.module.procurement.dto.CreateSupplierRequest;
import com.petcare.module.procurement.dto.UpdateSupplierRequest;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.module.procurement.mapper.SupplierMapper;
import com.petcare.module.procurement.mapper.SupplierMapperImpl;
import com.petcare.module.procurement.repository.SupplierRepository;
import com.petcare.platform.enums.SupplierStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-13-04 (ManageSupplier). */
@ExtendWith(MockitoExtension.class)
class SupplierServiceImplTest {

    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private ProcurementEventRecorder procurementEventRecorder;

    private final SupplierMapper supplierMapper = new SupplierMapperImpl();

    private SupplierServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId).build();
    }

    private static CreateSupplierRequest request(String code) {
        return new CreateSupplierRequest(code, "Cong ty ABC", "0900000000", "abc@example.com", "123 Test St");
    }

    private static Supplier supplier(UUID id, UUID organizationId, String code) {
        Supplier supplier = new Supplier(organizationId, code, "Cong ty ABC", "0900000000", "abc@example.com", "123 Test St");
        supplier.setId(id);
        return supplier;
    }

    @BeforeEach
    void setUp() {
        service = new SupplierServiceImpl(supplierRepository, supplierMapper, procurementEventRecorder);
    }

    @Test
    void createSupplier_uniqueCode_savesActive() {
        UUID organizationId = UUID.randomUUID();
        when(supplierRepository.existsByOrganizationIdAndCode(organizationId, "SUP01")).thenReturn(false);
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier s = invocation.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        var response = service.createSupplier(request("SUP01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.code()).isEqualTo("SUP01");
        assertThat(response.status()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(response.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void createSupplier_duplicateCode_throwsBusinessRuleViolation_RULE_13_04() {
        UUID organizationId = UUID.randomUUID();
        when(supplierRepository.existsByOrganizationIdAndCode(organizationId, "SUP01")).thenReturn(true);

        assertThatThrownBy(() -> service.createSupplier(request("SUP01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-04"));
    }

    @Test
    void createSupplier_raceCondition_dataIntegrityViolation_throwsBusinessRuleViolation() {
        UUID organizationId = UUID.randomUUID();
        when(supplierRepository.existsByOrganizationIdAndCode(organizationId, "SUP01")).thenReturn(false);
        when(supplierRepository.save(any(Supplier.class))).thenThrow(new DataIntegrityViolationException("uq_suppliers_org_code"));

        assertThatThrownBy(() -> service.createSupplier(request("SUP01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-04"));
    }

    @Test
    void createSupplier_wrongOrgActor_throwsAccessDeniedScope() {
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID());

        assertThatThrownBy(() -> service.createSupplier(request("SUP01"), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateSupplier_partialFields_keepsUnsetFields() {
        UUID organizationId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Supplier existing = supplier(supplierId, organizationId, "SUP01");
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(existing));
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateSupplier(supplierId, new UpdateSupplierRequest("Ten moi", null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.name()).isEqualTo("Ten moi");
        assertThat(response.contactPhone()).isEqualTo("0900000000");
    }

    @Test
    void updateSupplier_toggleStatusInactive_persists() {
        UUID organizationId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Supplier existing = supplier(supplierId, organizationId, "SUP01");
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(existing));
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateSupplier(supplierId,
                new UpdateSupplierRequest(null, null, null, null, SupplierStatus.INACTIVE),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(SupplierStatus.INACTIVE);
    }

    @Test
    void updateSupplier_notFound_throwsResourceNotFound() {
        UUID supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSupplier(supplierId, new UpdateSupplierRequest(null, null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateSupplier_staleVersion_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier(supplierId, organizationId, "SUP01")));
        when(supplierRepository.saveAndFlush(any(Supplier.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Supplier.class, supplierId));

        assertThatThrownBy(() -> service.updateSupplier(supplierId, new UpdateSupplierRequest("X", null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }
}
