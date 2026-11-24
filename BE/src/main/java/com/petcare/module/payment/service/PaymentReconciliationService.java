package com.petcare.module.payment.service;

import static com.petcare.platform.config.TimeConfig.BUSINESS_ZONE;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayTransaction;
import com.petcare.module.payment.api.PaymentGatewayException;
import com.petcare.module.payment.dto.ReconciliationItem;
import com.petcare.module.payment.dto.ReconciliationSummary;
import com.petcare.module.payment.dto.ReconciliationSummary.ReconciliationStatus;
import com.petcare.module.payment.gateway.PaymentProperties;
import com.petcare.module.payment.repository.PaymentReconciliationDao;
import com.petcare.module.payment.repository.PaymentReconciliationDao.LocalTransaction;
import com.petcare.module.payment.repository.PaymentReconciliationDao.Mismatch;
import com.petcare.module.payment.repository.PaymentReconciliationDao.MismatchKind;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;

/** BR-TG-10: so khớp báo cáo và dữ liệu nội bộ theo mã tham chiếu, chỉ ghi kết quả đối soát. */
@Service
public class PaymentReconciliationService {
    private final PaymentReconciliationDao reconciliations;
    private final PaymentGatewayClient gateway;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentReconciliationService(PaymentReconciliationDao reconciliations, PaymentGatewayClient gateway,
            PaymentProperties properties, Clock clock) {
        this.reconciliations = reconciliations;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    /** Ghi một phiên và các mục lệch trong cùng transaction. Lỗi lấy báo cáo không ghi phiên hay mục lệch. */
    @Transactional
    public boolean reconcile(LocalDate date) {
        Optional<Long> run = reconciliations.claimDate(date, Instant.now(clock));
        if (run.isEmpty()) {
            return false;
        }

        Map<String, GatewayTransaction> report = indexReport(gateway.fetchSettlementReport(date));
        Instant start = date.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<LocalTransaction> local = reconciliations.findTransactions(properties.gateway(), start, end);
        List<Mismatch> mismatches = new ArrayList<>();
        for (LocalTransaction transaction : local) {
            GatewayTransaction reported = report.remove(transaction.code());
            if (reported == null) {
                String reference = transaction.gatewayTxnId() == null ? transaction.code()
                        : transaction.gatewayTxnId();
                mismatches.add(new Mismatch(MismatchKind.MISSING_GATEWAY, transaction.id(), reference,
                        transaction.amount(), null));
            } else if (transaction.amount() != reported.amount()) {
                mismatches.add(new Mismatch(MismatchKind.AMOUNT_DIFF, transaction.id(), gatewayRef(reported),
                        transaction.amount(), reported.amount()));
            } else if (!transaction.status().equals(reported.status().name())) {
                mismatches.add(new Mismatch(MismatchKind.STATUS_DIFF, transaction.id(), gatewayRef(reported),
                        transaction.amount(), reported.amount()));
            }
        }
        for (GatewayTransaction reported : report.values()) {
            mismatches.add(new Mismatch(MismatchKind.MISSING_LOCAL, null, gatewayRef(reported), null,
                    reported.amount()));
        }

        long id = run.get();
        Instant finishedAt = Instant.now(clock);
        reconciliations.saveMismatches(id, mismatches, finishedAt);
        reconciliations.finish(id, local.size() + report.size(), mismatches.size(), finishedAt);
        return true;
    }

    private static Map<String, GatewayTransaction> indexReport(List<GatewayTransaction> rows) {
        Map<String, GatewayTransaction> byCode = new LinkedHashMap<>();
        for (GatewayTransaction row : rows) {
            if (row == null || row.txnCode() == null || row.txnCode().isBlank() || row.amount() <= 0
                    || row.status() == null || byCode.putIfAbsent(row.txnCode(), row) != null) {
                throw new PaymentGatewayException("Báo cáo đối soát có giao dịch thiếu dữ liệu hoặc trùng mã");
            }
        }
        return byCode;
    }

    private static String gatewayRef(GatewayTransaction transaction) {
        return transaction.gatewayTxnId() == null || transaction.gatewayTxnId().isBlank() ? transaction.txnCode()
                : transaction.gatewayTxnId();
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationSummary> list(ReconciliationStatus status, int page, int size) {
        PageRequest pagination = PageRequest.of(page, size);
        long total = reconciliations.countRuns(status);
        return PageResponse.of(new PageImpl<>(reconciliations.listRuns(status, pagination), pagination, total));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationItem> listItems(long reconciliationId, Boolean resolved, int page, int size) {
        if (!reconciliations.runExists(reconciliationId)) {
            throw new ResourceNotFoundException("phiên đối soát", reconciliationId);
        }
        PageRequest pagination = PageRequest.of(page, size);
        long total = reconciliations.countItems(reconciliationId, resolved);
        return PageResponse.of(new PageImpl<>(reconciliations.listItems(reconciliationId, resolved, pagination),
                pagination, total));
    }

    @Transactional
    public ReconciliationItem resolve(long itemId, long actorId, String note) {
        return reconciliations.resolveItem(itemId, actorId, note.strip(), Instant.now(clock)).orElseThrow(() -> {
            if (!reconciliations.itemExists(itemId)) {
                return new ResourceNotFoundException("mục lệch đối soát", itemId);
            }
            return new InvalidStateTransitionException("mục lệch đối soát", "đã xử lý", "đã xử lý") {
                @Override
                public String clientMessage() {
                    return "Mục đã được xử lý";
                }
            };
        });
    }
}
