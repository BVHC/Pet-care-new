package com.petcare.module.sales.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.sales.repository.SalesOrderRepository.BranchOverdue;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ST13 — cuối ngày báo quản lý các Order {@code PENDING} quá hạn (BR-BH-05; docs/adr/0064). Một thông báo trong app mỗi
 * chi nhánh có Order quá hạn, tới mọi BRANCH_MANAGER đang hoạt động (không có ai: SUPER_MANAGER). Không lập lịch lại,
 * không đánh dấu đã báo: chạy lại tay thì báo lại (nợ ghi ở ADR). Mỗi chi nhánh một {@code @Transactional}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingOrderDigestService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesOrderRepository orders;
    private final BranchQueryApi branches;
    private final NotificationApi notifications;
    private final ShiftNoticeRecipients recipients;

    /** Chi nhánh có Order {@code PENDING} chốt trước {@code cutoff}, theo id chi nhánh; đọc thuần. */
    @Transactional(readOnly = true)
    public List<BranchOverdue> findOverdueBranches(Instant cutoff) {
        return orders.findOverdueByBranch(cutoff);
    }

    @Transactional
    public void notifyPendingOrders(Long branchId, long orderCount, Instant oldestPendingAt, int days) {
        List<Long> targets = recipients.forBranch(branchId);
        if (targets.isEmpty()) {
            log.warn("PENDING_ORDER_DIGEST_NO_RECIPIENT branchId={} orders={}", branchId, orderCount);
            return;
        }
        String branchName = branches.findBranch(branchId)
                .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + branchId + " không tồn tại")).name();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chi_nhanh", branchName);
        payload.put("so_order", String.valueOf(orderCount));
        payload.put("so_ngay", String.valueOf(days));
        payload.put("ngay_cu_nhat", oldestPendingAt.atZone(TimeConfig.BUSINESS_ZONE).format(DATE));
        for (Long target : targets) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.PENDING_ORDERS_DIGEST_APP,
                    Channel.IN_APP, target, null, payload, null));
        }
        log.info("PENDING_ORDER_DIGEST branchId={} orders={} recipients={}", branchId, orderCount, targets.size());
    }
}
