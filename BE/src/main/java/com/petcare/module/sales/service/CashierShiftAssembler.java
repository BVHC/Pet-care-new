package com.petcare.module.sales.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.PaymentRepository.ShiftCount;

import lombok.RequiredArgsConstructor;

/**
 * Ca thu ngân → {@link CashierShiftResponse} (docs/adr/0064). Tên lễ tân qua <b>một</b> {@code findStaffByIds} và số
 * phiếu thu qua <b>một</b> câu GROUP BY cho cả trang, bất kể số ca. Chạy trong transaction của service gọi (OSIV tắt).
 * Lễ tân không còn trong danh bạ là dữ liệu hỏng → {@link IllegalStateException}.
 */
@Component
@RequiredArgsConstructor
public class CashierShiftAssembler {

    private final StaffDirectoryApi staffDirectory;
    private final PaymentRepository payments;

    public CashierShiftResponse toResponse(CashierShift shift) {
        return toResponses(List.of(shift)).get(0);
    }

    public List<CashierShiftResponse> toResponses(List<CashierShift> shifts) {
        if (shifts.isEmpty()) {
            return List.of();
        }
        Set<Long> cashierIds = shifts.stream().map(CashierShift::getCashierId).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        for (StaffSummary staff : staffDirectory.findStaffByIds(cashierIds)) {
            names.put(staff.accountId(), staff.fullName());
        }
        Map<Long, Long> counts = new HashMap<>();
        for (ShiftCount count : payments.countByShiftIds(shifts.stream().map(CashierShift::getId).toList())) {
            counts.put(count.getShiftId(), count.getCount());
        }
        return shifts.stream().map(shift -> {
            String name = names.get(shift.getCashierId());
            if (name == null) {
                throw new IllegalStateException("Ca #" + shift.getId() + " có lễ tân #" + shift.getCashierId()
                        + " không còn trong danh bạ nhân viên");
            }
            return new CashierShiftResponse(shift.getId(), shift.getBranchId(), shift.getCashierId(), name,
                    shift.isAfterHours(), shift.getBusinessDate(), shift.getStatus(), shift.getOpenedAt(),
                    shift.getClosedAt(), shift.isAutoClosed(), shift.getExpectedCash(), shift.getExpectedTransfer(),
                    shift.getCountedCash(), shift.getDifference(), shift.getReconcileNote(), shift.getReconciledBy(),
                    shift.getReconciledAt(), Math.toIntExact(counts.getOrDefault(shift.getId(), 0L)));
        }).toList();
    }
}
