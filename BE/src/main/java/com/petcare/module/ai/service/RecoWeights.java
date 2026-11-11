package com.petcare.module.ai.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Trọng số và công thức điểm BR-AI-21: {@code 100 × (wA·A + wH·H + wP·P)}, làm tròn HALF_UP một chữ số thập phân. BigDecimal
 * vì số thực nhị phân cho 92,00000000000001 ở ví dụ §7.3. {@code ConfigKey} chỉ kiểm khoảng từng khóa chứ không kiểm tổng
 * bằng 1 (07 §4.6): tổng lệch thì chia mỗi trọng số cho tổng để điểm không vượt 100; tổng bằng 0 thì dùng 0,5 / 0,3 / 0,2
 * (docs/adr/0133).
 */
record RecoWeights(BigDecimal attr, BigDecimal history, BigDecimal popularity) {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final RecoWeights DEFAULTS = new RecoWeights(new BigDecimal("0.5"), new BigDecimal("0.3"),
            new BigDecimal("0.2"));

    static RecoWeights normalized(BigDecimal attr, BigDecimal history, BigDecimal popularity) {
        BigDecimal sum = attr.add(history).add(popularity);
        if (sum.signum() <= 0) {
            return DEFAULTS;
        }
        return new RecoWeights(attr.divide(sum, MC), history.divide(sum, MC), popularity.divide(sum, MC));
    }

    BigDecimal score(BigDecimal a, BigDecimal h, BigDecimal p) {
        return attr.multiply(a).add(history.multiply(h)).add(popularity.multiply(p)).multiply(HUNDRED)
                .setScale(1, RoundingMode.HALF_UP);
    }
}
