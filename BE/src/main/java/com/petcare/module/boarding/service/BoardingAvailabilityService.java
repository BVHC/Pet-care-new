package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.BoardingAvailabilityResponse;
import com.petcare.module.boarding.dto.NightAvailabilityResponse;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Xem còn chỗ theo đêm (UC58, boarding-v1 #7; docs/adr/0065). Chỉ đọc, không khóa: kết quả chỉ để chọn ngày, đặt chỗ
 * kiểm lại dưới khóa chi nhánh. Khách chỉ thấy chi nhánh {@code ACTIVE}; lễ tân chỉ chi nhánh của mình.
 */
@Service
public class BoardingAvailabilityService {

    private final BranchScope branchScope;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final BoardingCapacity capacity;
    private final SystemConfigApi configs;
    private final Clock clock;

    public BoardingAvailabilityService(BranchScope branchScope, BranchQueryApi branches, CatalogQueryApi catalog,
            BoardingCapacity capacity, SystemConfigApi configs, Clock clock) {
        this.branchScope = branchScope;
        this.branches = branches;
        this.catalog = catalog;
        this.capacity = capacity;
        this.configs = configs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BoardingAvailabilityResponse availability(Long branchId, Long kennelTypeId, LocalDate checkInDate,
            LocalDate checkOutDate) {
        boolean customer = "CUSTOMER".equals(branchScope.current().role());
        if (!customer) {
            branchScope.check(branchId);
        }
        branches.findBranch(branchId).filter(found -> !customer || found.status() == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        KennelTypeInfo type = catalog.findKennelType(kennelTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("loại chuồng", kennelTypeId));
        checkNights(checkInDate, checkOutDate);
        if (!type.active() || !branches.isServiceEnabled(branchId, type.serviceId())) {
            throw new BusinessRuleViolationException("BR-LT-02", "Loại chuồng không đặt được tại chi nhánh này");
        }
        List<NightAvailabilityResponse> nights = capacity
                .nights(branchId, kennelTypeId, checkInDate, checkOutDate, LocalDate.now(clock)).stream()
                .map(n -> new NightAvailabilityResponse(n.date(), n.capacity(), n.occupied(), n.remaining())).toList();
        return new BoardingAvailabilityResponse(type.serviceId(), type.nightlyPrice(), nights,
                nights.stream().allMatch(n -> n.remaining() > 0));
    }

    /** BR-LT-02: ngày trả sau ngày nhận và số đêm trong {@code [boarding.min_nights, boarding.max_nights]} [CFG]. */
    public void checkNights(LocalDate checkInDate, LocalDate checkOutDate) {
        int min = configs.getInt(ConfigKey.BOARDING_MIN_NIGHTS);
        int max = configs.getInt(ConfigKey.BOARDING_MAX_NIGHTS);
        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        if (nights < min || nights > max) {
            throw new BusinessRuleViolationException("BR-LT-02",
                    "Thời gian lưu trú phải từ " + min + " đến " + max + " đêm và ngày trả phải sau ngày nhận");
        }
    }
}
