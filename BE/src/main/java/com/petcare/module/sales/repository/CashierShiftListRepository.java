package com.petcare.module.sales.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.sales.entity.CashierShiftStatus;

/**
 * Đường đọc danh sách ca thu ngân (sales-v1 #19; docs/adr/0064). Chỉ ghép điều kiện đã gửi (không {@code :x IS NULL
 * OR …}, như ADR-0038); luôn lọc theo chi nhánh và sắp {@code business_date DESC, id DESC} nên dùng
 * {@code ix_cashier_shifts_branch_date}. Trả id của trang; entity nạp bằng {@code findAllById} ở service.
 */
@Repository
public class CashierShiftListRepository {

    /** Điều kiện lọc; trường null = không gửi. */
    public record Filter(long branchId, CashierShiftStatus status, LocalDate businessDate, Long cashierId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CashierShiftListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + from(filter, params), params, Long.class);
        return total == null ? 0 : total;
    }

    public List<Long> findPageIds(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT s.id " + from(filter, params)
                + " ORDER BY s.business_date DESC, s.id DESC LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.queryForList(sql, params, Long.class);
    }

    static String from(Filter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("FROM cashier_shifts s WHERE s.branch_id = :branchId");
        params.addValue("branchId", filter.branchId());
        if (filter.status() != null) {
            sql.append(" AND s.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.businessDate() != null) {
            sql.append(" AND s.business_date = :businessDate");
            params.addValue("businessDate", filter.businessDate());
        }
        if (filter.cashierId() != null) {
            sql.append(" AND s.cashier_id = :cashierId");
            params.addValue("cashierId", filter.cashierId());
        }
        return sql.toString();
    }
}
