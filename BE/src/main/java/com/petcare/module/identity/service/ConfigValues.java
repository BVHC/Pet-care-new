package com.petcare.module.identity.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.service.SystemConfigService.ConfigRow;

/**
 * Bộ parse dùng chung của tham số [CFG] (BR-QT-13; docs/adr/0004, 0036 mục 3). Nạp lúc khởi động
 * ({@link SystemConfigService#load}) và sửa qua API ({@link SystemConfigEditService}) đi qua cùng {@link #parse}, nên một
 * giá trị API đã nhận luôn nạp lại được. Ở API, {@link #parseRequest} chặt hơn: TIME chỉ {@code HH:mm}, DECIMAL không nhận
 * dạng mũ ({@code 1e999999999} sẽ thành chuỗi hàng tỷ ký tự khi chuẩn hóa).
 */
final class ConfigValues {

    private static final Pattern TIME_HH_MM = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    private ConfigValues() {
    }

    /** Parse theo {@code type}; sai thì {@link IllegalArgumentException} (message tiếng Anh, chỉ cho log khởi động). */
    static Object parse(ConfigValueType type, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null value");
        }
        try {
            return switch (type) {
                case INT -> Integer.valueOf(raw.trim());
                case DECIMAL -> new BigDecimal(raw.trim());
                case BOOL -> parseBool(raw.trim());
                case TIME -> LocalTime.parse(raw.trim());
            };
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new IllegalArgumentException("cannot parse '" + raw + "' as " + type);
        }
    }

    /** Giá trị ADMIN gửi lên (identity-v1 #32): như {@link #parse} nhưng TIME chỉ {@code HH:mm}, DECIMAL không dạng mũ. */
    static Object parseRequest(ConfigValueType type, String raw) {
        String trimmed = raw == null ? null : raw.trim();
        if (type == ConfigValueType.TIME && (trimmed == null || !TIME_HH_MM.matcher(trimmed).matches())) {
            throw new IllegalArgumentException("cannot parse '" + raw + "' as TIME HH:mm");
        }
        if (type == ConfigValueType.DECIMAL && (trimmed == null || !PLAIN_DECIMAL.matcher(trimmed).matches())) {
            throw new IllegalArgumentException("cannot parse '" + raw + "' as plain DECIMAL");
        }
        return parse(type, raw);
    }

    /** Parse và kiểm min–max của dòng (null = không giới hạn). Message giữ nguyên dạng của ADR-0004 (log khởi động). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object parseInRange(ConfigRow row) {
        Object value = parse(row.valueType(), row.value());
        if (row.valueType() == ConfigValueType.BOOL) {
            return value;
        }
        Comparable comparable = (Comparable) value;
        if (row.minValue() != null && comparable.compareTo(parse(row.valueType(), row.minValue())) < 0) {
            throw new IllegalArgumentException("value " + row.value() + " < min " + row.minValue());
        }
        if (row.maxValue() != null && comparable.compareTo(parse(row.valueType(), row.maxValue())) > 0) {
            throw new IllegalArgumentException("value " + row.value() + " > max " + row.maxValue());
        }
        return value;
    }

    /** Dạng lưu vào {@code system_configs.value}: {@code "05"} → {@code "5"}, {@code "2.50"} → {@code "2.5"}. */
    static String canonical(ConfigValueType type, Object value) {
        return switch (type) {
            case INT, BOOL -> value.toString();
            case DECIMAL -> ((BigDecimal) value).stripTrailingZeros().toPlainString();
            case TIME -> value.toString();
        };
    }

    /** {@code value} có nằm trong [min, max] không (null = không giới hạn; BOOL không có khoảng). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean within(Object value, Object min, Object max) {
        if (!(value instanceof Comparable comparable)) {
            return true;
        }
        return (min == null || comparable.compareTo(min) >= 0) && (max == null || comparable.compareTo(max) <= 0);
    }

    /**
     * Message BR-QT-13 "hiển thị khoảng cho phép" (02 L56): {@code "Giá trị của otp.ttl_minutes phải là số nguyên từ 1
     * đến 15 (phút)"}; {@code reasons} (ràng buộc chéo đã thu hẹp khoảng) nối sau dấu hai chấm.
     * {@code BusinessRuleViolationException} tự thêm {@code " (BR-QT-13)"}.
     */
    static String describeAllowed(String key, ConfigValueType type, Object min, Object max, String unit,
            List<String> reasons) {
        StringBuilder text = new StringBuilder("Giá trị của ").append(key).append(" phải là ").append(switch (type) {
            case INT -> "số nguyên";
            case DECIMAL -> "số thập phân";
            case BOOL -> "true hoặc false";
            case TIME -> "giờ dạng HH:mm";
        });
        if (type != ConfigValueType.BOOL) {
            if (min != null && max != null) {
                text.append(" từ ").append(format(min)).append(" đến ").append(format(max));
            } else if (min != null) {
                text.append(" từ ").append(format(min)).append(" trở lên");
            } else if (max != null) {
                text.append(" không quá ").append(format(max));
            }
        }
        if ((type == ConfigValueType.INT || type == ConfigValueType.DECIMAL) && unit != null && !unit.isBlank()) {
            text.append(" (").append(unit).append(')');
        }
        if (!reasons.isEmpty()) {
            text.append(": ").append(String.join("; ", reasons));
        }
        return text.toString();
    }

    private static String format(Object bound) {
        return bound instanceof BigDecimal decimal ? decimal.toPlainString() : bound.toString();
    }

    private static Boolean parseBool(String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("cannot parse '" + raw + "' as BOOL");
    }
}
