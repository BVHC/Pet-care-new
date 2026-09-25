package com.petcare.module.procurement.service;

import com.petcare.module.procurement.dto.CreateSupplierRequest;
import com.petcare.module.procurement.dto.SupplierResponse;
import com.petcare.module.procurement.dto.UpdateSupplierRequest;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.module.procurement.mapper.SupplierMapper;
import com.petcare.module.procurement.repository.SupplierRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Module 13 — ManageSupplier (RULE-13-04). CRUD thuần, không Maker-Checker — bám sát
 * {@code ProductServiceImpl} (Module 05) 1:1: pre-check UNIQUE + fallback race
 * {@code DataIntegrityViolationException}, partial update, optimistic-lock catch.
 */
@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final ProcurementEventRecorder procurementEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "ManageSupplier", resourceType = "Supplier")
    public SupplierResponse createSupplier(CreateSupplierRequest request, UserPrincipal actor) {
        UUID organizationId = actor.getOrganizationId();
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        if (supplierRepository.existsByOrganizationIdAndCode(organizationId, request.code())) {
            throw new BusinessRuleViolationException("RULE-13-04", "Mã NCC đã tồn tại trong Organization");
        }

        Supplier supplier = new Supplier(organizationId, request.code(), request.name(),
                request.contactPhone(), request.contactEmail(), request.address());
        try {
            supplier = supplierRepository.save(supplier);
        } catch (DataIntegrityViolationException ex) {
            // Race condition giữa pre-check existsByOrganizationIdAndCode() và save() — UNIQUE
            // uq_suppliers_org_code ở DB là guard thật, cùng pattern ProductServiceImpl.createProduct.
            throw new BusinessRuleViolationException("RULE-13-04", "Mã NCC đã tồn tại trong Organization");
        }

        procurementEventRecorder.recordSupplierCreated(supplier);
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional
    @Auditable(action = "ManageSupplier", resourceType = "Supplier")
    public SupplierResponse updateSupplier(@AuditResourceId UUID supplierId, UpdateSupplierRequest request, UserPrincipal actor) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        RoleScopeGuard.assertCanManageOrganization(actor, supplier.getOrganizationId());

        if (request.name() != null) {
            supplier.setName(request.name());
        }
        if (request.contactPhone() != null) {
            supplier.setContactPhone(request.contactPhone());
        }
        if (request.contactEmail() != null) {
            supplier.setContactEmail(request.contactEmail());
        }
        if (request.address() != null) {
            supplier.setAddress(request.address());
        }
        if (request.status() != null) {
            supplier.setStatus(request.status());
        }

        try {
            supplier = supplierRepository.saveAndFlush(supplier);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Supplier", supplierId);
        }

        procurementEventRecorder.recordSupplierUpdated(supplier);
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(UUID supplierId, UserPrincipal actor) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        RoleScopeGuard.assertCanManageOrganization(actor, supplier.getOrganizationId());
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> listSuppliers(UserPrincipal actor, Pageable pageable) {
        UUID organizationId = actor.getOrganizationId();
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        Page<SupplierResponse> page = supplierRepository.findAllByOrganizationId(organizationId, pageable)
                .map(supplierMapper::toResponse);
        return PageResponse.of(page);
    }
}
