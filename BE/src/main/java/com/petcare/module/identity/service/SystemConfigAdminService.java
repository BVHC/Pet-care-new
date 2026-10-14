package com.petcare.module.identity.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.SystemConfigResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * UC10 — facade của xem / sửa tham số [CFG] (identity-v1 #31, #32; docs/adr/0036 mục 2). Cố ý <b>không</b>
 * {@code @Transactional} (convention 07 §7.1, khuôn {@code StaffAdminService}):
 * <ol>
 *   <li>{@link SystemConfigEditService#updateConfig}: một transaction (bean khác, đi qua proxy). Khi method đó trả về
 *       thì transaction đã commit.</li>
 *   <li>Rồi mới {@link SystemConfigService#reload()} — đọc DB bằng transaction readOnly riêng, sau khi connection của
 *       transaction ghi đã trả. Nhờ vậy khi client nhận 200, mọi giao dịch tạo sau đó đọc giá trị mới (BR-QT-13); khi
 *       transaction rollback thì không reload, cache giữ giá trị cũ. Reload chạy cả ở nhánh trùng giá trị, để lần bấm
 *       lại sau một lần reload lỗi tự sửa được cache.</li>
 * </ol>
 * Reload lỗi sau commit: log {@code SYSTEM_CONFIG_RELOAD_FAILED} rồi ném lại (500); DB đã có giá trị mới. Không được
 * gọi từ trong một transaction khác.
 */
@Slf4j
@Service
public class SystemConfigAdminService {

    private final SystemConfigEditService edits;
    private final SystemConfigService configs;

    public SystemConfigAdminService(SystemConfigEditService edits, SystemConfigService configs) {
        this.edits = edits;
        this.configs = configs;
    }

    public List<SystemConfigResponse> listConfigs() {
        return edits.listConfigs();
    }

    public SystemConfigResponse updateConfig(String key, String value) {
        SystemConfigResponse response = edits.updateConfig(key, value);
        try {
            configs.reload();
        } catch (RuntimeException ex) {
            log.error("SYSTEM_CONFIG_RELOAD_FAILED key={}", key, ex);
            throw ex;
        }
        return response;
    }
}
