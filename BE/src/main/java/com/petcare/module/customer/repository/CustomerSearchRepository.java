package com.petcare.module.customer.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.customer.dto.CustomerSearchFilter;
import com.petcare.module.customer.entity.CustomerChannel;

/**
 * Đường đọc của tra cứu khách (UC23, customer-v1 #8; BR-KH-10; docs/adr/0041). Truy vấn động chỉ ghép điều kiện đã gửi —
 * <b>không</b> dùng {@code :x IS NULL OR …} vì kế hoạch generic mất index ({@code ix_customers_phone},
 * {@code ix_customers_email}, {@code ix_pets_customer_id}); giống {@code AuditLogQueryRepository} của identity. Họ tên và
 * tên thú tìm bằng {@code ILIKE '%x%'} (không index, phân biệt dấu — nợ ghi ở docs/dept). Không tự mở transaction:
 * dùng connection của {@code CustomerSearchService} (readOnly).
 */
@Repository
public class CustomerSearchRepository {

    /** Một dòng khách của danh sách; thú cưng đọc riêng cho cả trang bằng một câu. */
    public record Row(Long id, String fullName, String phone, String email, CustomerChannel createdChannel,
            boolean hasAccount) {
    }

    private static final RowMapper<Row> ROW_MAPPER = CustomerSearchRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(CustomerSearchFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) " + from(filter, params);
        Long total = jdbc.queryForObject(sql, params, Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> findPage(CustomerSearchFilter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
                SELECT c.id, c.full_name, c.phone, c.email, c.created_channel, c.account_id IS NOT NULL AS has_account
                """ + from(filter, params) + " ORDER BY c.full_name, c.id LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    /** FROM … WHERE chỉ chứa điều kiện đã gửi; tham số đặt vào {@code params}. */
    private static String from(CustomerSearchFilter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("FROM customers c WHERE 1 = 1");
        if (filter.phone() != null) {
            sql.append(" AND c.phone = :phone");
            params.addValue("phone", filter.phone());
        }
        if (filter.email() != null) {
            sql.append(" AND c.email = :email");
            params.addValue("email", filter.email());
        }
        if (filter.name() != null) {
            sql.append(" AND c.full_name ILIKE :name ESCAPE '!'");
            params.addValue("name", likePattern(filter.name()));
        }
        if (filter.petName() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM pets p WHERE p.customer_id = c.id AND p.name ILIKE :petName"
                    + " ESCAPE '!')");
            params.addValue("petName", likePattern(filter.petName()));
        }
        return sql.toString();
    }

    /** "chứa chuỗi": thoát {@code !}, {@code %}, {@code _} để người dùng gõ ký tự đặc biệt vẫn khớp đúng nghĩa đen. */
    static String likePattern(String text) {
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("full_name"), rs.getString("phone"), rs.getString("email"),
                CustomerChannel.valueOf(rs.getString("created_channel")), rs.getBoolean("has_account"));
    }
}
