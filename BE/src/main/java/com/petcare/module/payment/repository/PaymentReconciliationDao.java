package com.petcare.module.payment.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Đọc giao dịch và ghi kết quả đối soát ST14, không cập nhật giao dịch gốc. */
@Repository
public class PaymentReconciliationDao {
    private final NamedParameterJdbcTemplate jdbc;

    public PaymentReconciliationDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LocalTransaction(long id, String code, String gatewayTxnId, long amount, String status) {}

    public enum MismatchKind { MISSING_LOCAL, MISSING_GATEWAY, AMOUNT_DIFF, STATUS_DIFF }

    public record Mismatch(MismatchKind kind, Long transactionId, String gatewayRef, Long localAmount,
            Long gatewayAmount) {}

    /** Một transaction giành ngày đối soát qua UNIQUE(recon_date); rollback thì lượt khác có thể thử lại. */
    public Optional<Long> claimDate(LocalDate date, Instant now) {
        return jdbc.query("""
                INSERT INTO payment_reconciliations (recon_date, status, created_at, updated_at)
                VALUES (:date, 'RUNNING', :now, :now)
                ON CONFLICT (recon_date) DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource("date", date).addValue("now", Timestamp.from(now)),
                (rs, row) -> rs.getLong("id")).stream().findFirst();
    }

    public List<LocalTransaction> findTransactions(String gateway, Instant start, Instant end) {
        return jdbc.query("""
                SELECT id, code, gateway_txn_id, amount, status FROM payment_transactions
                WHERE gateway = :gateway AND created_at >= :start AND created_at < :end
                ORDER BY id
                """, new MapSqlParameterSource("gateway", gateway).addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end)),
                (rs, row) -> new LocalTransaction(rs.getLong("id"), rs.getString("code"),
                        rs.getString("gateway_txn_id"), rs.getLong("amount"), rs.getString("status")));
    }

    public void saveMismatches(long reconciliationId, List<Mismatch> mismatches, Instant finishedAt) {
        if (mismatches.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = mismatches.stream().map(item -> new MapSqlParameterSource()
                .addValue("reconciliation", reconciliationId).addValue("kind", item.kind().name())
                .addValue("transaction", item.transactionId()).addValue("gatewayRef", item.gatewayRef())
                .addValue("localAmount", item.localAmount()).addValue("gatewayAmount", item.gatewayAmount())
                .addValue("now", Timestamp.from(finishedAt))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_items (reconciliation_id, kind, transaction_id, gateway_ref,
                                                  our_amount, gateway_amount, created_at, updated_at)
                VALUES (:reconciliation, :kind, :transaction, :gatewayRef, :localAmount, :gatewayAmount, :now, :now)
                """, rows);
    }

    public void finish(long reconciliationId, int txnCount, int mismatchCount, Instant finishedAt) {
        jdbc.update("""
                UPDATE payment_reconciliations
                SET status = :status, txn_count = :txnCount, mismatch_count = :mismatchCount,
                    finished_at = :now, updated_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource("id", reconciliationId)
                .addValue("status", mismatchCount == 0 ? "MATCHED" : "MISMATCHED")
                .addValue("txnCount", txnCount).addValue("mismatchCount", mismatchCount)
                .addValue("now", Timestamp.from(finishedAt)));
    }
}
