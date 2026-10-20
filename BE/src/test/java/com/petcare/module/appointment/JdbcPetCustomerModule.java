package com.petcare.module.appointment;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.Species;

/**
 * Đứng thay {@link PetQueryApi} và {@link CustomerQueryApi} của module customer (BE-2, còn placeholder — nợ D010, D031)
 * cho IT đặt lịch (docs/adr/0047) và xem lịch (docs/adr/0048), khuôn {@code LinkProfileIT.JdbcCustomerModule}. Đọc thẳng
 * bảng {@code pets}, {@code customers}, {@code weight_records} bằng SQL ở <b>mỗi lần gọi</b> (không cache), trong
 * transaction của bên gọi — đúng nghĩa vụ mà đặt lịch cần: đọc thú sau khóa thấy dữ liệu đã commit trước khi khóa được
 * nhả. Hai method theo lô ({@code findPets}, {@code findContacts}) đếm số lần gọi để IT xem lịch chứng minh mỗi trang chỉ
 * gọi một lần. Method không dùng tới ném lỗi.
 */
final class JdbcPetCustomerModule implements PetQueryApi, CustomerQueryApi {

    private static final String PET_SELECT = """
            SELECT p.id, p.customer_id, p.name, p.species, p.birth_date, p.deceased_on,
                   (SELECT w.weight_kg FROM weight_records w WHERE w.pet_id = p.id
                    ORDER BY w.measured_at DESC, w.id DESC LIMIT 1) AS weight_kg
            FROM pets p
            """;
    private static final String CONTACT_SELECT =
            "SELECT id, full_name, phone, email, account_id, link_decision_pending FROM customers ";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    final AtomicInteger findPetsCalls = new AtomicInteger();
    final AtomicInteger findContactsCalls = new AtomicInteger();

    JdbcPetCustomerModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    void resetCounters() {
        findPetsCalls.set(0);
        findContactsCalls.set(0);
    }

    @Override
    public Optional<PetSummary> findPet(Long petId) {
        return jdbc.query(PET_SELECT + "WHERE p.id = ?", (rs, n) -> pet(rs), petId).stream().findFirst();
    }

<<<<<<< HEAD
    /** Như {@code PetQueryService.findPetForShare} (docs/adr/0041): khóa chia sẻ dòng thú rồi đọc. */
    @Override
    public Optional<PetSummary> findPetForShare(Long petId) {
        return jdbc.query(PET_SELECT + "WHERE p.id = ? FOR SHARE OF p", (rs, n) -> pet(rs), petId).stream().findFirst();
=======
    /** Như {@code PetQueryService.findPetForShare} (T17, thêm khi gộp nhánh): khóa chia sẻ dòng thú rồi đọc. */
    @Override
    public Optional<PetSummary> findPetForShare(Long petId) {
        return jdbc.query(PET_SELECT + "WHERE p.id = ? FOR SHARE OF p", (rs, n) -> pet(rs), petId).stream()
                .findFirst();
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    }

    @Override
    public List<PetSummary> findPets(Collection<Long> petIds) {
        findPetsCalls.incrementAndGet();
        if (petIds.isEmpty()) {
            return List.of();
        }
        return named.query(PET_SELECT + "WHERE p.id IN (:ids)", Map.of("ids", petIds), (rs, n) -> pet(rs));
    }

    @Override
    public Optional<CustomerContact> findContact(Long customerId) {
        return jdbc.query(CONTACT_SELECT + "WHERE id = ?", (rs, n) -> contact(rs), customerId).stream().findFirst();
    }

    @Override
    public List<CustomerContact> findContacts(Collection<Long> customerIds) {
        findContactsCalls.incrementAndGet();
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return named.query(CONTACT_SELECT + "WHERE id IN (:ids)", Map.of("ids", customerIds), (rs, n) -> contact(rs));
    }

    @Override
    public Optional<Long> findCustomerIdByAccountId(Long accountId) {
        return jdbc.queryForList("SELECT id FROM customers WHERE account_id = ?", Long.class, accountId).stream()
                .findFirst();
    }

    @Override
    public List<LinkCandidate> findLinkCandidates(String phone) {
        throw new UnsupportedOperationException("không dùng trong IT lịch hẹn");
    }

    @Override
    public OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId) {
        throw new UnsupportedOperationException("không dùng trong IT lịch hẹn");
    }

    private static PetSummary pet(ResultSet rs) throws SQLException {
        return new PetSummary(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("name"),
                Species.valueOf(rs.getString("species")), toLocalDate(rs.getDate("birth_date")),
                toLocalDate(rs.getDate("deceased_on")), (BigDecimal) rs.getObject("weight_kg"));
    }

    private static CustomerContact contact(ResultSet rs) throws SQLException {
        return new CustomerContact(rs.getLong("id"), rs.getString("full_name"), rs.getString("phone"),
                rs.getString("email"), (Long) rs.getObject("account_id"), rs.getBoolean("link_decision_pending"));
    }

    private static java.time.LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
