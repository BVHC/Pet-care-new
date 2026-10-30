package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.inventory.api.StockReportApi.ExpiringLotRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ST08 — báo quản lý một chi nhánh tồn dưới ngưỡng, lô sắp hết hạn và lô đã hết hạn còn tồn (BR-KO-07; docs/adr/0087).
 * Dùng chung {@link StockReportApi} với báo cáo (5) nên số liệu khớp. Một thông báo trong app mỗi người nhận; không đánh
 * dấu đã báo nên chạy lại trong ngày thì báo lại (nợ ghi ở ADR). Mỗi chi nhánh một {@code @Transactional}; thuần đọc rồi
 * outbox, không khóa dòng nào.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlertDigestService {

    private final StockReportApi stock;
    private final BranchQueryApi branches;
    private final StockAlertRecipients recipients;
    private final NotificationApi notifications;
    private final SystemConfigApi configs;
    private final Clock clock;

    /** Chi nhánh {@code ACTIVE}; đọc thuần. */
    @Transactional(readOnly = true)
    public List<BranchSummary> findActiveBranches() {
        return branches.listActiveBranches();
    }

    /**
     * {@code true} nếu đã đưa ít nhất một thông báo vào outbox; chi nhánh không có cảnh báo hoặc không có người nhận →
     * {@code false}.
     */
    @Transactional
    public boolean notifyBranch(Long branchId, String branchName) {
        int days = configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
        int lowStock = stock.lowStock(branchId).size();
        List<ExpiringLotRow> lots = stock.expiringLots(branchId, days);
        LocalDate today = LocalDate.now(clock);
        long expired = lots.stream().filter(lot -> lot.expiryDate().isBefore(today)).count();
        long expiring = lots.size() - expired;
        if (lowStock == 0 && lots.isEmpty()) {
            return false;
        }
        List<Long> targets = recipients.forBranch(branchId);
        if (targets.isEmpty()) {
            log.warn("STOCK_ALERT_DIGEST_NO_RECIPIENT branchId={} lowStock={} lots={}", branchId, lowStock,
                    lots.size());
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chi_nhanh", branchName);
        payload.put("so_duoi_nguong", String.valueOf(lowStock));
        payload.put("so_sap_het_han", String.valueOf(expiring));
        payload.put("so_ngay", String.valueOf(days));
        payload.put("so_da_het_han", String.valueOf(expired));
        for (Long target : targets) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.STOCK_ALERT_DIGEST_APP,
                    Channel.IN_APP, target, null, payload, "/admin/reports"));
        }
        log.info("STOCK_ALERT_DIGEST branchId={} lowStock={} expiring={} expired={} recipients={}", branchId, lowStock,
                expiring, expired, targets.size());
        return true;
    }
}
