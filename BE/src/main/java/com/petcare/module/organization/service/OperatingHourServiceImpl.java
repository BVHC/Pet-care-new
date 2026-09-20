package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.ConfigureOperatingHoursRequest;
import com.petcare.module.organization.dto.OperatingHourItem;
import com.petcare.module.organization.dto.OperatingHoursResponse;
import com.petcare.module.organization.entity.OperatingHour;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.mapper.OperatingHourMapper;
import com.petcare.module.organization.repository.OperatingHourRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — ConfigureOperatingHour (RULE-03-02/07).
 * Actor CHỈ STORE_MANAGER đúng Store mình quản lý (quyết định 2026-09-17, xem
 * RoleScopeGuard#assertIsOwnStoreManager) — khác các UC Store khác vốn cho cả
 * SUPER_ADMIN/ORGANIZATION_ADMIN.
 */
@Service
@RequiredArgsConstructor
public class OperatingHourServiceImpl implements OperatingHourService {

    private final StoreRepository storeRepository;
    private final OperatingHourRepository operatingHourRepository;
    private final OperatingHourMapper operatingHourMapper;

    @Override
    @Transactional
    @Auditable(action = "ConfigureOperatingHour", resourceType = "Store")
    public OperatingHoursResponse configureOperatingHours(@AuditResourceId UUID storeId,
                                                           ConfigureOperatingHoursRequest request,
                                                           UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        RoleScopeGuard.assertIsOwnStoreManager(actor, storeId);

        // RULE-03-06 (gốc, Phase 4 — GAP-ORG-01): OperatingHours là dữ liệu cấu hình con của
        // Store, trở thành bất biến chỉ đọc khi Store đã ARCHIVED — không phải quyết định mới
        // như UpdateStore, mà là áp dụng trực tiếp rule đã có sẵn từ trước.
        if (store.getStatus() == StoreStatus.ARCHIVED) {
            throw new BusinessRuleViolationException("RULE-03-06",
                    "Store đã lưu trữ (ARCHIVED), không được cấu hình giờ hoạt động");
        }

        validateNoDuplicateDays(request.hours());
        List<OperatingHour> entities = request.hours().stream()
                .map(item -> toValidatedEntity(storeId, item))
                .toList();

        // Semantics replace-all (đã thảo luận, chốt 2026-09-17): xóa sạch cấu hình cũ rồi ghi
        // lại đúng mảng gửi lên — ngày không có trong request coi như chưa cấu hình sau lệnh này,
        // không phải upsert-giữ-nguyên-ngày-thiếu. Dùng bulk DELETE (xem
        // OperatingHourRepository#deleteAllByStoreId) để tránh đụng UNIQUE constraint do thứ tự
        // flush INSERT-trước-DELETE mặc định của Hibernate.
        operatingHourRepository.deleteAllByStoreId(storeId);
        try {
            operatingHourRepository.saveAll(entities);
        } catch (DataIntegrityViolationException ex) {
            // RULE-03-07 — race condition: 2 request PUT gần như đồng thời lên CÙNG Store (vd
            // double-click/retry) đều DELETE thành công (idempotent) rồi cùng INSERT; nếu 2
            // payload trùng dayOfWeek nào đó, request thua đụng UNIQUE uq_operating_hours_store_day
            // — trả lỗi nghiệp vụ sạch (400) thay vì rơi xuống handleGeneric (500), cùng pattern
            // OrganizationServiceImpl.createOrganization/StoreServiceImpl.createStore.
            throw new BusinessRuleViolationException("RULE-03-07",
                    "Xung đột dữ liệu khi ghi giờ hoạt động, vui lòng thử lại");
        }

        // Đọc lại theo đúng dayOfWeek (không dùng list trả về từ saveAll, vốn giữ nguyên thứ tự
        // request gửi lên) — response luôn nhất quán theo thứ tự ngày, không phụ thuộc client
        // gửi mảng theo thứ tự nào.
        List<OperatingHour> saved = operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId);
        return operatingHourMapper.toResponse(storeId, store.getOrganizationId(), saved);
    }

    /** RULE-03-07 — không được cấu hình trùng dayOfWeek trong cùng 1 request (mơ hồ ngày nào thắng). */
    private void validateNoDuplicateDays(List<OperatingHourItem> hours) {
        long distinctDays = hours.stream().map(OperatingHourItem::dayOfWeek).distinct().count();
        if (distinctDays != hours.size()) {
            throw new BusinessRuleViolationException("RULE-03-07", "Trùng dayOfWeek trong cùng 1 request");
        }
    }

    /**
     * RULE-03-07 (derived, org-store-v1.md C3) — ngày mở cửa (`isClosed=false`) bắt buộc có đủ
     * `openTime`/`closeTime` và `openTime < closeTime`; ngày nghỉ (`isClosed=true`) không được
     * kèm giờ nào (tránh cấu hình mâu thuẫn: vừa nghỉ vừa có khung giờ mở cửa).
     */
    private OperatingHour toValidatedEntity(UUID storeId, OperatingHourItem item) {
        boolean closed = Boolean.TRUE.equals(item.isClosed());
        if (closed) {
            if (item.openTime() != null || item.closeTime() != null) {
                throw new BusinessRuleViolationException("RULE-03-07",
                        "Ngày nghỉ (isClosed=true) không được có openTime/closeTime");
            }
        } else {
            if (item.openTime() == null || item.closeTime() == null) {
                throw new BusinessRuleViolationException("RULE-03-07",
                        "Ngày mở cửa bắt buộc có đủ openTime và closeTime");
            }
            if (!item.openTime().isBefore(item.closeTime())) {
                throw new BusinessRuleViolationException("RULE-03-07", "openTime phải nhỏ hơn closeTime");
            }
        }
        return new OperatingHour(storeId, item.dayOfWeek(), item.openTime(), item.closeTime(), closed);
    }
}
