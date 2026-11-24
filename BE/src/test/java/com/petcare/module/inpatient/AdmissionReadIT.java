package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Đọc ca nội trú (inpatient-v1 #2, #3; BR-LT-14, BR-LT-17; docs/adr/0149 mục 19) trên HTTP thật: {@code status} mặc định
 * {@code ADMITTED} và lọc {@code DISCHARGED}, thứ tự nhập viện tăng dần (trùng thì id), phân trang, mã chuồng đúng cả
 * hai đường, đủ khóa JSON (nullable có mặt), {@code billedDays} đổi khi qua ngày và đứng yên sau xuất viện, 404 / 400
 * hình thức. Era <b>năm 2098</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionReadIT.TestBeans.class})
class AdmissionReadIT extends AdmissionItBase {

    private static final Instant NOW = Instant.parse("2098-03-14T02:00:00Z"); // 09:00 giờ Việt Nam

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long vet;
    private long customer;
    private long service;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = data.inpatientService(200_000L);
        token = token(data.staff("RECEPTIONIST", branch));
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    /** Ca nhập từ lượt khám, chuồng điều trị riêng; trả {id ca, id chuồng}. */
    private long[] fromVisitAdmission(Instant admittedAt, String code) {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        long id = jdbc.queryForObject("""
                INSERT INTO admissions (code, visit_id, pet_id, branch_id, kennel_id, service_id, unit_price, status,
                        admitted_by, admitted_at)
                VALUES (?, ?, ?, ?, ?, ?, 200000, 'ADMITTED', ?, ?) RETURNING id""", Long.class, code, visit, pet,
                branch, kennel, service, vet, Timestamp.from(admittedAt));
        return new long[] {id, kennel};
    }

    /** Ca nhập từ lưu trú; trả {id ca, id chuồng của đặt chỗ}. */
    private long[] fromBookingAdmission(Instant admittedAt, String code) {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        long visit = data.visit(branch, customer, pet, vet);
        long id = jdbc.queryForObject("""
                INSERT INTO admissions (code, visit_id, pet_id, branch_id, boarding_booking_id, service_id, unit_price,
                        status, admitted_by, admitted_at)
                VALUES (?, ?, ?, ?, ?, ?, 300000, 'ADMITTED', ?, ?) RETURNING id""", Long.class, code, visit, pet,
                branch, booking, service, vet, Timestamp.from(admittedAt));
        return new long[] {id, bookingKennel(booking)};
    }

    private void discharge(long admissionId, Instant at) {
        long visit = jdbc.queryForObject("SELECT visit_id FROM admissions WHERE id = ?", Long.class, admissionId);
        long line = data.orderLine(visit, service, 1, 200_000L, vet);
        jdbc.update("""
                UPDATE admissions SET status = 'DISCHARGED', discharge_outcome = 'HOME', discharge_summary = 'Ổn',
                    discharged_by = ?, discharged_at = ?, order_line_id = ? WHERE id = ?""", vet, Timestamp.from(at),
                line, admissionId);
    }

    @Test
    void theListDefaultsToAdmittedOldestFirstWithEveryContractKeyAndBothKennelSources() {
        long[] later = fromVisitAdmission(NOW.minus(Duration.ofHours(1)), "NT-9803-00002");
        long[] earlier = fromBookingAdmission(NOW.minus(Duration.ofHours(30)), "NT-9803-00001");
        long[] gone = fromVisitAdmission(NOW.minus(Duration.ofHours(40)), "NT-9803-00003");
        discharge(gone[0], NOW.minus(Duration.ofHours(2)));

        ResponseEntity<Map<String, Object>> response = get(ADMISSIONS, token);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = contentOf(response);
        assertThat(rows).extracting(r -> idOf(r, "admissionId")).containsExactly(earlier[0], later[0]);
        assertThat(rows.get(0).keySet()).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(rows.get(0)).containsEntry("kennelCode", kennelCode(earlier[1])).containsEntry("status", "ADMITTED")
                .containsEntry("billedDays", 2).containsEntry("estimatedAmount", 600_000);
        assertThat(rows.get(1)).containsEntry("kennelCode", kennelCode(later[1])).containsEntry("billedDays", 1)
                .containsEntry("estimatedAmount", 200_000);
        assertThat(dataOf(response)).containsEntry("totalElements", 2);
    }

    @Test
    void dischargedCasesAreListedOnlyOnRequestAndTheirDaysStopAtDischarge() {
        long[] gone = fromVisitAdmission(NOW.minus(Duration.ofDays(3)), "NT-9803-00004");
        discharge(gone[0], NOW.minus(Duration.ofDays(2)));

        List<Map<String, Object>> rows = contentOf(get(ADMISSIONS + "?status=DISCHARGED", token));

        assertThat(rows).extracting(r -> idOf(r, "admissionId")).containsExactly(gone[0]);
        assertThat(rows.get(0)).containsEntry("status", "DISCHARGED").containsEntry("billedDays", 2);
        clock.set(NOW.plus(Duration.ofDays(5)));
        token = token(data.staff("RECEPTIONIST", branch));
        assertThat(contentOf(get(ADMISSIONS + "?status=DISCHARGED", token)).get(0)).containsEntry("billedDays", 2);
    }

    @Test
    void billedDaysGrowWhenTheClockCrossesMidnightInVietnam() {
        long[] admitted = fromVisitAdmission(NOW, "NT-9803-00005");
        assertThat(dataOf(get(ADMISSIONS + "/" + admitted[0], token))).containsEntry("billedDays", 1);

        clock.set(Instant.parse("2098-03-14T17:00:00Z")); // 00:00 ngày 15 giờ Việt Nam
        token = token(data.staff("RECEPTIONIST", branch));

        assertThat(dataOf(get(ADMISSIONS + "/" + admitted[0], token))).containsEntry("billedDays", 2)
                .containsEntry("estimatedAmount", 400_000);
    }

    @Test
    void equalAdmissionTimesAreOrderedByIdAndPagesSplitTheList() {
        long first = fromVisitAdmission(NOW, "NT-9803-00006")[0];
        long second = fromVisitAdmission(NOW, "NT-9803-00007")[0];
        long third = fromVisitAdmission(NOW, "NT-9803-00008")[0];

        assertThat(contentOf(get(ADMISSIONS + "?size=2", token))).extracting(r -> idOf(r, "admissionId"))
                .containsExactly(first, second);
        ResponseEntity<Map<String, Object>> page2 = get(ADMISSIONS + "?size=2&page=1", token);
        assertThat(contentOf(page2)).extracting(r -> idOf(r, "admissionId")).containsExactly(third);
        assertThat(dataOf(page2)).containsEntry("totalElements", 3);
        assertThat(get(ADMISSIONS + "?size=0&page=-3", token).getStatusCode().value()).isEqualTo(200);
        assertThat(get(ADMISSIONS + "?size=100&page=2147483647", token).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theDetailHasEveryContractKeyIncludingTheNullOnes() {
        long[] admitted = fromBookingAdmission(NOW, "NT-9803-00009");

        Map<String, Object> body = dataOf(get(ADMISSIONS + "/" + admitted[0], token));

        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ADMISSION_KEYS);
        assertThat(body).containsEntry("kennelId", null).containsEntry("dischargeOutcome", null)
                .containsEntry("dischargeSummary", null).containsEntry("dischargedAt", null)
                .containsEntry("orderLineId", null).containsEntry("unitPrice", 300_000);
    }

    @Test
    void malformedParametersAre400AndAnUnknownIdIs404() {
        assertStatus(get(ADMISSIONS + "?status=FOO", token), 400, "MALFORMED_REQUEST");
        assertStatus(get(ADMISSIONS + "?page=abc", token), 400, "MALFORMED_REQUEST");
        assertStatus(get(ADMISSIONS + "/abc", token), 400, "MALFORMED_REQUEST");
        assertStatus(get(ADMISSIONS + "/" + Long.MAX_VALUE, token), 404, "RESOURCE_NOT_FOUND");
    }
}
