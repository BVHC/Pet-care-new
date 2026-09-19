package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateOrganizationRequest;
import com.petcare.module.organization.dto.OrganizationResponse;
import com.petcare.module.organization.dto.UpdateOrganizationRequest;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.mapper.OrganizationMapper;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — phần Organization.
 * docs/02-business-rules.md RULE-03-01 (sở hữu đơn tổ chức, cách ly tenant).
 * Store/FSM-2/operating-hours/store-resources ngoài phạm vi lần triển khai này.
 */
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMapper organizationMapper;

    @Override
    @Transactional
    @Auditable(action = "CreateOrganization", resourceType = "Organization")
    public OrganizationResponse createOrganization(CreateOrganizationRequest request) {
        if (organizationRepository.existsByCode(request.code())) {
            throw new BusinessRuleViolationException("RULE-03-01", "Mã tổ chức đã tồn tại");
        }

        Organization organization = new Organization(request.code(), request.name(), request.taxCode(), request.address());
        try {
            organization = organizationRepository.save(organization);
        } catch (DataIntegrityViolationException ex) {
            // RULE-03-01: race condition giữa pre-check existsByCode và save() —
            // UNIQUE constraint uq_organizations_code ở DB là guard thật.
            throw new BusinessRuleViolationException("RULE-03-01", "Mã tổ chức đã tồn tại");
        }

        return organizationMapper.toResponse(organization);
    }

    @Override
    @Transactional
    @Auditable(action = "UpdateOrganization", resourceType = "Organization")
    public OrganizationResponse updateOrganization(@AuditResourceId UUID organizationId, UpdateOrganizationRequest request, UserPrincipal actor) {
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));

        if (request.name() != null) {
            // RULE-03-01: tên tổ chức là dữ liệu định danh bắt buộc (CreateOrganizationRequest
            // ràng buộc @NotBlank) — Update không được phép hạ nó xuống chuỗi rỗng/toàn khoảng
            // trắng chỉ vì record cho phép null nghĩa là "giữ nguyên".
            if (request.name().isBlank()) {
                throw new BusinessRuleViolationException("RULE-03-01", "Tên tổ chức không được để trống");
            }
            organization.setName(request.name());
        }
        if (request.taxCode() != null) {
            organization.setTaxCode(request.taxCode());
        }
        if (request.address() != null) {
            organization.setAddress(request.address());
        }

        // save() tường minh (không dựa vào dirty-checking tự flush lúc commit) — cùng lý do
        // AuthServiceImpl.saveWithConcurrencyCheck: Organization có @Version (optimistic lock,
        // xem V5__organization_audit_columns.sql), 2 request PATCH đồng thời trên cùng Organization
        // có thể đụng version conflict thật; nếu để Hibernate tự flush ngầm lúc @Transactional
        // commit thì ObjectOptimisticLockingFailureException không có chỗ nào bắt được (không
        // handler nào trong GlobalExceptionHandler), rơi thẳng xuống handleGeneric -> 500 thay vì
        // 409 CONCURRENCY_CONFLICT sạch như các service khác.
        try {
            organization = organizationRepository.save(organization);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Organization", organizationId);
        }

        return organizationMapper.toResponse(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(UUID organizationId, UserPrincipal actor) {
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));

        return organizationMapper.toResponse(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> listOrganizations(UserPrincipal actor, Pageable pageable) {
        if (actor.getRole() == UserRole.SUPER_ADMIN) {
            Page<OrganizationResponse> page = organizationRepository.findAll(pageable)
                    .map(organizationMapper::toResponse);
            return PageResponse.of(page);
        }

        // ORGANIZATION_ADMIN — chỉ thấy Org của chính mình (RULE-02-01 cách ly tenant),
        // không dùng findAll để tránh lộ Org khác.
        List<OrganizationResponse> own = organizationRepository.findById(actor.getOrganizationId())
                .map(organizationMapper::toResponse)
                .map(List::of)
                .orElse(List.of());
        // PageImpl không tự cắt content theo pageable — phải subList thủ công theo offset/size,
        // nếu không mọi page (0, 1, 2...) đều trả về cùng 1 phần tử thay vì rỗng khi vượt quá.
        int fromIndex = Math.min((int) pageable.getOffset(), own.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), own.size());
        Page<OrganizationResponse> page = new PageImpl<>(own.subList(fromIndex, toIndex), pageable, own.size());
        return PageResponse.of(page);
    }
}
