package com.petcare.module.branch.service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.dto.QuotaDefaultResponse;
import com.petcare.module.branch.dto.SetSlotQuotaRequest;
import com.petcare.module.branch.dto.SlotQuotaResponse;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchQuotaDefault;
import com.petcare.module.branch.entity.BranchQuotaDefaultId;
import com.petcare.module.branch.entity.SlotQuota;
import com.petcare.module.branch.repository.BranchQuotaDefaultRepository;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.SlotQuotaRepository;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC42 — quota lịch hẹn (BR-LH-03): quota mặc định theo chi nhánh × nhóm dịch vụ và quota riêng cho một khung giờ
 * (0 = khóa khung). Giảm quota không ảnh hưởng lịch đã đặt, chỉ chặn đặt mới, nên không phát sự kiện. Quota chỉ có
 * với Khám/Tiêm và Thẩm mỹ; Lưu trú không có khung giờ.
 */
@Service
public class QuotaService {

    /** Cột {@code quota} và {@code default_quota} là {@code SMALLINT}. */
    static final int MAX_QUOTA = Short.MAX_VALUE;

    private static final List<ServiceGroup> QUOTA_GROUPS = List.of(ServiceGroup.MEDICAL, ServiceGroup.GROOMING);

    private final BranchRepository branches;
    private final BranchQuotaDefaultRepository defaults;
    private final SlotQuotaRepository slotQuotas;
    private final ScheduleLoader scheduleLoader;
    private final BranchScope scope;

    public QuotaService(BranchRepository branches, BranchQuotaDefaultRepository defaults,
            SlotQuotaRepository slotQuotas, ScheduleLoader scheduleLoader, BranchScope scope) {
        this.branches = branches;
        this.defaults = defaults;
        this.slotQuotas = slotQuotas;
        this.scheduleLoader = scheduleLoader;
        this.scope = scope;
    }

    /** Cả hai nhóm; nhóm chưa cấu hình có {@code defaultQuota} null (áp dụng quota 1). */
    @Transactional(readOnly = true)
    public List<QuotaDefaultResponse> listDefaults(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        Map<ServiceGroup, Integer> configured = new EnumMap<>(ServiceGroup.class);
        defaults.findByBranchId(branchId)
                .forEach(q -> configured.put(q.getId().getServiceGroup(), q.getDefaultQuota()));
        return QUOTA_GROUPS.stream().map(g -> new QuotaDefaultResponse(g, configured.get(g))).toList();
    }

    @Transactional
    public QuotaDefaultResponse setDefault(Long branchId, ServiceGroup group, int quota) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        requireQuotaGroup(group);
        requireQuotaValue(quota);
        Long actor = scope.current().accountId();
        BranchQuotaDefault entry = defaults.findById(new BranchQuotaDefaultId(branchId, group))
                .orElseGet(() -> new BranchQuotaDefault(branchId, group, quota, actor));
        entry.setDefaultQuota(quota);
        entry.setUpdatedBy(actor);
        defaults.saveAndFlush(entry);
        return new QuotaDefaultResponse(group, quota);
    }

    @Transactional(readOnly = true)
    public List<SlotQuotaResponse> listSlotQuotas(Long branchId, LocalDate from, LocalDate to, ServiceGroup group) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        if (from.isAfter(to)) {
            throw new BusinessRuleViolationException("BR-LH-03", "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        List<SlotQuota> found;
        if (group == null) {
            found = slotQuotas.findByBranchIdAndSlotDateBetweenOrderBySlotDateAscSlotStartAscServiceGroupAsc(
                    branchId, from, to);
        } else {
            requireQuotaGroup(group);
            found = slotQuotas.findByBranchIdAndServiceGroupAndSlotDateBetweenOrderBySlotDateAscSlotStartAsc(
                    branchId, group, from, to);
        }
        return found.stream().map(QuotaService::toResponse).toList();
    }

    /**
     * Tạo mới hoặc ghi đè quota riêng của khung. Khung phải đúng là khung appointment sẽ sinh (BR-LH-02): trong giờ
     * mở cửa, không phải ngày nghỉ, đúng bước 30 phút từ giờ mở của khoảng; quota của giờ lẻ sẽ không bao giờ được dùng.
     */
    @Transactional
    public SlotQuotaResponse setSlotQuota(Long branchId, SetSlotQuotaRequest request) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        requireQuotaGroup(request.serviceGroup());
        requireQuotaValue(request.quota());
        if (!scheduleLoader.load(branchId).isGeneratedSlot(request.slotDate(), request.slotStart())) {
            throw new BusinessRuleViolationException("BR-LH-02", "Khung " + request.slotStart() + " ngày "
                    + request.slotDate() + " không phải khung đặt lịch của chi nhánh (ngoài giờ mở cửa, ngày nghỉ,"
                    + " hoặc không đúng bước 30 phút từ giờ mở cửa)");
        }
        Long actor = scope.current().accountId();
        SlotQuota entry = slotQuotas.findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(branchId,
                        request.serviceGroup(), request.slotDate(), request.slotStart())
                .orElseGet(() -> new SlotQuota(branchId, request.serviceGroup(), request.slotDate(),
                        request.slotStart(), request.quota(), actor));
        entry.setQuota(request.quota());
        entry.setUpdatedBy(actor);
        return toResponse(slotQuotas.saveAndFlush(entry));
    }

    /** Bỏ quota riêng, khung quay về quota mặc định (branch-v1 A4). */
    @Transactional
    public void deleteSlotQuota(Long branchId, Long slotQuotaId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        SlotQuota entry = slotQuotas.findByIdAndBranchId(slotQuotaId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Quota khung giờ", slotQuotaId));
        slotQuotas.delete(entry);
        slotQuotas.flush();
    }

    private static void requireQuotaGroup(ServiceGroup group) {
        if (!QUOTA_GROUPS.contains(group)) {
            throw new BusinessRuleViolationException("BR-LH-03",
                    "Quota chỉ có với nhóm Khám/Tiêm và Thẩm mỹ, nhóm Lưu trú không có khung giờ");
        }
    }

    private static void requireQuotaValue(int quota) {
        if (quota < 0 || quota > MAX_QUOTA) {
            throw new BusinessRuleViolationException("BR-LH-03", "Quota phải từ 0 đến " + MAX_QUOTA);
        }
    }

    private static SlotQuotaResponse toResponse(SlotQuota q) {
        return new SlotQuotaResponse(q.getId(), q.getServiceGroup(), q.getSlotDate(), q.getSlotStart(), q.getQuota());
    }

    private static ResourceNotFoundException notFound(Long branchId) {
        return new ResourceNotFoundException("Chi nhánh", branchId);
    }
}
