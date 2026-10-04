package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.entity.SystemConfig;
import com.petcare.module.identity.repository.SystemConfigRepository;
import com.petcare.module.identity.service.SystemConfigService.ConfigRow;

/** docs/adr/0004 D11–D13; BR-QT-13 (khoảng hợp lệ). Nạp fail-fast và getter đúng kiểu. */
class SystemConfigServiceTest {

    /** Một bộ dòng hợp lệ cho mọi ConfigKey: INT = 5 trong [1, 10], TIME = 12:00 trong [06:00, 22:00]. */
    private static List<ConfigRow> validRows() {
        List<ConfigRow> rows = new ArrayList<>();
        for (ConfigKey key : ConfigKey.values()) {
            rows.add(key.type() == ConfigValueType.TIME
                    ? new ConfigRow(key.key(), "12:00", ConfigValueType.TIME, "06:00", "22:00")
                    : new ConfigRow(key.key(), "5", key.type(), "1", "10"));
        }
        return rows;
    }

    private static List<ConfigRow> replace(ConfigKey key, ConfigRow row) {
        List<ConfigRow> rows = new ArrayList<>(validRows());
        rows.removeIf(r -> r.key().equals(key.key()));
        if (row != null) {
            rows.add(row);
        }
        return rows;
    }

    @Test
    void loadsEveryKeyWithItsType() {
        Map<ConfigKey, Object> values = SystemConfigService.load(validRows());

        assertThat(values).hasSize(ConfigKey.values().length);
        assertThat(values.get(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(5);
        assertThat(values.get(ConfigKey.BOARDING_CHECKOUT_TIME)).isEqualTo(LocalTime.NOON);
    }

    @Test
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp.ttl_minutes: missing");
    }

    @Test
    void wrongValueTypeFailsFast() {
        ConfigRow row = new ConfigRow("otp.ttl_minutes", "5", ConfigValueType.DECIMAL, null, null);

        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp.ttl_minutes: value_type DECIMAL, expected INT");
    }

    @Test
    void unparsableValueFailsFast() {
        ConfigRow row = new ConfigRow("otp.ttl_minutes", "five", ConfigValueType.INT, "1", "15");

        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp.ttl_minutes: cannot parse 'five' as INT");
    }

    @Test
    void unparsableTimeFailsFast() {
        ConfigRow row = new ConfigRow("boarding.checkout_time", "noon", ConfigValueType.TIME, null, null);

        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.BOARDING_CHECKOUT_TIME, row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boarding.checkout_time");
    }

    @Test
    void valueBelowMinFailsFast() {
        ConfigRow row = new ConfigRow("otp.ttl_minutes", "0", ConfigValueType.INT, "1", "15");

        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value 0 < min 1");
    }

    @Test
    void valueAboveMaxFailsFast() {
        ConfigRow row = new ConfigRow("otp.ttl_minutes", "16", ConfigValueType.INT, "1", "15");

        assertThatThrownBy(() -> SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value 16 > max 15");
    }

    @Test
    void boundaryValuesAreAccepted() {
        List<ConfigRow> rows = replace(ConfigKey.OTP_TTL_MINUTES,
                new ConfigRow("otp.ttl_minutes", "15", ConfigValueType.INT, "1", "15"));
        rows.removeIf(r -> r.key().equals(ConfigKey.BOARDING_CHECKOUT_TIME.key()));
        rows.add(new ConfigRow("boarding.checkout_time", "06:00", ConfigValueType.TIME, "06:00", "22:00"));

        Map<ConfigKey, Object> values = SystemConfigService.load(rows);

        assertThat(values.get(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(15);
        assertThat(values.get(ConfigKey.BOARDING_CHECKOUT_TIME)).isEqualTo(LocalTime.of(6, 0));
    }

    @Test
    void allErrorsAreReportedTogether() {
        List<ConfigRow> rows = replace(ConfigKey.OTP_TTL_MINUTES, null);
        rows.removeIf(r -> r.key().equals(ConfigKey.SESSION_TTL_HOURS.key()));

        assertThatThrownBy(() -> SystemConfigService.load(rows))
                .hasMessageContaining("otp.ttl_minutes: missing")
                .hasMessageContaining("session.ttl_hours: missing");
    }

    @Test
    void unknownKeyInDatabaseIsIgnored() {
        List<ConfigRow> rows = new ArrayList<>(validRows());
        rows.add(new ConfigRow("legacy.unused", "1", ConfigValueType.INT, null, null));

        assertThat(SystemConfigService.load(rows)).hasSize(ConfigKey.values().length);
    }

    @Test
    void missingMinMaxMeansNoBound() {
        ConfigRow row = new ConfigRow("otp.ttl_minutes", "999", ConfigValueType.INT, null, null);

        assertThat(SystemConfigService.load(replace(ConfigKey.OTP_TTL_MINUTES, row)).get(ConfigKey.OTP_TTL_MINUTES))
                .isEqualTo(999);
    }

    @Test
    void getterOfWrongTypeIsProgrammingError() {
        SystemConfigService service = serviceWith(validRows());

        assertThatThrownBy(() -> service.getTime(ConfigKey.SESSION_TTL_HOURS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getInt(ConfigKey.BOARDING_CHECKOUT_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getBool(ConfigKey.SESSION_TTL_HOURS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getDecimal(ConfigKey.SESSION_TTL_HOURS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gettersReturnLoadedValues() {
        SystemConfigService service = serviceWith(validRows());

        assertThat(service.getInt(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(5);
        assertThat(service.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)).isEqualTo(LocalTime.NOON);
    }

    @Test
    void reloadSwapsValuesAndKeepsOldOnInvalidData() {
        SystemConfigRepositoryStub repository = new SystemConfigRepositoryStub(validRows());
        SystemConfigService service = repository.service();
        assertThat(service.getInt(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(5);

        repository.rows = replace(ConfigKey.SESSION_TTL_HOURS,
                new ConfigRow("session.ttl_hours", "8", ConfigValueType.INT, "1", "72"));
        service.reload();
        assertThat(service.getInt(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(8);

        repository.rows = replace(ConfigKey.SESSION_TTL_HOURS, null);
        assertThatThrownBy(service::reload).isInstanceOf(IllegalStateException.class);
        assertThat(service.getInt(ConfigKey.SESSION_TTL_HOURS)).isEqualTo(8);
    }

    /** ConfigKey hiện chưa có khóa DECIMAL/BOOL; bộ parse của hai kiểu này kiểm tra trực tiếp. */
    @Test
    void parsesDecimalAndBoolWithRange() {
        assertThat(SystemConfigService.parseInRange(row(ConfigValueType.DECIMAL, "2.50", "0", "10")))
                .isEqualTo(new BigDecimal("2.50"));
        assertThat(SystemConfigService.parseInRange(row(ConfigValueType.BOOL, "true", null, null)))
                .isEqualTo(Boolean.TRUE);
        assertThat(SystemConfigService.parseInRange(row(ConfigValueType.BOOL, "false", null, null)))
                .isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> SystemConfigService.parseInRange(row(ConfigValueType.BOOL, "yes", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as BOOL");
        assertThatThrownBy(() -> SystemConfigService.parseInRange(row(ConfigValueType.DECIMAL, "11", "0", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> max");
    }

    private static ConfigRow row(ConfigValueType type, String value, String min, String max) {
        return new ConfigRow("x.y", value, type, min, max);
    }

    private static SystemConfigService serviceWith(List<ConfigRow> rows) {
        return new SystemConfigRepositoryStub(rows).service();
    }

    /** Repository giả trả entity dựng từ ConfigRow, để chạy đúng đường afterPropertiesSet / reload. */
    private static final class SystemConfigRepositoryStub {
        private List<ConfigRow> rows;
        private final SystemConfigRepository repository = mock(SystemConfigRepository.class);

        SystemConfigRepositoryStub(List<ConfigRow> rows) {
            this.rows = rows;
            when(repository.findAll()).thenAnswer(invocation -> this.rows.stream().map(this::entity).toList());
        }

        SystemConfigService service() {
            SystemConfigService service = new SystemConfigService(repository);
            service.afterPropertiesSet();
            return service;
        }

        private SystemConfig entity(ConfigRow row) {
            SystemConfig entity = BeanUtils.instantiateClass(SystemConfig.class);
            ReflectionTestUtils.setField(entity, "configKey", row.key());
            ReflectionTestUtils.setField(entity, "value", row.value());
            ReflectionTestUtils.setField(entity, "valueType", row.valueType());
            ReflectionTestUtils.setField(entity, "minValue", row.minValue());
            ReflectionTestUtils.setField(entity, "maxValue", row.maxValue());
            return entity;
        }
    }
}
