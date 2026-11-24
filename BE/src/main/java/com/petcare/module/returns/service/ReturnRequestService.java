package com.petcare.module.returns.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.returns.dto.CreateReturnRequest;
import com.petcare.module.returns.dto.ReturnRequestResponse;
import com.petcare.module.returns.repository.ReturnRequestDao;
import com.petcare.module.returns.repository.ReturnRequestDao.NewLine;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.OrderQueryApi.ReturnOrderLine;
import com.petcare.module.sales.api.OrderQueryApi.ReturnOrderSnapshot;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.shop.api.OnlineOrderQueryApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReturnRequestService {

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT)
            .withZone(TimeConfig.BUSINESS_ZONE);

    private final BranchScope scope;
    private final OrderQueryApi orders;
    private final OnlineOrderQueryApi onlineOrders;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi config;
    private final ReturnRequestDao requests;
    private final StaffDirectoryApi staff;
    private final BranchQueryApi branches;
    private final NotificationApi notifications;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnRequestResponse create(CreateReturnRequest input) {
        long branchId = orders.findBranchForReturn(input.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", input.orderId()));
        scope.check(branchId);
        long actorId = scope.current().accountId();
        if (staff.findStaffForShare(actorId).filter(actor -> actor.active() && actor.role() == Role.RECEPTIONIST
                && Long.valueOf(branchId).equals(actor.branchId())).isEmpty()) {
            throw new AccessDeniedScopeException("branch:" + branchId, "account:" + actorId);
        }

        ReturnOrderSnapshot order = orders.lockForReturn(input.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", input.orderId()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        checkWindow(order, now);
        if (input.reasonCode() == null || input.lines() == null || input.lines().isEmpty()) {
            throw rule("Cần lý do và ít nhất một dòng trả hàng");
        }
        List<ReturnRequestResponse.Line> lines = validateLines(order, input.lines());
        long id = requests.nextId();
        String code = "TH-" + CODE_DATE.format(now) + "-" + String.format(Locale.ROOT, "%04d", id);
        String note = input.reasonNote() == null ? null : input.reasonNote().strip();
        requests.insert(id, code, order.id(), branchId, actorId, input.reasonCode().name(), note, now);
        requests.insertLines(id, lines.stream().map(line -> new NewLine(line.orderLineId(), line.quantity())).toList(),
                now);
        notifyManagers(order, code);
        return new ReturnRequestResponse(id, code, order.id(), branchId, "PENDING", input.reasonCode(), note, lines,
                null, null, null, now, null);
    }

    private void checkWindow(ReturnOrderSnapshot order, Instant now) {
        if (order.paidAt() == null) {
            throw rule("Order chưa thanh toán");
        }
        Instant start = order.source() == OrderSource.ONLINE
                ? onlineOrders.findDeliveredAtByOrderId(order.id()).orElseThrow(() -> rule("Đơn online chưa giao"))
                : order.paidAt();
        if (now.isAfter(start.plus(config.getInt(ConfigKey.RETURNS_WINDOW_DAYS), ChronoUnit.DAYS))) {
            throw rule("Đã quá hạn trả hàng");
        }
    }

    private List<ReturnRequestResponse.Line> validateLines(ReturnOrderSnapshot order, List<CreateReturnRequest.Line> inputs) {
        Map<Long, ReturnOrderLine> lines = order.lines().stream()
                .collect(Collectors.toMap(ReturnOrderLine::id, Function.identity()));
        Map<Long, Long> claimed = requests.claimedQuantities(order.id());
        Set<Long> seen = new HashSet<>();
        List<ReturnRequestResponse.Line> response = new ArrayList<>(inputs.size());
        for (CreateReturnRequest.Line input : inputs) {
            if (!seen.add(input.orderLineId())) {
                throw rule("Dòng trả hàng bị trùng");
            }
            ReturnOrderLine line = lines.get(input.orderLineId());
            if (line == null) {
                throw new ResourceNotFoundException("dòng Order", input.orderLineId());
            }
            if (!line.goods() || catalog.findProduct(line.productId())
                    .filter(product -> product.type() == ProductType.GOODS && !product.prescription()).isEmpty()) {
                throw rule("Chỉ được trả hàng hóa không kê đơn");
            }
            if (input.quantity() > line.quantity() - claimed.getOrDefault(line.id(), 0L)) {
                throw rule("Số lượng vượt quá phần còn được trả");
            }
            response.add(new ReturnRequestResponse.Line(line.id(), line.description(), line.unitPrice(),
                    input.quantity()));
        }
        return List.copyOf(response);
    }

    private void notifyManagers(ReturnOrderSnapshot order, String code) {
        List<Long> recipients = staff.findActiveStaffIds(order.branchId(), Role.BRANCH_MANAGER);
        if (recipients.isEmpty()) {
            return;
        }
        String branchName = branches.findBranch(order.branchId()).orElseThrow().name();
        Map<String, Object> payload = Map.of("ma_tra_hang", code, "ma_don", order.code(), "chi_nhanh", branchName);
        for (Long accountId : recipients) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.RETURN_REQUESTED_APP, Channel.IN_APP,
                    accountId, null, payload, null));
        }
    }

    private static BusinessRuleViolationException rule(String message) {
        return new BusinessRuleViolationException("BR-BH-13", message);
    }
}
