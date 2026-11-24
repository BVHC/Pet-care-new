package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.support.MutableClock;

/**
 * Nhập viện (inpatient-v1 #1; BR-LT-13, BR-LT-18; docs/adr/0148, docs/adr/0149 mục 16–18) trên Postgres thật qua HTTP:
 * hai đường thành công và commit cùng nhau (chuồng / Visit điều trị / Order / ca / bộ đếm), đường (b) nhận cả
 * {@code OVERDUE} và giữ trạng thái đặt chỗ, giá chốt lúc nhập, mọi lỗi hình thức / 400 / 404 / 409 không để lại dấu vết.
 * Era <b>năm 2095</b> (không job nào quét {@code admissions}; docs/architecture/testing-notes.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionAdmitIT.TestBeans.class})
class AdmissionAdmitIT extends AdmissionItBase {

    private static final Instant NOW = Instant.parse("2095-03-14T02:00:00Z"); // 09:00 giờ Việt Nam
    private static final long PRICE = 250_000L;

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
    private long pet;
    private long visit;
    private long kennel;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, PRICE);
        pet = data.pet(customer);
        visit = data.visit(branch, customer, pet, vet);
        kennel = data.kennel(branch);
        token = token(vet);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void fromAVisitReturns201AndCommitsKennelAdmissionAndCodeTogether() {
        int counter = counter(Admission.CODE_DOC_TYPE);

        ResponseEntity<Map<String, Object>> response = admit(token, fromVisit(visit, service, kennel));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ADMISSION_KEYS);
        assertThat((String) body.get("code")).startsWith("NT-9503-");
        assertThat(body).containsEntry("visitId", (int) visit).containsEntry("petId", (int) pet)
                .containsEntry("branchId", (int) branch).containsEntry("boardingBookingId", null)
                .containsEntry("kennelId", (int) kennel).containsEntry("serviceId", (int) service)
                .containsEntry("unitPrice", (int) PRICE).containsEntry("status", "ADMITTED")
                .containsEntry("billedDays", 1).containsEntry("estimatedAmount", (int) PRICE)
                .containsEntry("dischargeOutcome", null).containsEntry("dischargeSummary", null)
                .containsEntry("dischargedAt", null).containsEntry("orderLineId", null);
        assertThat(Instant.parse((String) body.get("admittedAt"))).isEqualTo(NOW);

        // đọc ngoài transaction (autocommit): đã commit thật
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT visit_id, pet_id, branch_id, boarding_booking_id, kennel_id, service_id, unit_price, status,
                       admitted_by FROM admissions WHERE id = ?""", idOf(body, "admissionId"));
        assertThat(row).containsEntry("visit_id", visit).containsEntry("pet_id", pet).containsEntry("branch_id", branch)
                .containsEntry("boarding_booking_id", null).containsEntry("kennel_id", kennel)
                .containsEntry("service_id", service).containsEntry("unit_price", PRICE)
                .containsEntry("status", "ADMITTED").containsEntry("admitted_by", vet);
        assertThat(counter(Admission.CODE_DOC_TYPE)).isEqualTo(counter + 1);
        assertThat(visitCount(pet)).as("đường (a) không tạo Visit mới").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHECKED_IN", "OVERDUE"})
    void fromABookingCreatesAnInProgressTreatmentVisitWithAnEmptyOrderAndKeepsTheStay(String stayStatus) {
        long boarder = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, boarder, vet);
        jdbc.update("UPDATE boarding_bookings SET status = ? WHERE id = ?", stayStatus, booking);
        long stayKennel = bookingKennel(booking);

        ResponseEntity<Map<String, Object>> response = admit(token, fromBooking(booking, service));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body).containsEntry("boardingBookingId", (int) booking).containsEntry("kennelId", null)
                .containsEntry("petId", (int) boarder).containsEntry("status", "ADMITTED");
        long treatment = idOf(body, "visitId");
        Map<String, Object> visitRow = jdbc.queryForMap("""
                SELECT status, assignee_id, appointment_id, service_group, customer_id, branch_id FROM visits
                WHERE id = ?""", treatment);
        assertThat(visitRow).containsEntry("status", "IN_PROGRESS").containsEntry("assignee_id", vet)
                .containsEntry("appointment_id", null).containsEntry("service_group", "MEDICAL")
                .containsEntry("customer_id", customer).containsEntry("branch_id", branch);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, treatment))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, treatment)).as("Order#14: không dòng tự sinh").isZero();
        assertThat(bookingStatus(booking)).as("BR-LT-17: đặt chỗ giữ trạng thái").isEqualTo(stayStatus);
        assertThat(kennelStatus(stayKennel)).as("thú ở nguyên chuồng của đặt chỗ").isEqualTo("OCCUPIED");
        assertThat(counter("visits")).isPositive();
        assertThat(counter(OnlineSalesOrderApi.ORDER_DOC_TYPE)).isPositive();
    }

    @Test
    void theDailyPriceIsFrozenAtAdmissionAndTheDetailShowsTheCurrentServiceName() {
        long id = idOf(dataOf(admit(token, fromVisit(visit, service, kennel))), "admissionId");
        jdbc.update("UPDATE services SET price = 999000, name = 'Nội trú (đổi tên)' WHERE id = ?", service);

        Map<String, Object> detail = dataOf(get(ADMISSIONS + "/" + id, token));

        assertThat(detail).containsEntry("unitPrice", (int) PRICE).containsEntry("serviceName", "Nội trú (đổi tên)")
                .containsEntry("estimatedAmount", (int) PRICE);
    }

    // ---------------------------------------------------------------- hình thức (trước service)

    @Test
    void aMissingServiceIdOrANonPositiveIdIsValidationFailedAndWritesNothing() {
        String before = footprint(pet);

        assertStatus(admit(token, "{'visitId':" + visit + ",'kennelId':" + kennel + "}"), 400, "VALIDATION_FAILED");
        assertStatus(admit(token, fromVisit(0, service, kennel)), 400, "VALIDATION_FAILED");
        assertStatus(admit(token, fromVisit(visit, service, -1)), 400, "VALIDATION_FAILED");

        assertThat(footprint(pet)).isEqualTo(before);
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void wrongJsonTypesAreMalformed() {
        assertStatus(admit(token, "{'visitId':'abc','serviceId':" + service + ",'kennelId':" + kennel + "}"), 400,
                "MALFORMED_REQUEST");
        assertStatus(admit(token, "{'visitId':" + visit + ".5,'serviceId':" + service + ",'kennelId':" + kennel + "}"),
                400, "MALFORMED_REQUEST");
        assertStatus(admit(token, "{not json"), 400, "MALFORMED_REQUEST");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void anUnknownFieldIsIgnoredSoAMisspelledKennelIdIsAMissingKennel() {
        assertRule(admit(token, "{'visitId':" + visit + ",'serviceId':" + service + ",'kennelID':" + kennel + "}"),
                "BR-LT-13");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    // ---------------------------------------------------------------- 400 BR-LT-13 nguồn

    @Test
    void theSourceMustBeExactlyOneAndTheKennelMustMatchIt() {
        long booking = data.checkedInBooking(branch, customer, data.pet(customer), vet);
        String before = footprint(pet);

        assertRule(admit(token, "{'visitId':" + visit + ",'boardingBookingId':" + booking + ",'serviceId':" + service
                + ",'kennelId':" + kennel + "}"), "BR-LT-13");
        assertRule(admit(token, "{'serviceId':" + service + ",'kennelId':" + kennel + "}"), "BR-LT-13");
        assertRule(admit(token, "{'visitId':" + visit + ",'serviceId':" + service + "}"), "BR-LT-13");
        assertRule(admit(token, "{'boardingBookingId':" + booking + ",'serviceId':" + service + ",'kennelId':" + kennel
                + "}"), "BR-LT-13");

        assertThat(footprint(pet)).isEqualTo(before);
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    // ---------------------------------------------------------------- 404

    @Test
    void unknownVisitBookingServiceOrKennelIs404AndWritesNothing() {
        String before = footprint(pet);

        assertStatus(admit(token, fromVisit(Long.MAX_VALUE, service, kennel)), 404, "RESOURCE_NOT_FOUND");
        assertStatus(admit(token, fromBooking(Long.MAX_VALUE, service)), 404, "RESOURCE_NOT_FOUND");
        assertStatus(admit(token, fromVisit(visit, Long.MAX_VALUE, kennel)), 404, "RESOURCE_NOT_FOUND");
        assertStatus(admit(token, fromVisit(visit, service, Long.MAX_VALUE)), 404, "RESOURCE_NOT_FOUND");

        assertThat(footprint(pet)).isEqualTo(before);
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(counter(Admission.CODE_DOC_TYPE)).isZero();
    }

    // ---------------------------------------------------------------- 409

    @Test
    void aSecondAdmissionOfTheSamePetIs409AndLeavesTheSecondKennelFree() {
        assertThat(admit(token, fromVisit(visit, service, kennel)).getStatusCode().value()).isEqualTo(201);
        long second = data.kennel(branch);

        ResponseEntity<Map<String, Object>> response = admit(token, fromVisit(visit, service, second));

        assertStatus(response, 409, "INVALID_STATE_TRANSITION");
        assertThat(message(response)).contains("đã có ca đang điều trị");
        assertThat(admissionCount(pet)).isEqualTo(1);
        assertThat(kennelStatus(second)).isEqualTo("AVAILABLE");
    }

    @Test
    void theDuplicateCheckRunsBeforeTheRuleChecks() {
        assertThat(admit(token, fromVisit(visit, service, kennel)).getStatusCode().value()).isEqualTo(201);
        jdbc.update("UPDATE visits SET status = 'COMPLETED', completed_at = now() WHERE id = ?", visit);

        assertStatus(admit(token, fromVisit(visit, service, data.kennel(branch))), 409, "INVALID_STATE_TRANSITION");
    }

    // ---------------------------------------------------------------- 400 BR-LT-13 điều kiện (mỗi ca không ghi gì)

    @Test
    void aDeceasedPetIsRejected() {
        jdbc.update("UPDATE pets SET deceased_on = DATE '2095-03-13' WHERE id = ?", pet);

        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING", "COMPLETED", "CANCELLED"})
    void aVisitThatIsNotInProgressIsRejected(String status) {
        jdbc.update("UPDATE visits SET status = ? WHERE id = ?", status, visit);

        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));
    }

    @Test
    void aGroomingVisitIsRejected() {
        jdbc.update("UPDATE visits SET service_group = 'GROOMING' WHERE id = ?", visit);

        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));
    }

    @Test
    void aPetInABoardingStayCannotTakeASecondKennelFromAVisit() {
        data.checkedInBooking(branch, customer, pet, vet);

        ResponseEntity<Map<String, Object>> response = admit(token, fromVisit(visit, service, kennel));

        assertRule(response, "BR-LT-13");
        assertThat(message(response)).contains("đặt chỗ lưu trú");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(admissionCount(pet)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOKED", "CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void aBookingThatDoesNotHoldThePetIsRejected(String status) {
        long boarder = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, boarder, vet);
        jdbc.update("UPDATE boarding_bookings SET status = ?, end_reason = ? WHERE id = ?", status,
                "CHECKED_OUT".equals(status) ? "RETURNED" : null, booking);
        String before = footprint(boarder);

        assertRule(admit(token, fromBooking(booking, service)), "BR-LT-13");

        assertThat(footprint(boarder)).as("không Visit / Order điều trị").isEqualTo(before);
        assertThat(bookingStatus(booking)).isEqualTo(status);
    }

    @Test
    void aServiceThatIsNotInpatientInactiveOrDisabledAtTheBranchIsRejected() {
        jdbc.update("UPDATE services SET medical_type = 'EXAM' WHERE id = ?", service);
        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));

        jdbc.update("UPDATE services SET medical_type = 'INPATIENT', is_active = false WHERE id = ?", service);
        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));

        jdbc.update("UPDATE services SET is_active = true WHERE id = ?", service);
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                service);
        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));
    }

    @Test
    void aKennelInMaintenanceOrOfAnotherBranchIsRejected() {
        jdbc.update("UPDATE kennels SET status = 'MAINTENANCE' WHERE id = ?", kennel);
        assertRejectedWithoutWrites(fromVisit(visit, service, kennel));
        assertThat(kennelStatus(kennel)).isEqualTo("MAINTENANCE");

        long otherKennel = data.kennel(data.branch());
        assertRejectedWithoutWrites(fromVisit(visit, service, otherKennel));
        assertThat(kennelStatus(otherKennel)).isEqualTo("AVAILABLE");
    }

    private void assertRejectedWithoutWrites(String json) {
        String before = footprint(pet);
        int counter = counter(Admission.CODE_DOC_TYPE);

        assertRule(admit(token, json), "BR-LT-13");

        assertThat(footprint(pet)).isEqualTo(before);
        assertThat(counter(Admission.CODE_DOC_TYPE)).isEqualTo(counter);
        if (!"MAINTENANCE".equals(kennelStatus(kennel))) {
            assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        }
    }
}
