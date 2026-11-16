package com.petcare.module.returns.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code return_requests} (V46): task 126 chỉ cần biết Order có yêu cầu đang mở hay không; thực thể đầy đủ thuộc task trả
 * hàng (137). Không tự mở transaction. Dùng {@code ix_return_requests_order}.
 */
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
}
