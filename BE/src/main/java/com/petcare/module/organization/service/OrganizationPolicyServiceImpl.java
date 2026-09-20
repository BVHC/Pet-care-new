package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.OrganizationPolicyResponse;
import com.petcare.module.organization.dto.UpdateOrganizationPolicyRequest;
import com.petcare.module.organization.entity.OrganizationPolicy;
import com.petcare.module.organization.mapper.OrganizationPolicyMapper;
import com.petcare.module.organization.repository.OrganizationPolicyRepository;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.platform.audit.AuditOrganizationId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — ManageOrganizationPolicy (RULE-03-09). Actor CHỈ
 * SUPER_ADMIN (toàn quyền) hoặc ORGANIZATION_ADMIN (đúng Org mình) — RoleScopeGuard#assertCanManageOrganization,
 * không có STORE_MANAGER (khác ConfigureOperatingHour/ConfigureStoreResource).
 * <p>
 * PATCH lazy-create bản ghi ở lần gọi đầu tiên (chưa từng cấu hình) — pattern mới, không có tiền
 * lệ nào khác trong codebase này. {@code version} client gửi luôn được đối chiếu tường minh với
 * version hiện tại (0 nếu chưa có row, khớp giá trị Hibernate @Version gán lúc insert đầu tiên):
 * lệch version — dù do đã có row mới hơn, hay do race-condition 2 lần lazy-create cùng lúc đụng
 * UNIQUE {@code uq_org_policies_org} — đều quy về cùng 1 lỗi {@link ConcurrencyConflictException}
 * (409), giữ contract nhất quán cho FE (luôn retry bằng cách re-GET).
 */
@Service
@RequiredArgsConstructor
public class OrganizationPolicyServiceImpl implements OrganizationPolicyService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final OrganizationPolicyMapper organizationPolicyMapper;

    @Override
    @Transactional(readOnly = true)
    public OrganizationPolicyResponse getPolicy(UUID organizationId, UserPrincipal actor) {
        assertOrganizationExists(organizationId);
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        return organizationPolicyRepository.findByOrganizationId(organizationId)
                .map(organizationPolicyMapper::toResponse)
                .orElseGet(() -> organizationPolicyMapper.toDefaultResponse(organizationId));
    }

    @Override
    @Transactional
    @Auditable(action = "ManageOrganizationPolicy", resourceType = "OrganizationPolicy")
    public OrganizationPolicyResponse updatePolicy(@AuditOrganizationId UUID organizationId,
                                                    UpdateOrganizationPolicyRequest request, UserPrincipal actor) {
        assertOrganizationExists(organizationId);
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        Optional<OrganizationPolicy> existing = organizationPolicyRepository.findByOrganizationId(organizationId);
        OrganizationPolicy policy;
        if (existing.isPresent()) {
            policy = existing.get();
            // Check tường minh version — entity vừa load mang version THẬT (mới nhất), không phải
            // version cũ client gửi, nên JPA sẽ không tự phát hiện được lệch version nếu chỉ dựa
            // vào @Version của entity đang giữ; phải tự đối chiếu ở đây trước khi áp field nào.
            if (!policy.getVersion().equals(request.version())) {
                throw new ConcurrencyConflictException("OrganizationPolicy", organizationId);
            }
            applyRequest(policy, request);
        } else {
            // Chưa từng cấu hình — "policy luôn tồn tại về mặt khái niệm" ở version 0 (khớp GET
            // default và giá trị Hibernate @Version gán lúc insert đầu tiên). version khác 0 nghĩa
            // là client đang cầm 1 basis không khớp thực tế server — từ chối trước khi insert.
            if (request.version() != 0L) {
                throw new ConcurrencyConflictException("OrganizationPolicy", organizationId);
            }
            policy = new OrganizationPolicy(organizationId);
            applyRequest(policy, request);
        }

        try {
            policy = organizationPolicyRepository.save(policy);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Update đồng thời trong cửa sổ hẹp giữa check version thủ công ở trên và lúc flush.
            throw new ConcurrencyConflictException("OrganizationPolicy", organizationId);
        } catch (DataIntegrityViolationException ex) {
            // Race-condition INSERT: 2 lần lazy-create đầu tiên gần như đồng thời cùng thấy "chưa
            // có row", cùng INSERT — đụng UNIQUE uq_org_policies_org. Về bản chất giống hệt case
            // stale-version ở trên (2 writer bất đồng về version hiện tại của đúng 1 conceptual
            // row), chỉ phát hiện muộn hơn ở tầng DB thay vì ở check thủ công — quy về cùng lỗi.
            throw new ConcurrencyConflictException("OrganizationPolicy", organizationId);
        }

        return organizationPolicyMapper.toResponse(policy);
    }

    private void assertOrganizationExists(UUID organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException("Organization", organizationId);
        }
    }

    private void applyRequest(OrganizationPolicy policy, UpdateOrganizationPolicyRequest request) {
        if (request.refundWindowDays() != null) {
            policy.setRefundWindowDays(request.refundWindowDays());
        }
        if (request.refundRequiresApproval() != null) {
            policy.setRefundRequiresApproval(request.refundRequiresApproval());
        }
        if (request.dataRetentionDays() != null) {
            policy.setDataRetentionDays(request.dataRetentionDays());
        }
        if (request.securityFrameworkLevel() != null) {
            policy.setSecurityFrameworkLevel(request.securityFrameworkLevel());
        }
    }
}
