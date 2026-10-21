package com.petcare.module.sales.service;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.repository.CashierShiftRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link CashierShiftQueryApi} thật (T30, trả nợ D016; docs/adr/0064). Một câu SQL đọc thuần, không khóa; class
 * {@code readOnly} nên tham gia transaction của bên gọi (identity: điều chuyển, vô hiệu hóa, khóa A05–A08 — BR-QT-08,
 * BR-QT-11). Mở ca khóa {@code accounts FOR SHARE} của lễ tân (ADR-0031 mục 9) nên kết quả đọc ở đây không thể bỏ sót
 * một ca mở song song với thao tác của identity.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierShiftLookupService implements CashierShiftQueryApi {

    private final CashierShiftRepository shifts;

    @Override
    public Optional<OpenShift> findOpenShift(Long cashierAccountId) {
        Objects.requireNonNull(cashierAccountId, "cashierAccountId");
        return shifts.findOpenShiftRow(cashierAccountId).map(row -> new OpenShift(row.getId(), row.getBranchId()));
    }
}
