package com.petcare.module.fees.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.fees.api.ShippingFeeApi;
import com.petcare.module.fees.api.ShippingFeeQuote;
import com.petcare.module.fees.dto.SaveShippingFeeConfigRequest;
import com.petcare.module.fees.dto.ShippingFeeConfigResponse;
import com.petcare.module.fees.entity.ShippingFeeConfig;
import com.petcare.module.fees.mapper.ShippingFeeConfigMapper;
import com.petcare.module.fees.repository.ShippingFeeConfigRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * Phí vận chuyển toàn chuỗi: cấu hình (UC32, BR-SP-08, 11) và ST09 (docs/adr/0140). PUT là một use case một transaction:
 * kiểm giá trị → khóa dòng 1 → đọc dưới khóa → `now` từ `Clock` → UPDATE tường minh (luôn ghi dòng) → audit. Bảng chỉ có một dòng nên không có khóa nào khác.
 */
@Service
public class ShippingFeeService implements ShippingFeeApi {

    static final String ENTITY_TYPE = "shipping_fee_configs";
    static final String ACTION_UPDATED = "SHIPPING_FEE_CONFIG_UPDATED";

    private final ShippingFeeConfigRepository configs;
    private final ShippingFeeConfigMapper mapper;
    private final AuditRecorder auditRecorder;
    private final BranchScope scope;
    private final Clock clock;

    public ShippingFeeService(ShippingFeeConfigRepository configs, ShippingFeeConfigMapper mapper,
            AuditRecorder auditRecorder, BranchScope scope, Clock clock) {
        this.configs = configs;
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
        this.scope = scope;
        this.clock = clock;
    }

    /** fees-v1 #1. */
    @Transactional(readOnly = true)
    public ShippingFeeConfigResponse get() {
        return mapper.toResponse(current());
    }

    /** fees-v1 #2: ghi đè cả hai giá trị; ghi cả khi giống hệt (vẫn để lại vết {@code updated_by} và audit). */
    @Transactional
    public ShippingFeeConfigResponse save(SaveShippingFeeConfigRequest request) {
        long baseFee = request.baseFee();
        long freeThreshold = request.freeThreshold();
        if (baseFee < 0) {
            throw new BusinessRuleViolationException("BR-SP-08", "Phí cơ bản không được âm (nhận " + baseFee + ")");
        }
        if (freeThreshold < 0) {
            throw new BusinessRuleViolationException("BR-SP-08",
                    "Ngưỡng miễn phí không được âm (nhận " + freeThreshold + ")");
        }
        ShippingFeeConfig config = configs.findForUpdate().orElseThrow(ShippingFeeService::missing);
        Map<String, Object> before = snapshot(config);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        configs.replace(baseFee, freeThreshold, scope.current().accountId(), now);
        auditRecorder.record(AuditEntry.of(ACTION_UPDATED).entity(ENTITY_TYPE, ShippingFeeConfig.SINGLE_ROW_ID)
                .before(before).after(snapshot(baseFee, freeThreshold)));
        return new ShippingFeeConfigResponse(baseFee, freeThreshold, now);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public ShippingFeeQuote quote(long subtotal) {
        if (subtotal < 0) {
            throw new IllegalArgumentException("subtotal must be >= 0, got " + subtotal);
        }
        ShippingFeeConfig config = current();
        boolean free = config.getFreeThreshold() > 0 && subtotal >= config.getFreeThreshold();
        return new ShippingFeeQuote(free ? 0 : config.getBaseFee(), config.getBaseFee(), config.getFreeThreshold());
    }

    private ShippingFeeConfig current() {
        return configs.findById(ShippingFeeConfig.SINGLE_ROW_ID).orElseThrow(ShippingFeeService::missing);
    }

    private static IllegalStateException missing() {
        return new IllegalStateException("shipping_fee_configs id=1 missing (V43 seed)");
    }

    /** Ảnh chụp để audit: luôn là Map (docs/adr/0001). */
    private static Map<String, Object> snapshot(ShippingFeeConfig config) {
        return snapshot(config.getBaseFee(), config.getFreeThreshold());
    }

    private static Map<String, Object> snapshot(long baseFee, long freeThreshold) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("baseFee", baseFee);
        snapshot.put("freeThreshold", freeThreshold);
        return snapshot;
    }
}
