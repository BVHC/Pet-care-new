package com.petcare.module.identity.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.ConfigValueType;

/**
 * Ràng buộc chéo giữa các tham số [CFG] (docs/adr/0036 mục 1, trả TBD của ADR-0004; cặp thứ ba: docs/adr/0051). Ba cặp,
 * đều kiểu INT:
 * <ul>
 *   <li>{@code visit.late_priority_minutes < appointment.no_show_after_minutes}: BR-TN-03 xếp "trễ 15–30 phút" giữa hai
 *       mốc này, mốc NO_SHOW là BR-LH-08.</li>
 *   <li>{@code boarding.min_nights ≤ boarding.max_nights}: BR-LT-02 "từ 1 đến 30 đêm".</li>
 *   <li>{@code appointment.late_cancel_hours < appointment.reminder_hours_before}: BR-LH-12 nhắc lịch "trước mốc hủy
 *       muộn" (BR-LH-07) để khách còn kịp hủy / đổi mà không bị tính hủy muộn.</li>
 * </ul>
 * Mọi cặp khác trong V2 đã được min–max của từng khóa đảm bảo (bảng rà ở ADR-0036). Kiểm khi nạp
 * ({@link SystemConfigService#load}, fail-fast) và khi sửa ({@link SystemConfigEditService}, dưới khóa dòng của cả cặp).
 */
final class ConfigConstraints {

    /** {@code lower} phải nhỏ hơn ({@code strict}) hoặc không lớn hơn {@code upper}. */
    record Constraint(ConfigKey lower, ConfigKey upper, boolean strict) {

        Constraint {
            if (lower.type() != ConfigValueType.INT || upper.type() != ConfigValueType.INT) {
                throw new IllegalArgumentException("Cross-key constraints only support INT keys");
            }
        }

        boolean holds(int lowerValue, int upperValue) {
            return strict ? lowerValue < upperValue : lowerValue <= upperValue;
        }
    }

    /** Khoảng [min, max] sau khi thu hẹp theo khóa kia; {@code reasons} chỉ có khi khoảng thật sự bị thu hẹp. */
    record Narrowed(Integer min, Integer max, List<String> reasons) {}

    static final List<Constraint> ALL = List.of(
            new Constraint(ConfigKey.VISIT_LATE_PRIORITY_MINUTES, ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES, true),
            new Constraint(ConfigKey.BOARDING_MIN_NIGHTS, ConfigKey.BOARDING_MAX_NIGHTS, false),
            new Constraint(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS, ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE, true));

    private ConfigConstraints() {
    }

    /** Các khóa khác cùng cặp với {@code key} (để khóa dòng cùng lúc). */
    static List<ConfigKey> partnersOf(ConfigKey key) {
        List<ConfigKey> partners = new ArrayList<>();
        for (Constraint constraint : ALL) {
            if (constraint.lower() == key) {
                partners.add(constraint.upper());
            } else if (constraint.upper() == key) {
                partners.add(constraint.lower());
            }
        }
        return partners;
    }

    /**
     * Thu hẹp [min, max] tĩnh của {@code key} theo giá trị hiện hành của khóa kia ({@code current}, đọc dưới khóa dòng).
     * Ví dụ {@code no_show = 30} thì {@code late_priority} từ [0, 30] còn [0, 29].
     */
    static Narrowed narrow(ConfigKey key, Integer min, Integer max, Function<ConfigKey, Integer> current) {
        Integer narrowedMin = min;
        Integer narrowedMax = max;
        List<String> reasons = new ArrayList<>();
        for (Constraint constraint : ALL) {
            if (constraint.lower() == key) {
                int upper = current.apply(constraint.upper());
                int bound = constraint.strict() ? upper - 1 : upper;
                if (narrowedMax == null || bound < narrowedMax) {
                    narrowedMax = bound;
                    reasons.add((constraint.strict() ? "phải nhỏ hơn " : "không được lớn hơn ")
                            + constraint.upper().key() + " đang là " + upper);
                }
            } else if (constraint.upper() == key) {
                int lower = current.apply(constraint.lower());
                int bound = constraint.strict() ? lower + 1 : lower;
                if (narrowedMin == null || bound > narrowedMin) {
                    narrowedMin = bound;
                    reasons.add((constraint.strict() ? "phải lớn hơn " : "không được nhỏ hơn ")
                            + constraint.lower().key() + " đang là " + lower);
                }
            }
        }
        return new Narrowed(narrowedMin, narrowedMax, List.copyOf(reasons));
    }

    /** Lỗi của bộ giá trị đã nạp (log khởi động, tiếng Anh như các lỗi khác của {@code load}). */
    static List<String> violations(Map<ConfigKey, Object> values) {
        List<String> errors = new ArrayList<>();
        for (Constraint constraint : ALL) {
            Object lower = values.get(constraint.lower());
            Object upper = values.get(constraint.upper());
            if (lower instanceof Integer lowerValue && upper instanceof Integer upperValue
                    && !constraint.holds(lowerValue, upperValue)) {
                errors.add(constraint.lower().key() + " (" + lowerValue + ") must be "
                        + (constraint.strict() ? "<" : "<=") + " " + constraint.upper().key() + " (" + upperValue + ")");
            }
        }
        return errors;
    }
}
