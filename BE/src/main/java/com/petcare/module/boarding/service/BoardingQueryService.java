package com.petcare.module.boarding.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.config.TimeConfig;

/**
 * Cài thật {@link BoardingQueryApi} (T31, docs/adr/0065; trả nợ D033). Chỉ đọc {@code boarding_bookings} (và
 * {@code boarding_check_ins}) bằng SQL, không khóa, tham gia transaction của bên gọi: {@link #countViolations} chạy dưới
 * khóa theo khách (advisory 1002) của {@code BookingRestrictionApi.evaluateAfterViolation}, READ COMMITTED.
 */
@Service
public class BoardingQueryService implements BoardingQueryApi {

    private static final String STAY_SELECT = """
            SELECT b.id, b.branch_id, b.customer_id, b.pet_id, b.status, c.emergency_phone
            FROM boarding_bookings b LEFT JOIN boarding_check_ins c ON c.boarding_booking_id = b.id
            """;

    /** {@code PetQueryApi.findPets}: bên gọi truyền tối đa 100 id (06 §6). */
    private static final int PET_BATCH = 100;

    private final JdbcTemplate jdbc;
    private final PetQueryApi pets;
    private final SystemConfigApi config;
    private final Clock clock;

    public BoardingQueryService(JdbcTemplate jdbc, PetQueryApi pets, SystemConfigApi config, Clock clock) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Optional<StaySummary> findBooking(Long bookingId) {
        return jdbc.query(STAY_SELECT + " WHERE b.id = ?", (rs, n) -> toSummary(rs), bookingId).stream().findFirst();
    }

    @Override
    public boolean isPetInStay(Long petId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE pet_id = ? AND status IN ('CHECKED_IN', 'OVERDUE'))
                """, Boolean.class, petId));
    }

    @Override
    public boolean existsByPet(Long petId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE pet_id = ?)", Boolean.class, petId));
    }

    @Override
    public List<Long> findBookingIdsByPet(Long petId) {
        return jdbc.queryForList("SELECT id FROM boarding_bookings WHERE pet_id = ? ORDER BY id", Long.class, petId);
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE customer_id = ?)", Boolean.class, customerId));
    }

    @Override
    public List<BookedStay> findBookedFrom(Long branchId, LocalDate fromDate) {
        return jdbc.query("""
                SELECT id, code, pet_id, customer_id, check_in_date, check_out_date FROM boarding_bookings
                WHERE branch_id = ? AND status = 'BOOKED' AND check_out_date >= ? ORDER BY id
                """, (rs, n) -> new BookedStay(rs.getLong("id"), rs.getString("code"), rs.getLong("pet_id"),
                rs.getLong("customer_id"), rs.getObject("check_in_date", LocalDate.class),
                rs.getObject("check_out_date", LocalDate.class)), branchId, fromDate);
    }

    /**
     * Hủy muộn hoặc {@code NO_SHOW} có thời điểm vi phạm {@code > since}. Bảng không có cột thời điểm riêng: dùng
     * {@code updated_at} của dòng đã ở trạng thái cuối (ghi một lần, từ {@code Clock}); xem docs/adr/0065 mục 3.
     */
    @Override
    public int countViolations(Long customerId, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT CAST(count(*) AS integer) FROM boarding_bookings
                WHERE customer_id = ? AND ((status = 'CANCELLED' AND late_cancel) OR status = 'NO_SHOW')
                  AND updated_at > ?
                """, Integer.class, customerId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public List<StaySummary> findActiveStaysByCustomer(Long customerId) {
        return jdbc.query(STAY_SELECT + " WHERE b.customer_id = ? AND b.status IN ('BOOKED', 'CHECKED_IN', 'OVERDUE')"
                + " ORDER BY b.id", (rs, n) -> toSummary(rs), customerId);
    }

    /** I-7 (UC92): mọi đặt chỗ của thú, mới nhất trước (theo ngày nhận, rồi id). */
    @Override
    public List<StayOfPet> findStaysOfPet(Long petId) {
        return jdbc.query("""
                SELECT id, kennel_type_id, status, check_in_date, check_out_date FROM boarding_bookings
                WHERE pet_id = ? ORDER BY check_in_date DESC, id DESC
                """, (rs, n) -> new StayOfPet(rs.getLong("id"), rs.getLong("kennel_type_id"),
                StayStatus.valueOf(rs.getString("status")), rs.getObject("check_in_date", LocalDate.class),
                rs.getObject("check_out_date", LocalDate.class)), petId);
    }

    /**
     * I-7 (UC92): lượt {@code CHECKED_OUT} có {@code actual_check_out_at} từ 00:00 ngày {@code since} (giờ Việt Nam). Loài
     * và nhóm tuổi của thú lấy qua {@code PetQueryApi} (không JOIN bảng của customer); tuổi tính tại ngày gọi.
     */
    @Override
    public Map<Long, Long> countStaysByKennelType(Species species, VisitQueryApi.AgeGroup ageGroup, LocalDate since) {
        List<long[]> rows = jdbc.query("""
                SELECT pet_id, kennel_type_id, count(*) AS cnt FROM boarding_bookings
                WHERE status = 'CHECKED_OUT' AND actual_check_out_at >= ? GROUP BY pet_id, kennel_type_id
                """, (rs, n) -> new long[] {rs.getLong("pet_id"), rs.getLong("kennel_type_id"), rs.getLong("cnt")},
                Timestamp.from(since.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        Set<Long> inGroup = petIdsInGroup(rows.stream().map(r -> r[0]).distinct().toList(), species, ageGroup);
        Map<Long, Long> result = new LinkedHashMap<>();
        rows.stream().filter(r -> inGroup.contains(r[0])).forEach(r -> result.merge(r[1], r[2], Long::sum));
        return result;
    }

    private Set<Long> petIdsInGroup(List<Long> petIds, Species species, VisitQueryApi.AgeGroup ageGroup) {
        LocalDate today = LocalDate.now(clock);
        int seniorAgeYears = config.getInt(switch (species) {
            case DOG -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG;
            case CAT -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT;
            case OTHER -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER;
        });
        Set<Long> result = new HashSet<>();
        for (int from = 0; from < petIds.size(); from += PET_BATCH) {
            pets.findPets(petIds.subList(from, Math.min(from + PET_BATCH, petIds.size()))).stream()
                    .filter(p -> p.species() == species
                            && VisitQueryApi.AgeGroup.of(p.birthDate(), today, seniorAgeYears) == ageGroup)
                    .forEach(p -> result.add(p.petId()));
        }
        return result;
    }

    private static StaySummary toSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StaySummary(rs.getLong("id"), rs.getLong("branch_id"), rs.getLong("customer_id"),
                rs.getLong("pet_id"), StayStatus.valueOf(rs.getString("status")), rs.getString("emergency_phone"));
    }
}
