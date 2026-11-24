package com.petcare.module.returns.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.returns.dto.RefundResponse;
import com.petcare.module.sales.api.CollectMethod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RefundDao {

    private final NamedParameterJdbcTemplate jdbc;

    public record RefundScope(long branchId, CollectMethod method) {}

    public Optional<RefundScope> findScope(long refundId) {
        return jdbc.query("""
                SELECT branch_id, method FROM refunds WHERE id = :id
                """, new MapSqlParameterSource("id", refundId), (row, index) ->
                new RefundScope(row.getLong("branch_id"), CollectMethod.valueOf(row.getString("method"))))
                .stream().findFirst();
    }

    public Optional<RefundResponse> lock(long refundId) {
        return jdbc.query("""
                SELECT id, code, return_request_id, online_order_id, method, amount, status, attempts,
                       failure_reason, completed_at, created_at
                FROM refunds WHERE id = :id FOR NO KEY UPDATE
                """, new MapSqlParameterSource("id", refundId), (row, index) -> {
            Timestamp completed = row.getTimestamp("completed_at");
            return new RefundResponse(row.getLong("id"), row.getString("code"),
                    row.getObject("return_request_id", Long.class), row.getObject("online_order_id", Long.class),
                    CollectMethod.valueOf(row.getString("method")), row.getLong("amount"), row.getString("status"),
                    row.getInt("attempts"), row.getString("failure_reason"),
                    completed == null ? null : completed.toInstant(), row.getTimestamp("created_at").toInstant());
        }).stream().findFirst();
    }

    public void confirm(long refundId, long actorId, Long shiftId, Instant now) {
        jdbc.update("""
                UPDATE refunds SET status = 'SUCCEEDED', confirmed_by = :actor, cashier_shift_id = :shift,
                    completed_at = :now, updated_at = :now, next_attempt_at = NULL
                WHERE id = :id
                """, new MapSqlParameterSource("id", refundId).addValue("actor", actorId)
                .addValue("shift", shiftId, Types.BIGINT).addValue("now", Timestamp.from(now)));
    }

    public boolean completeReturn(long requestId, Instant now) {
        return jdbc.update("""
                UPDATE return_requests SET status = 'REFUNDED', updated_at = :now
                WHERE id = :id AND status = 'APPROVED'
                """, new MapSqlParameterSource("id", requestId).addValue("now", Timestamp.from(now))) == 1;
    }
}
