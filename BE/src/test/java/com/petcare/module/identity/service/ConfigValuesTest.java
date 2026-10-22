package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.petcare.module.identity.api.ConfigValueType;

/**
 * Bộ parse dùng chung của [CFG] (BR-QT-13; docs/adr/0036 mục 3): chuẩn hóa, dạng chặt ở API (TIME {@code HH:mm},
 * DECIMAL không dạng mũ), message "khoảng cho phép" tiếng Việt.
 */
class ConfigValuesTest {

    @ParameterizedTest
    @CsvSource({"5,5", "05,5", "+5,5", "' 5 ',5", "0,0", "-0,0"})
    void intIsCanonicalised(String raw, String canonical) {
        Object value = ConfigValues.parseRequest(ConfigValueType.INT, raw);

        assertThat(ConfigValues.canonical(ConfigValueType.INT, value)).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1e3", "abc", "5 phút", "99999999999", "", "  "})
    void invalidIntIsRejected(String raw) {
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.INT, raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decimalIsCanonicalisedAndExponentFormIsRejected() {
        assertThat(ConfigValues.canonical(ConfigValueType.DECIMAL,
                ConfigValues.parseRequest(ConfigValueType.DECIMAL, "2.50"))).isEqualTo("2.5");
        assertThat(ConfigValues.canonical(ConfigValueType.DECIMAL,
                ConfigValues.parseRequest(ConfigValueType.DECIMAL, "0.00"))).isEqualTo("0");
        // 1e999999999 chuẩn hóa ra chuỗi ~1 tỷ ký tự (OOM): API không nhận dạng mũ.
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.DECIMAL, "1e999999999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.DECIMAL, "1."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boolAcceptsOnlyLowerCaseTrueFalse() {
        assertThat(ConfigValues.parseRequest(ConfigValueType.BOOL, "true")).isEqualTo(Boolean.TRUE);
        assertThat(ConfigValues.parseRequest(ConfigValueType.BOOL, " false ")).isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.BOOL, "TRUE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.BOOL, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeAtApiIsHourMinuteOnly() {
        Object noon = ConfigValues.parseRequest(ConfigValueType.TIME, "12:00");

        assertThat(noon).isEqualTo(LocalTime.NOON);
        assertThat(ConfigValues.canonical(ConfigValueType.TIME, noon)).isEqualTo("12:00");
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.TIME, "12:00:30"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.TIME, "9:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValues.parseRequest(ConfigValueType.TIME, "24:00"))
                .isInstanceOf(IllegalArgumentException.class);
        // Lúc nạp vẫn nhận giây (dữ liệu sửa tay): API chặt hơn thì không bao giờ làm reload hỏng.
        assertThat(ConfigValues.parse(ConfigValueType.TIME, "12:00:30")).isEqualTo(LocalTime.of(12, 0, 30));
    }

    @Test
    void withinTreatsNullAsUnbounded() {
        assertThat(ConfigValues.within(5, 1, 15)).isTrue();
        assertThat(ConfigValues.within(1, 1, 15)).isTrue();
        assertThat(ConfigValues.within(15, 1, 15)).isTrue();
        assertThat(ConfigValues.within(0, 1, 15)).isFalse();
        assertThat(ConfigValues.within(16, 1, 15)).isFalse();
        assertThat(ConfigValues.within(999, null, null)).isTrue();
        assertThat(ConfigValues.within(Boolean.TRUE, null, null)).isTrue();
        assertThat(ConfigValues.within(new BigDecimal("2.5"), new BigDecimal("2.50"), null)).isTrue();
    }

    @Test
    void messageShowsAllowedRangeAndUnit() {
        assertThat(ConfigValues.describeAllowed("otp.ttl_minutes", ConfigValueType.INT, 1, 15, "phút", List.of()))
                .isEqualTo("Giá trị của otp.ttl_minutes phải là số nguyên từ 1 đến 15 (phút)");
        assertThat(ConfigValues.describeAllowed("boarding.checkout_time", ConfigValueType.TIME, LocalTime.of(6, 0),
                LocalTime.of(22, 0), "giờ", List.of()))
                .isEqualTo("Giá trị của boarding.checkout_time phải là giờ dạng HH:mm từ 06:00 đến 22:00");
        assertThat(ConfigValues.describeAllowed("x.flag", ConfigValueType.BOOL, null, null, null, List.of()))
                .isEqualTo("Giá trị của x.flag phải là true hoặc false");
        assertThat(ConfigValues.describeAllowed("x.rate", ConfigValueType.DECIMAL, new BigDecimal("0.5"), null, "%",
                List.of())).isEqualTo("Giá trị của x.rate phải là số thập phân từ 0.5 trở lên (%)");
        assertThat(ConfigValues.describeAllowed("x.count", ConfigValueType.INT, null, 9, null, List.of()))
                .isEqualTo("Giá trị của x.count phải là số nguyên không quá 9");
        assertThat(ConfigValues.describeAllowed("visit.late_priority_minutes", ConfigValueType.INT, 0, 29, "phút",
                List.of("phải nhỏ hơn appointment.no_show_after_minutes đang là 30")))
                .isEqualTo("Giá trị của visit.late_priority_minutes phải là số nguyên từ 0 đến 29 (phút): phải nhỏ "
                        + "hơn appointment.no_show_after_minutes đang là 30");
    }
}
