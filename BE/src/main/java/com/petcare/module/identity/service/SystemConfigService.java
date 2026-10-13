package com.petcare.module.identity.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.SystemConfig;
import com.petcare.module.identity.repository.SystemConfigRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache trong bộ nhớ của {@code system_configs} (docs/adr/0004). Nạp toàn bộ lúc khởi động và kiểm tra fail-fast:
 * DB phải có đủ mọi {@link ConfigKey}, đúng {@code value_type}, giá trị parse được, nằm trong min–max (BR-QT-13) và
 * thỏa ràng buộc chéo ({@link ConfigConstraints}, docs/adr/0036); sai thì app dừng, log nêu từng key lỗi. Map bất biến
 * được thay nguyên khối ({@code volatile}) nên luồng đọc không bao giờ thấy trạng thái nửa cũ nửa mới và không bị chặn.
 * Sửa tham số (UC10) gọi {@link #reload()} sau khi commit, từ facade {@code SystemConfigAdminService} (docs/adr/0036
 * mục 2). Cache chỉ có đúng một entry mỗi {@link ConfigKey}: dòng DB lạ bị bỏ qua, nên kích thước cố định.
 */
@Slf4j
@Service
public class SystemConfigService implements SystemConfigApi, InitializingBean {

    /** Một dòng {@code system_configs} ở dạng thô, tách khỏi entity để test việc nạp. */
    record ConfigRow(String key, String value, ConfigValueType valueType, String minValue, String maxValue) {

        static ConfigRow of(SystemConfig entity) {
            return new ConfigRow(entity.getConfigKey(), entity.getValue(), entity.getValueType(),
                    entity.getMinValue(), entity.getMaxValue());
        }
    }

    private final SystemConfigRepository repository;
    private volatile Map<ConfigKey, Object> values = Map.of();

    public SystemConfigService(SystemConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public void afterPropertiesSet() {
        reload();
    }

    /**
     * Nạp lại từ DB. Dữ liệu không hợp lệ thì giữ nguyên bản đang dùng và ném {@link IllegalStateException}.
     * {@code synchronized} gói cả bước đọc lẫn bước gán: hai lần nạp chồng nhau (hai ADMIN sửa hai khóa khác nhau) không
     * thể để lần đọc trước gán đè kết quả của lần đọc sau (docs/adr/0036 mục 2). Luồng đọc giá trị không lấy monitor này.
     */
    public synchronized void reload() {
        values = load(repository.findAll().stream().map(ConfigRow::of).toList());
        log.info("Loaded {} system configs", values.size());
    }

    static Map<ConfigKey, Object> load(List<ConfigRow> rows) {
        Map<String, ConfigRow> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.put(row.key(), row));
        Map<ConfigKey, Object> loaded = new EnumMap<>(ConfigKey.class);
        List<String> errors = new ArrayList<>();
        for (ConfigKey key : ConfigKey.values()) {
            ConfigRow row = byKey.remove(key.key());
            if (row == null) {
                errors.add(key.key() + ": missing");
            } else if (row.valueType() != key.type()) {
                errors.add(key.key() + ": value_type " + row.valueType() + ", expected " + key.type());
            } else {
                try {
                    loaded.put(key, parseInRange(row));
                } catch (IllegalArgumentException ex) {
                    errors.add(key.key() + ": " + ex.getMessage());
                }
            }
        }
        if (!byKey.isEmpty()) {
            log.warn("system_configs has keys unknown to ConfigKey, ignored: {}", byKey.keySet());
        }
        if (errors.isEmpty()) {
            errors.addAll(ConfigConstraints.violations(loaded));
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid system_configs: " + String.join("; ", errors));
        }
        return Map.copyOf(loaded);
    }

    /** Giữ cho {@code SystemConfigServiceTest}; cài ở {@link ConfigValues#parseInRange}. */
    static Object parseInRange(ConfigRow row) {
        return ConfigValues.parseInRange(row);
    }

    @Override
    public int getInt(ConfigKey key) {
        return (Integer) get(key, ConfigValueType.INT);
    }

    @Override
    public BigDecimal getDecimal(ConfigKey key) {
        return (BigDecimal) get(key, ConfigValueType.DECIMAL);
    }

    @Override
    public boolean getBool(ConfigKey key) {
        return (Boolean) get(key, ConfigValueType.BOOL);
    }

    @Override
    public LocalTime getTime(ConfigKey key) {
        return (LocalTime) get(key, ConfigValueType.TIME);
    }

    private Object get(ConfigKey key, ConfigValueType expected) {
        if (key.type() != expected) {
            throw new IllegalArgumentException(key + " is " + key.type() + ", not " + expected);
        }
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("System config not loaded: " + key.key());
        }
        return value;
    }
}
