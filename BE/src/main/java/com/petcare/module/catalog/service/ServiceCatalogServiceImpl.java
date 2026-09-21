package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.RequiredResourceItem;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.ServiceRequiredResource;
import com.petcare.module.catalog.mapper.ServiceMapper;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.ServiceRequiredResourceRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Module 05 — phần Service (RULE-05-03: ManageService, required resources đi kèm). Tên entity
 * {@code Service} trùng {@code org.springframework.stereotype.Service} — annotation phải
 * fully-qualify ({@code @org.springframework.stereotype.Service}), không import thường, để
 * tránh xung đột với {@code import com.petcare.module.catalog.entity.Service}.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceCatalogServiceImpl implements ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final ServiceRequiredResourceRepository requiredResourceRepository;
    private final ServiceMapper serviceMapper;

    @Override
    @Transactional
    @Auditable(action = "ManageService", resourceType = "Service")
    public ServiceResponse createService(CreateServiceRequest request, UserPrincipal actor) {
        UUID organizationId = actor.getOrganizationId();

        if (serviceRepository.existsByOrganizationIdAndCode(organizationId, request.code())) {
            throw new BusinessRuleViolationException("RULE-05-03", "Mã dịch vụ đã tồn tại trong Organization");
        }

        Service service = new Service(organizationId, request.code(), request.name(), request.category(),
                new BigDecimal(request.basePrice()), request.durationMinutes(), request.isActive());
        try {
            service = serviceRepository.save(service);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleViolationException("RULE-05-03", "Mã dịch vụ đã tồn tại trong Organization");
        }

        List<ServiceRequiredResource> resources = saveRequiredResources(service.getId(), request.requiredResources());
        return serviceMapper.toResponse(service, resources);
    }

    @Override
    @Transactional
    @Auditable(action = "ManageService", resourceType = "Service")
    public ServiceResponse updateService(@AuditResourceId UUID serviceId, UpdateServiceRequest request, UserPrincipal actor) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
        RoleScopeGuard.assertCanManageOrganization(actor, service.getOrganizationId());

        if (request.name() != null) {
            service.setName(request.name());
        }
        if (request.category() != null) {
            service.setCategory(request.category());
        }
        if (request.basePrice() != null) {
            service.setBasePrice(new BigDecimal(request.basePrice()));
        }
        if (request.durationMinutes() != null) {
            service.setDurationMinutes(request.durationMinutes());
        }
        if (request.isActive() != null) {
            service.setActive(request.isActive());
        }

        try {
            service = serviceRepository.save(service);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Service", serviceId);
        }

        List<ServiceRequiredResource> resources;
        if (request.requiredResources() != null) {
            // Replace-as-whole (contract ASSUMPTION A1) — kể cả list rỗng nghĩa là xóa hết.
            requiredResourceRepository.deleteAllByServiceId(serviceId);
            resources = saveRequiredResources(serviceId, request.requiredResources());
        } else {
            resources = requiredResourceRepository.findAllByServiceId(serviceId);
        }

        return serviceMapper.toResponse(service, resources);
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceResponse getService(UUID serviceId, UserPrincipal actor) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
        RoleScopeGuard.assertCanViewOrganizationCatalog(actor, service.getOrganizationId());
        List<ServiceRequiredResource> resources = requiredResourceRepository.findAllByServiceId(serviceId);
        return serviceMapper.toResponse(service, resources);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ServiceResponse> listServices(UserPrincipal actor, Pageable pageable) {
        UUID organizationId = actor.getOrganizationId();
        RoleScopeGuard.assertCanViewOrganizationCatalog(actor, organizationId);

        Page<ServiceResponse> page = serviceRepository.findAllByOrganizationId(organizationId, pageable)
                .map(service -> serviceMapper.toResponse(service, requiredResourceRepository.findAllByServiceId(service.getId())));
        return PageResponse.of(page);
    }

    private List<ServiceRequiredResource> saveRequiredResources(UUID serviceId, List<RequiredResourceItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ServiceRequiredResource> resources = items.stream()
                .map(item -> new ServiceRequiredResource(serviceId, item.resourceType(),
                        item.quantityRequired() == null ? 1 : item.quantityRequired()))
                .toList();
        return requiredResourceRepository.saveAll(resources);
    }
}
