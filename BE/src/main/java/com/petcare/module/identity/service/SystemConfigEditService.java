package com.petcare.module.identity.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.module.identity.entity.SystemConfig;
import com.petcare.module.identity.mapper.SystemConfigAdminMapper;
import com.petcare.module.identity.repository.SystemConfigRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC10 — xem, sửa tham số [CFG] (identity-v1 #31, #32; BR-QT-13, 15; docs/adr/0036). Chỉ được gọi từ facade
 * {@link SystemConfigAdminService}, vì cache {@code SystemConfigService} chỉ được nạp lại <b>sau</b> khi transaction ở
 * đây đã commit.
 *
 * <p>{@link #updateConfig}: một transaction, không {@code noRollbackFor}, không {@code catch}. Thứ tự:
 * <ol>
 *   <li>key không thuộc {@link ConfigKey} → 404, chưa chạm DB;</li>
 *   <li>khóa ({@code FOR NO KEY UPDATE}) dòng của key cùng các khóa chung ràng buộc chéo, theo key tăng dần — trước mọi
 *       lần đọc khác, để không nhận entity cũ của persistence context (docs/adr/0029 mục 4);</li>
 *   <li>kiểu, min–max của dòng đã khóa, ràng buộc chéo với giá trị khóa kia đọc dưới khóa → 400 BR-QT-13 kèm khoảng cho
 *       phép thực tế;</li>
 *   <li>trùng giá trị hiện tại (sau chuẩn hóa) → trả luôn, không ghi, không audit;</li>
 *   <li>ghi → {@code flush()} (UPDATE chạy trong transaction) → audit {@code SYSTEM_CONFIG_UPDATED} → map response.</li>
 * </ol>
 */
@Service
public class SystemConfigEditService {

    static final String NOT_FOUND_TYPE = "tham số";
    static final String RULE = "BR-QT-13";

    private static final Map<String, ConfigKey> KEYS = Arrays.stream(ConfigKey.values())
            .collect(Collectors.toUnmodifiableMap(ConfigKey::key, Function.identity()));

    private final SystemConfigRepository configs;
    private final SystemConfigAdminMapper mapper;
    private final AuditRecorder audit;
    private final BranchScope branchScope;

    public SystemConfigEditService(SystemConfigRepository configs, SystemConfigAdminMapper mapper, AuditRecorder audit,
            BranchScope branchScope) {
        this.configs = configs;
        this.mapper = mapper;
        this.audit = audit;
        this.branchScope = branchScope;
    }

    /** identity-v1 #31: mọi dòng, sắp theo key. */
    @Transactional(readOnly = true)
    public List<SystemConfigResponse> listConfigs() {
        return configs.findAll(Sort.by("configKey")).stream().map(mapper::toResponse).toList();
    }

    /** identity-v1 #32. {@code rawValue} đã qua Bean Validation (không rỗng, ≤ 255 ký tự). */
    @Transactional
    public SystemConfigResponse updateConfig(String key, String rawValue) {
        ConfigKey configKey = KEYS.get(key);
        if (configKey == null) {
            throw new ResourceNotFoundException(NOT_FOUND_TYPE, key);
        }
        Map<ConfigKey, SystemConfig> locked = lockWithPartners(configKey);
        SystemConfig target = locked.get(configKey);
        ConfigValueType type = configKey.type();
        Object value = validate(configKey, target, rawValue, locked);

        String canonical = ConfigValues.canonical(type, value);
        if (canonical.equals(ConfigValues.canonical(type, ConfigValues.parse(type, target.getValue())))) {
            return mapper.toResponse(target);
        }
        String before = target.getValue();
        target.changeValue(canonical, branchScope.current().accountId());
        configs.flush();
        audit.record(AuditEntry.of(QtAuditActions.SYSTEM_CONFIG_UPDATED)
                .entity("system_configs", null)
                .before(snapshot(key, before))
                .after(snapshot(key, canonical)));
        return mapper.toResponse(target);
    }

    /** Khóa theo {@code key} tăng dần (thứ tự toàn cục duy nhất trên bảng này, docs/adr/0036 mục 4). */
    private Map<ConfigKey, SystemConfig> lockWithPartners(ConfigKey configKey) {
        TreeMap<String, ConfigKey> ordered = new TreeMap<>();
        ordered.put(configKey.key(), configKey);
        ConfigConstraints.partnersOf(configKey).forEach(partner -> ordered.put(partner.key(), partner));
        Map<ConfigKey, SystemConfig> locked = new LinkedHashMap<>();
        for (ConfigKey key : ordered.values()) {
            SystemConfig row = configs.findByKeyForUpdate(key.key()).orElse(null);
            if (row == null) {
                if (key == configKey) {
                    throw new ResourceNotFoundException(NOT_FOUND_TYPE, configKey.key());
                }
                // App không khởi động được khi thiếu dòng (SystemConfigService.load); tới đây là dữ liệu bị sửa tay.
                throw new IllegalStateException("system_configs row missing: " + key.key());
            }
            locked.put(key, row);
        }
        return locked;
    }

    private static Object validate(ConfigKey key, SystemConfig row, String rawValue,
            Map<ConfigKey, SystemConfig> locked) {
        ConfigValueType type = key.type();
        Object min = row.getMinValue() == null ? null : ConfigValues.parse(type, row.getMinValue());
        Object max = row.getMaxValue() == null ? null : ConfigValues.parse(type, row.getMaxValue());
        List<String> reasons = List.of();
        if (!ConfigConstraints.partnersOf(key).isEmpty()) {
            ConfigConstraints.Narrowed narrowed = ConfigConstraints.narrow(key, (Integer) min, (Integer) max,
                    partner -> (Integer) ConfigValues.parse(ConfigValueType.INT, locked.get(partner).getValue()));
            min = narrowed.min();
            max = narrowed.max();
            reasons = narrowed.reasons();
        }
        Object value;
        try {
            value = ConfigValues.parseRequest(type, rawValue);
        } catch (IllegalArgumentException ex) {
            value = null;
        }
        if (value == null || !ConfigValues.within(value, min, max)) {
            throw new BusinessRuleViolationException(RULE,
                    ConfigValues.describeAllowed(key.key(), type, min, max, row.getUnit(), reasons));
        }
        return value;
    }

    private static Map<String, Object> snapshot(String key, String value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", key);
        snapshot.put("value", value);
        return snapshot;
    }
}
