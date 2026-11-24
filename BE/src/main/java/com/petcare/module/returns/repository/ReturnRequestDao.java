package com.petcare.module.returns.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy cập yêu cầu trả hàng V46 trong transaction của caller. */
@Repository
public class ReturnRequestDao {

    private final NamedParameterJdbcTemplate jdbc;

    public ReturnRequestDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsOpen(long orderId) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM return_requests WHERE order_id = :order AND status IN ('PENDING', 'APPROVED'))
                """, new MapSqlParameterSource("order", orderId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    public Map<Long, Long> claimedQuantities(long orderId) {
        return jdbc.query("""
                SELECT line.order_line_id, SUM(line.quantity) AS quantity
                FROM return_requests request
                JOIN return_request_lines line ON line.request_id = request.id
                WHERE request.order_id = :order AND request.status IN ('PENDING', 'APPROVED', 'REFUNDED')
                GROUP BY line.order_line_id
                """, new MapSqlParameterSource("order", orderId), rows -> {
            Map<Long, Long> quantities = new HashMap<>();
            while (rows.next()) {
                quantities.put(rows.getLong("order_line_id"), rows.getLong("quantity"));
            }
            return quantities;
        });
    }

    public long nextId() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT nextval(pg_get_serial_sequence('return_requests', 'id'))", Long.class);
    }

    public void insert(long id, String code, long orderId, long branchId, long actorId, String reasonCode,
            String reasonNote, Instant now) {
        jdbc.update("""
                INSERT INTO return_requests (id, code, order_id, branch_id, status, reason_code, reason_note,
                                             created_by, created_at, updated_at)
                OVERRIDING SYSTEM VALUE
                VALUES (:id, :code, :order, :branch, 'PENDING', :reason, :note, :actor, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("code", code).addValue("order", orderId)
                .addValue("branch", branchId).addValue("reason", reasonCode).addValue("note", reasonNote)
                .addValue("actor", actorId).addValue("now", Timestamp.from(now)));
    }

    public record NewLine(long orderLineId, int quantity) {}

    public void insertLines(long requestId, List<NewLine> lines, Instant now) {
        MapSqlParameterSource[] parameters = lines.stream().map(line -> new MapSqlParameterSource("request", requestId)
                .addValue("line", line.orderLineId()).addValue("quantity", line.quantity())
                .addValue("now", Timestamp.from(now))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO return_request_lines (request_id, order_line_id, quantity, created_at, updated_at)
                VALUES (:request, :line, :quantity, :now, :now)
                """, parameters);
    }
}
