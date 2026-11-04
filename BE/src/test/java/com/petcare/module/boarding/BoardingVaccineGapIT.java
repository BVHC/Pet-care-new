package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.VaccinationItSupport;
import com.petcare.support.MutableClock;

/**
 * T45-1 — BR-LT-05 với bean thật, không stand-in {@code VaccinationQueryApi}: lúc đặt chỉ cảnh báo thiếu mũi bắt buộc,
 * lúc nhận thú bị chặn 400. Loài {@code OTHER} có phác đồ riêng của IT (chó / mèo không có phác đồ bắt buộc để các IT
 * lưu trú khác không bị ảnh hưởng); dọn phác đồ ngay sau mỗi ca. Era <b>năm 2038</b> (không job nào quét tới). Đường
 * "đủ mũi" đã được {@code VaccinationQueryService} và IT ghi mũi tiêm của visit phủ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingVaccineGapIT.TestBeans.class})
class BoardingVaccineGapIT extends BoardingItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2038, 8, 4);
    private static final LocalDate IN = LocalDate.of(2038, 8, 6);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(instantAt(TODAY, 10));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private MutableClock clock;

    private VaccinationItSupport catalog;
    private long vaccineType;
    private long branch;
    private long type;
    private long kennel;
    private long receptionistId;
    private long customerAccount;
    private long pet;
    private String receptionist;
    private String customerToken;

    @BeforeEach
    void setUp() {
        catalog = new VaccinationItSupport(jdbc);
        vaccineType = catalog.vaccineType("OTHER", "Dại IT");
        catalog.protocol("OTHER", vaccineType, 1, 365, true);
        branch = newBranch();
        type = newKennelType(branch, "OTHER", "30", 150_000);
        kennel = newKennel(branch, type, "V-01", "AVAILABLE");
        receptionistId = newStaff("RECEPTIONIST", branch);
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        pet = newPet(online[1], "OTHER");
        weigh(pet, "5.00");
        at(TODAY, 10);
    }

    @AfterEach
    void removeProtocol() {
        catalog.deleteCatalog();
    }

    private long book() {
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("kennelTypeId", type);
        body.put("checkInDate", IN.toString());
        body.put("checkOutDate", IN.plusDays(2).toString());
        Map<String, Object> result = data(call(HttpMethod.POST, "/api/boarding-bookings", customerToken, body),
                HttpStatus.CREATED);
        warnings = list(result.get("vaccineWarnings"));
        return number(map(result.get("booking")).get("bookingId"));
    }

    private List<Map<String, Object>> warnings;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void bookingOnlyWarnsAboutTheMissingDose() {
        long booking = book();

        assertThat(statusOf(booking)).isEqualTo("BOOKED");
        assertThat(warnings).hasSize(1);
        assertThat(number(warnings.get(0).get("vaccineTypeId"))).isEqualTo(vaccineType);
    }

    @Test
    void checkInIsRefusedWithBrLt05AndChangesNothing() {
        long booking = book();
        at(IN, 10);
        Map<String, Object> checkIn = new HashMap<>();
        checkIn.put("kennelId", kennel);
        checkIn.put("weightKg", "5.00");
        checkIn.put("healthCondition", "Tỉnh táo");
        checkIn.put("belongings", "Chăn");
        checkIn.put("dietInstructions", "Hạt khô");
        checkIn.put("emergencyPhone", "0901234567");

        assertRule(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/check-in", receptionist, checkIn),
                "BR-LT-05");

        assertThat(statusOf(booking)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, kennel))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarding_check_ins WHERE boarding_booking_id = ?",
                Long.class, booking)).isZero();
    }

    private void at(LocalDate day, int hour) {
        clock.set(instantAt(day, hour));
        receptionist = token(receptionistId);
        customerToken = token(customerAccount);
    }

    private static Instant instantAt(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(VN).toInstant();
    }
}
