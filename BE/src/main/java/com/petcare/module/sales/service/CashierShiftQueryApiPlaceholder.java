package com.petcare.module.sales.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.sales.api.CashierShiftQueryApi;

/**
 * Giữ chỗ cho {@link CashierShiftQueryApi} tới khi module sales (BE-2, hạn giao 21/10 — 06 §6) cài thật — nợ D016
 * (docs/dept/INDEX.md). BE-1 tạo thay owner (2026-10-11, docs/adr/0029) để T14 chạy được. Theo 06-module-contracts §1:
 * ném lỗi, không trả {@code Optional.empty()} "an toàn" — giá trị đó sẽ cho vô hiệu hóa lễ tân đang mở ca đi qua sai
 * BR-QT-08. BE-2 xóa lớp này (cùng {@code CashierShiftQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class CashierShiftQueryApiPlaceholder implements CashierShiftQueryApi {

    static final String MESSAGE = "CashierShiftQueryApi chưa được cài (module sales, BE-2) — nợ D016";

    @Override
    public Optional<OpenShift> findOpenShift(Long cashierAccountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
