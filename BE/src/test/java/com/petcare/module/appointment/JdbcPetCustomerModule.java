package com.petcare.module.appointment;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.Species;

/**
 * Đứng thay {@link PetQueryApi} và {@link CustomerQueryApi} của module customer (BE-2, còn placeholder — nợ D010, D031)
 * cho IT đặt lịch (docs/adr/0043), khuôn {@code LinkProfileIT.JdbcCustomerModule}. Đọc thẳng bảng {@code pets},
 * {@code customers}, {@code weight_records} bằng SQL ở <b>mỗi lần gọi</b> (không cache), trong transaction của bên gọi —
 * đúng nghĩa vụ mà đặt lịch cần: đọc thú sau khóa thấy dữ liệu đã commit trước khi khóa được nhả. Method không dùng tới ném
 * lỗi.
 */
final class JdbcPetCustomerModule implements PetQueryApi, CustomerQueryApi {

    private final JdbcTemplate jdbc;

    JdbcPetCustomerModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PetSummary> findPet(Long petId) {
        return jdbc.query("""
                SELECT p.id, p.customer_id, p.name, p.species, p.birth_date, p.deceased_on,
                       (SELECT w.weight_kg FROM weight_records w WHERE w.pet_id = p.id
                        ORDER BY w.measured_at DESC, w.id DESC LIMIT 1) AS weight_kg
                FROM pets p WHERE p.id = ?""", (rs, n) -> new PetSummary(rs.getLong("id"), rs.getLong("customer_id"),
                rs.getString("name"), Species.valueOf(rs.getString("species")), toLocalDate(rs.getDate("birth_date")),
                toLocalDate(rs.getDate("deceased_on")), (BigDecimal) rs.getObject("weight_kg")), petId)
                .stream().findFirst();
    }

    @Override
    public Optional<CustomerContact> findContact(Long customerId) {
        return jdbc.query("""
                SELECT id, full_name, phone, email, account_id, link_decision_pending FROM customers WHERE id = ?
                """, (rs, n) -> new CustomerContact(rs.getLong("id"), rs.getString("full_name"),
                rs.getString("phone"), rs.getString("email"), (Long) rs.getObject("account_id"),
                rs.getBoolean("link_decision_pending")), customerId).stream().findFirst();
    }

    @Override
    public Optional<Long> findCustomerIdByAccountId(Long accountId) {
        return jdbc.queryForList("SELECT id FROM customers WHERE account_id = ?", Long.class, accountId).stream()
                .findFirst();
    }

    @Override
    public List<LinkCandidate> findLinkCandidates(String phone) {
        throw new UnsupportedOperationException("không dùng trong IT đặt lịch");
    }

    @Override
    public OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId) {
        throw new UnsupportedOperationException("không dùng trong IT đặt lịch");
    }

    private static java.time.LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
