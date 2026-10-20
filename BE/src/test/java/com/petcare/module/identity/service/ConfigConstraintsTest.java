package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.ConfigKey;

/**
 * Ràng buộc chéo [CFG] (docs/adr/0036 mục 1, docs/adr/0051): đúng ba cặp, {@code <} ở BR-TN-03 và BR-LH-12, {@code ≤} ở
 * BR-LT-02;
 * khoảng thu hẹp theo khóa kia, kèm lý do chỉ khi thật sự thu hẹp.
 */
class ConfigConstraintsTest {

    @Test
    void exactlyTheThreeAgreedPairs() {
        assertThat(ConfigConstraints.ALL).containsExactly(
                new ConfigConstraints.Constraint(ConfigKey.VISIT_LATE_PRIORITY_MINUTES,
                        ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES, true),
                new ConfigConstraints.Constraint(ConfigKey.BOARDING_MIN_NIGHTS, ConfigKey.BOARDING_MAX_NIGHTS, false),
                new ConfigConstraints.Constraint(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS,
                        ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, true));
    }

    @Test
    void eachKeyBelongsToAtMostOnePair() {
        Set<ConfigKey> seen = new HashSet<>();
        for (ConfigConstraints.Constraint constraint : ConfigConstraints.ALL) {
            assertThat(seen.add(constraint.lower())).isTrue();
            assertThat(seen.add(constraint.upper())).isTrue();
        }
    }

    @Test
    void partners() {
        assertThat(ConfigConstraints.partnersOf(ConfigKey.VISIT_LATE_PRIORITY_MINUTES))
                .containsExactly(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES);
        assertThat(ConfigConstraints.partnersOf(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES))
                .containsExactly(ConfigKey.VISIT_LATE_PRIORITY_MINUTES);
        assertThat(ConfigConstraints.partnersOf(ConfigKey.BOARDING_MAX_NIGHTS))
                .containsExactly(ConfigKey.BOARDING_MIN_NIGHTS);
        assertThat(ConfigConstraints.partnersOf(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS))
                .containsExactly(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE);
        assertThat(ConfigConstraints.partnersOf(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE))
                .containsExactly(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS);
        assertThat(ConfigConstraints.partnersOf(ConfigKey.OTP_TTL_MINUTES)).isEmpty();
    }

    @Test
    void onlyIntKeysAreAllowed() {
        assertThatThrownBy(() -> new ConfigConstraints.Constraint(ConfigKey.BOARDING_CHECKOUT_TIME,
                ConfigKey.BOARDING_MAX_NIGHTS, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictPairExcludesEquality() {
        assertThat(ConfigConstraints.violations(values(15, 30, 1, 30))).isEmpty();
        assertThat(ConfigConstraints.violations(values(29, 30, 1, 30))).isEmpty();
        assertThat(ConfigConstraints.violations(values(30, 30, 1, 30))).containsExactly(
                "visit.late_priority_minutes (30) must be < appointment.no_show_after_minutes (30)");
    }

    @Test
    void reminderMustComeBeforeLateCancelMark() {
        Map<ConfigKey, Object> values = values(15, 30, 1, 30);
        values.put(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS, 12);
        values.put(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, 24);
        assertThat(ConfigConstraints.violations(values)).isEmpty();
        values.put(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, 13);
        assertThat(ConfigConstraints.violations(values)).isEmpty();
        values.put(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, 12);
        assertThat(ConfigConstraints.violations(values)).containsExactly(
                "appointment.late_cancel_hours (12) must be < appointment.reminder_hours_before (12)");
        values.put(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, 6);
        assertThat(ConfigConstraints.violations(values)).containsExactly(
                "appointment.late_cancel_hours (12) must be < appointment.reminder_hours_before (6)");
    }

    @Test
    void narrowsReminderPairBothWays() {
        assertThat(ConfigConstraints.narrow(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS, 0, 48, key -> 24))
                .isEqualTo(new ConfigConstraints.Narrowed(0, 23,
                        List.of("phải nhỏ hơn appointment.reminder_hours_before đang là 24")));
        assertThat(ConfigConstraints.narrow(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, 1, 72, key -> 12))
                .isEqualTo(new ConfigConstraints.Narrowed(13, 72,
                        List.of("phải lớn hơn appointment.late_cancel_hours đang là 12")));
    }

    @Test
    void nonStrictPairAllowsEquality() {
        assertThat(ConfigConstraints.violations(values(15, 30, 7, 7))).isEmpty();
        assertThat(ConfigConstraints.violations(values(15, 30, 8, 7)))
                .containsExactly("boarding.min_nights (8) must be <= boarding.max_nights (7)");
    }

    @Test
    void narrowsLowerKeyByUpperValue() {
        assertThat(ConfigConstraints.narrow(ConfigKey.VISIT_LATE_PRIORITY_MINUTES, 0, 30, key -> 30))
                .isEqualTo(new ConfigConstraints.Narrowed(0, 29,
                        List.of("phải nhỏ hơn appointment.no_show_after_minutes đang là 30")));
        assertThat(ConfigConstraints.narrow(ConfigKey.BOARDING_MIN_NIGHTS, 1, 7, key -> 3))
                .isEqualTo(new ConfigConstraints.Narrowed(1, 3,
                        List.of("không được lớn hơn boarding.max_nights đang là 3")));
    }

    @Test
    void narrowsUpperKeyByLowerValue() {
        assertThat(ConfigConstraints.narrow(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES, 15, 120, key -> 15))
                .isEqualTo(new ConfigConstraints.Narrowed(16, 120,
                        List.of("phải lớn hơn visit.late_priority_minutes đang là 15")));
        assertThat(ConfigConstraints.narrow(ConfigKey.BOARDING_MAX_NIGHTS, 1, 90, key -> 5))
                .isEqualTo(new ConfigConstraints.Narrowed(5, 90,
                        List.of("không được nhỏ hơn boarding.min_nights đang là 5")));
    }

    @Test
    void noReasonWhenStaticRangeIsAlreadyTighter() {
        // min_nights tĩnh [1, 7], max_nights = 30 → không thu hẹp.
        assertThat(ConfigConstraints.narrow(ConfigKey.BOARDING_MIN_NIGHTS, 1, 7, key -> 30))
                .isEqualTo(new ConfigConstraints.Narrowed(1, 7, List.of()));
        assertThat(ConfigConstraints.narrow(ConfigKey.OTP_TTL_MINUTES, 1, 15, key -> {
            throw new AssertionError("không có khóa kia");
        })).isEqualTo(new ConfigConstraints.Narrowed(1, 15, List.of()));
    }

    private static Map<ConfigKey, Object> values(int late, int noShow, int minNights, int maxNights) {
        Map<ConfigKey, Object> values = new EnumMap<>(ConfigKey.class);
        values.put(ConfigKey.VISIT_LATE_PRIORITY_MINUTES, late);
        values.put(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES, noShow);
        values.put(ConfigKey.BOARDING_MIN_NIGHTS, minNights);
        values.put(ConfigKey.BOARDING_MAX_NIGHTS, maxNights);
        return values;
    }
}
