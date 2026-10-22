package com.petcare.module.boarding.repository;

import java.sql.Date;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.boarding.dto.BookingFilter;

/**
 * Danh sách đặt chỗ (boarding-v1 #9): chỉ nối điều kiện được gửi (không dùng {@code :x IS NULL OR …} vì mất index),
 * sắp {@code check_in_date DESC, id DESC}; {@code missingCareLogToday = true} chỉ giữ thú đang lưu trú hôm nay chưa có
 * nhật ký (BR-LT-11). Hai câu đếm và lấy trang chạy trong cùng transaction REPEATABLE READ của
 * service nên cùng snapshot.
 */
@Repository
public class BoardingBookingListRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BoardingBookingListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param branchId        giới hạn chi nhánh (nhân viên); null = không giới hạn
     * @param ownerCustomerId giới hạn khách (khách đăng nhập); null = không giới hạn
     */
    public long count(Long branchId, Long ownerCustomerId, BookingFilter filter, java.time.LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + from(branchId, ownerCustomerId, filter, today, params), params,
                Long.class);
        return total == null ? 0 : total;
    }

    public List<Long> findPageIds(Long branchId, Long ownerCustomerId, BookingFilter filter, java.time.LocalDate today,
            long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT b.id " + from(branchId, ownerCustomerId, filter, today, params)
                + " ORDER BY b.check_in_date DESC, b.id DESC LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.queryForList(sql, params, Long.class);
    }

    static String from(Long branchId, Long ownerCustomerId, BookingFilter filter, java.time.LocalDate today,
            MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("FROM boarding_bookings b WHERE 1 = 1");
        if (branchId != null) {
            sql.append(" AND b.branch_id = :branchId");
            params.addValue("branchId", branchId);
        }
        if (ownerCustomerId != null) {
            sql.append(" AND b.customer_id = :ownerCustomerId");
            params.addValue("ownerCustomerId", ownerCustomerId);
        }
        if (filter.status() != null) {
            sql.append(" AND b.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.petId() != null) {
            sql.append(" AND b.pet_id = :petId");
            params.addValue("petId", filter.petId());
        }
        if (filter.customerId() != null) {
            sql.append(" AND b.customer_id = :customerId");
            params.addValue("customerId", filter.customerId());
        }
        if (filter.from() != null) {
            sql.append(" AND b.check_out_date >= :from");
            params.addValue("from", Date.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND b.check_in_date <= :to");
            params.addValue("to", Date.valueOf(filter.to()));
        }
        if (Boolean.TRUE.equals(filter.inStay())) {
            sql.append(" AND b.status IN ('CHECKED_IN', 'OVERDUE')");
        }
        if (Boolean.TRUE.equals(filter.missingCareLogToday())) {
            sql.append(" AND b.status IN ('CHECKED_IN', 'OVERDUE') AND NOT EXISTS (SELECT 1 FROM care_logs c "
                    + "WHERE c.boarding_booking_id = b.id AND c.log_date = :today)");
            params.addValue("today", Date.valueOf(today));
        }
        return sql.toString();
    }
}
