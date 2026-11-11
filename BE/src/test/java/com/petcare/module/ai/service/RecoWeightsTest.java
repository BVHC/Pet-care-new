package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Công thức điểm BR-AI-21 {@code 100 × (wA·A + wH·H + wP·P)} tính bằng BigDecimal: số thực nhị phân cho 92,00000000000001
 * ở ví dụ §7.3 nên vừa sai phép so sánh bằng, vừa phá thứ tự "trùng điểm thì id tăng dần". Đặc tả chỉ ghi "tổng bằng 1"
 * mà {@code ConfigKey} không kiểm tổng (ADR-0133): tổng lệch thì chia cho tổng, tổng bằng 0 thì dùng 0,5 / 0,3 / 0,2.
 */
class RecoWeightsTest {

    private static final RecoWeights DEFAULT = RecoWeights.normalized(bd("0.5"), bd("0.3"), bd("0.2"));

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void workedExampleBathIsExactly92() {
        assertThat(DEFAULT.score(bd("1"), bd("1"), bd("0.6"))).hasToString("92.0");
    }

    @Test
    void workedExampleSpaIsExactly37() {
        assertThat(DEFAULT.score(bd("0.3"), bd("0.4"), bd("0.5"))).hasToString("37.0");
    }

    @Test
    void perfectComponentsGive100() {
        assertThat(DEFAULT.score(bd("1"), bd("1"), bd("1"))).hasToString("100.0");
    }

    @Test
    void roundsHalfUpToOneDecimalPlace() {
        // 100 × 0,0625 = 6,25: HALF_UP cho 6,3 còn HALF_EVEN cho 6,2.
        RecoWeights onlyAttr = RecoWeights.normalized(bd("1"), bd("0"), bd("0"));

        assertThat(onlyAttr.score(bd("0.0625"), bd("1"), bd("1"))).hasToString("6.3");
    }

    @Test
    void weightsThatDoNotSumToOneAreDividedByTheirSum() {
        RecoWeights thirds = RecoWeights.normalized(bd("1"), bd("1"), bd("1"));

        assertThat(thirds.score(bd("1"), bd("1"), bd("1"))).as("không vượt 100").hasToString("100.0");
        assertThat(thirds.score(bd("1"), bd("0"), bd("0"))).hasToString("33.3");
    }

    @Test
    void weightsSummingToZeroFallBackToTheDefaults() {
        RecoWeights zero = RecoWeights.normalized(bd("0"), bd("0"), bd("0"));

        assertThat(zero.score(bd("1"), bd("1"), bd("0.6"))).hasToString("92.0");
    }

    @Test
    void aSingleZeroWeightSwitchesThatComponentOff() {
        RecoWeights noHistory = RecoWeights.normalized(bd("0.5"), bd("0"), bd("0.5"));

        assertThat(noHistory.score(bd("1"), bd("1"), bd("0"))).hasToString("50.0");
    }
}
