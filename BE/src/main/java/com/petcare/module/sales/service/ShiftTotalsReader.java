package com.petcare.module.sales.service;

import org.springframework.stereotype.Component;

import com.petcare.module.sales.entity.PaymentMethod;
import com.petcare.module.sales.entity.ShiftTotals;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.PaymentRepository.MethodTotal;

import lombok.RequiredArgsConstructor;

/**
 * Tổng phiếu thu của một ca theo phương thức (docs/adr/0064). Gọi <b>sau khi khóa dòng ca</b>: thu tiền giữ ca
 * {@code FOR SHARE} tới khi commit, nên khi khóa được ca (READ COMMITTED) mọi phiếu thu đã commit đều nằm trong tổng.
 */
@Component
@RequiredArgsConstructor
public class ShiftTotalsReader {

    private final PaymentRepository payments;

    public ShiftTotals read(Long shiftId) {
        long cash = 0;
        long transfer = 0;
        for (MethodTotal row : payments.totalsByMethod(shiftId)) {
            if (row.getMethod() == PaymentMethod.CASH) {
                cash = row.getTotal();
            } else if (row.getMethod() == PaymentMethod.TRANSFER) {
                transfer = row.getTotal();
            }
        }
        return new ShiftTotals(cash, transfer);
    }
}
