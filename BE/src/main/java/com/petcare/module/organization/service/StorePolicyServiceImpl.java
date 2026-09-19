package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.StorePolicyResponse;
import com.petcare.module.organization.dto.UpdateStorePolicyRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.entity.StorePolicy;
import com.petcare.module.organization.mapper.StorePolicyMapper;
import com.petcare.module.organization.repository.StorePolicyRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.audit.AuditStoreId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.SurchargeType;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — ConfigureStorePolicy (RULE-03-10). Actor GET:
 * SUPER_ADMIN/ORGANIZATION_ADMIN (Org mình)/STORE_MANAGER (Store mình) —
 * RoleScopeGuard#assertCanManageStore, mirror getStore/listResources. Actor PATCH: CHỈ
 * STORE_MANAGER đúng Store mình quản lý — RoleScopeGuard#assertIsOwnStoreManager, không ngoại lệ
 * admin, cùng ConfigureOperatingHour/ConfigureStoreResource (khác ManageOrganizationPolicy).
 * <p>
 * Lazy-create + optimistic lock tường minh — mirror y hệt OrganizationPolicyServiceImpl (xem
 * javadoc ở đó cho lý do chi tiết cách xử lý version/race-condition).
 */
@Service
@RequiredArgsConstructor
public class StorePolicyServiceImpl implements StorePolicyService {

    private final StoreRepository storeRepository;
    private final StorePolicyRepository storePolicyRepository;
    private final StorePolicyMapper storePolicyMapper;

    @Override
    @Transactional(readOnly = true)
    public StorePolicyResponse getPolicy(UUID storeId, UserPrincipal actor) {
        Store store = loadStore(storeId);
        RoleScopeGuard.assertCanManageStore(actor, store.getOrganizationId(), storeId);

        return storePolicyRepository.findByStoreId(storeId)
                .map(policy -> storePolicyMapper.toResponse(policy, store.getOrganizationId()))
                .orElseGet(() -> storePolicyMapper.toDefaultResponse(storeId, store.getOrganizationId()));
    }

    @Override
    @Transactional
    @Auditable(action = "ConfigureStorePolicy", resourceType = "StorePolicy")
    public StorePolicyResponse updatePolicy(@AuditStoreId UUID storeId, UpdateStorePolicyRequest request,
                                             UserPrincipal actor) {
        Store store = loadStore(storeId);
        RoleScopeGuard.assertIsOwnStoreManager(actor, storeId);

        // RULE-03-06 (mở rộng 2026-09-18 sang StorePolicy) — Store đã ARCHIVED là Terminal State
        // tuyệt đối, dữ liệu cấu hình con (cùng nhóm OperatingHours/StoreResource) bất biến chỉ đọc.
        if (store.getStatus() == StoreStatus.ARCHIVED) {
            throw new BusinessRuleViolationException("RULE-03-06",
                    "Store đã lưu trữ (ARCHIVED), không được cấu hình chính sách vận hành");
        }

        Optional<StorePolicy> existing = storePolicyRepository.findByStoreId(storeId);
        StorePolicy policy;
        if (existing.isPresent()) {
            policy = existing.get();
            // Check tường minh version — cùng lý do OrganizationPolicyServiceImpl.updatePolicy.
            if (!policy.getVersion().equals(request.version())) {
                throw new ConcurrencyConflictException("StorePolicy", storeId);
            }
            applyRequest(policy, request);
        } else {
            if (request.version() != 0L) {
                throw new ConcurrencyConflictException("StorePolicy", storeId);
            }
            policy = new StorePolicy(storeId);
            applyRequest(policy, request);
        }

        validateAndNormalizeSurcharge(policy);

        try {
            policy = storePolicyRepository.save(policy);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("StorePolicy", storeId);
        } catch (DataIntegrityViolationException ex) {
            throw new ConcurrencyConflictException("StorePolicy", storeId);
        }

        return storePolicyMapper.toResponse(policy, store.getOrganizationId());
    }

    private Store loadStore(UUID storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
    }

    private void applyRequest(StorePolicy policy, UpdateStorePolicyRequest request) {
        if (request.surchargeEnabled() != null) {
            policy.setSurchargeEnabled(request.surchargeEnabled());
        }
        if (request.surchargeType() != null) {
            policy.setSurchargeType(request.surchargeType());
        }
        if (request.surchargeValue() != null) {
            policy.setSurchargeValue(request.surchargeValue());
        }
    }

    /**
     * RULE-03-10 — validate trạng thái CUỐI CÙNG (sau khi áp toàn bộ partial update), không chỉ
     * field vừa gửi: surchargeEnabled=true bắt buộc có đủ type+value hợp lệ; surchargeEnabled=false
     * chuẩn hóa type/value về null (tránh dữ liệu rác, cùng tinh thần isClosed=true của OperatingHour).
     */
    private void validateAndNormalizeSurcharge(StorePolicy policy) {
        if (!policy.isSurchargeEnabled()) {
            policy.setSurchargeType(null);
            policy.setSurchargeValue(null);
            return;
        }

        if (policy.getSurchargeType() == null || policy.getSurchargeValue() == null) {
            throw new BusinessRuleViolationException("RULE-03-10",
                    "surchargeType và surchargeValue bắt buộc khi surchargeEnabled=true");
        }
        if (policy.getSurchargeValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleViolationException("RULE-03-10", "surchargeValue phải >= 0");
        }
        if (policy.getSurchargeType() == SurchargeType.PERCENTAGE
                && policy.getSurchargeValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleViolationException("RULE-03-10",
                    "surchargeValue phải trong khoảng 0-100 khi surchargeType=PERCENTAGE");
        }
    }
}
