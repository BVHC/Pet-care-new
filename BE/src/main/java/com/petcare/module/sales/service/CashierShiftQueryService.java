package com.petcare.module.sales.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.CurrentShiftResponse;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.repository.CashierShiftListRepository;
import com.petcare.module.sales.repository.CashierShiftListRepository.Filter;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Đọc ca thu ngân (sales-v1 #17, #19, #20; UC70–UC72; docs/adr/0064). Chỉ đọc, không khóa. Chủ ca ở #17 luôn là người
 * đang đăng nhập. Danh sách (BRANCH_MANAGER) lọc theo chi nhánh của quản lý ({@code resolve(null)} trước mọi lần đọc),
 * đếm và lấy trang trong cùng một snapshot REPEATABLE READ. Chi tiết: BRANCH_MANAGER cùng chi nhánh, lễ tân chỉ ca của
 * mình.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierShiftQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CashierShiftRepository shifts;
    private final CashierShiftListRepository lists;
    private final CashierShiftAssembler assembler;
    private final BranchScope branchScope;

    /** #17: ca {@code OPEN} của tôi; {@code open = false} và không có {@code shift} khi chưa mở ca. */
    public CurrentShiftResponse getMyCurrentShift() {
        Long me = branchScope.current().accountId();
        return shifts.findFirstByCashierIdAndStatus(me, CashierShiftStatus.OPEN)
                .map(shift -> new CurrentShiftResponse(true, assembler.toResponse(shift)))
                .orElseGet(() -> new CurrentShiftResponse(false, null));
    }

    /** #19: ca của chi nhánh quản lý, mới nhất trước. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<CashierShiftResponse> listCashierShifts(CashierShiftStatus status, LocalDate businessDate,
            Long cashierId, int page, int size) {
        Long branchId = branchScope.resolve(null);
        Filter filter = new Filter(branchId, status, businessDate, cashierId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        long total = lists.count(filter);
        List<CashierShiftResponse> content = List.of();
        if (total > 0) {
            List<Long> ids = lists.findPageIds(filter, pageable.getOffset(), pageable.getPageSize());
            content = assembler.toResponses(inOrder(ids, shifts.findAllById(ids)));
        }
        return PageResponse.of(new PageImpl<>(content, pageable, total));
    }

    /** #20: BRANCH_MANAGER cùng chi nhánh; lễ tân chỉ ca của mình. */
    public CashierShiftResponse getCashierShift(Long shiftId) {
        CashierShift shift = shifts.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("ca thu ngân", shiftId));
        var me = branchScope.current();
        if ("RECEPTIONIST".equals(me.role())) {
            if (!me.accountId().equals(shift.getCashierId())) {
                throw new AccessDeniedScopeException("cashierShift:" + shiftId, "cashier:" + me.accountId());
            }
        } else {
            branchScope.check(shift.getBranchId());
        }
        return assembler.toResponse(shift);
    }

    private static List<CashierShift> inOrder(List<Long> ids, List<CashierShift> loaded) {
        Map<Long, CashierShift> byId = new HashMap<>();
        loaded.forEach(shift -> byId.put(shift.getId(), shift));
        List<CashierShift> ordered = new ArrayList<>();
        ids.forEach(id -> ordered.add(byId.get(id)));
        return ordered;
    }
}
