package com.petcare.module.branch.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.dto.BranchServiceItem;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchServiceSetting;
import com.petcare.module.branch.entity.BranchServiceSettingId;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.BranchServiceSettingRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC33 — bật / tắt dịch vụ, kể cả loại chuồng, tại chi nhánh (BR-LH-01, BR-SP-04). Chưa có dòng nghĩa là chưa bật.
 * Tắt dịch vụ chỉ chặn đặt mới; lịch hẹn và đặt chỗ đã có giữ nguyên (branch-v1 mục B), nên không phát sự kiện.
 */
@Service
public class BranchServiceAvailabilityService {

    private final BranchRepository branches;
    private final BranchServiceSettingRepository settings;
    private final CatalogQueryApi catalog;
    private final BranchScope scope;

    public BranchServiceAvailabilityService(BranchRepository branches, BranchServiceSettingRepository settings,
            CatalogQueryApi catalog, BranchScope scope) {
        this.branches = branches;
        this.settings = settings;
        this.catalog = catalog;
        this.scope = scope;
    }

    /** Mọi dịch vụ đang kinh doanh của danh mục, kèm cờ bật tại chi nhánh này. */
    @Transactional(readOnly = true)
    public List<BranchServiceItem> listServices(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        Map<Long, Boolean> enabled = settings.findByBranchId(branchId).stream()
                .collect(Collectors.toMap(s -> s.getId().getServiceId(), BranchServiceSetting::isEnabled));
        return catalog.listActiveServices().stream()
                .map(s -> new BranchServiceItem(s.serviceId(), s.name(), s.group(),
                        enabled.getOrDefault(s.serviceId(), false)))
                .toList();
    }

    /** Dịch vụ đã ngừng kinh doanh thì không bật được, nhưng vẫn tắt được để dọn cấu hình cũ. */
    @Transactional
    public BranchServiceItem setService(Long branchId, Long serviceId, boolean enabled) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        ServiceInfo service = catalog.findService(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Dịch vụ", serviceId));
        if (enabled && !service.active()) {
            throw new BusinessRuleViolationException("BR-LH-01",
                    "Dịch vụ đã ngừng kinh doanh, không bật được tại chi nhánh");
        }
        BranchServiceSetting setting = settings.findById(new BranchServiceSettingId(branchId, serviceId))
                .orElseGet(() -> new BranchServiceSetting(branchId, serviceId, enabled));
        setting.setEnabled(enabled);
        settings.saveAndFlush(setting);
        return new BranchServiceItem(service.serviceId(), service.name(), service.group(), enabled);
    }

    private static ResourceNotFoundException notFound(Long branchId) {
        return new ResourceNotFoundException("Chi nhánh", branchId);
    }
}
