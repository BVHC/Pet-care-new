package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Y lệnh nội trú (inpatient-v1 #5–#8; docs/adr/0149 mục 26–30) trên Postgres thật: đường đúng của bốn endpoint với
 * response khớp từng cột DB, ba quyết định nghiệp vụ (dòng mua ngoài, dòng đơn cho y lệnh không phải thuốc, giờ dự kiến tự
 * do), <b>409 trước 400</b> (rule), xuất viện chặn / thả theo y lệnh, và ma trận đầu vào với đúng {@code errorCode}. Sau
 * mỗi lời gọi bị từ chối, DB không đổi. Phân quyền ở {@code AdmissionOrderSecurityIT}, khóa chờ ở {@code AdmissionLockIT},
 * rollback ở {@code AdmissionOrderRollbackIT}. Era <b>năm 2101</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionOrderIT.TestBeans.class})
class AdmissionOrderIT extends AdmissionOrderItBase {

    /** 09:00 ngày 14/3/2101 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2101-03-14T02:00:00.123456Z");

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
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, 250_000L);
        token = token(vet);
        a = admitFromVisit(branch, customer, service, vet, token);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- #5 ra y lệnh

    @Test
    void aFeedingOrderIsStoredAndTheResponseMatchesEveryColumn() {
        ResponseEntity<Map<String, Object>> response = createOrder(token, a.id(),
                "{'orderType':'FEEDING','content':'  Cho ăn pate ','scheduledAt':'2101-03-14T05:00:00.123456789Z'}");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ORDER_KEYS);
        assertThat(body).containsEntry("admissionId", (int) a.id()).containsEntry("orderType", "FEEDING")
                .containsEntry("content", "Cho ăn pate").containsEntry("status", "ORDERED")
                .containsEntry("prescriptionItemId", null).containsEntry("doneAt", null)
                .containsEntry("doneNote", null).containsEntry("cancelReason", null);
        assertThat(Instant.parse((String) body.get("scheduledAt")))
                .isEqualTo(Instant.parse("2101-03-14T05:00:00.123456Z"));

        long id = idOf(body, "admissionOrderId");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT admission_id, order_type, content, scheduled_at, status, prescription_item_id, created_by,
                       done_by, done_at, done_note, cancel_reason
                FROM admission_orders WHERE id = ?""", id);
        assertThat(row).containsEntry("admission_id", a.id()).containsEntry("order_type", "FEEDING")
                .containsEntry("content", "Cho ăn pate").containsEntry("status", "ORDERED")
                .containsEntry("prescription_item_id", null).containsEntry("created_by", vet)
                .containsEntry("done_by", null).containsEntry("done_at", null).containsEntry("done_note", null)
                .containsEntry("cancel_reason", null);
        assertThat(((Timestamp) row.get("scheduled_at")).toInstant())
                .isEqualTo(Instant.parse("2101-03-14T05:00:00.123456Z"));
        assertThat(orderRows(a.id())).isEqualTo(1);
    }

    @Test
    void aMedicationOrderLinksAClinicPrescriptionItemOfTheVisit() {
        long item = data.clinicPrescriptionItem(a.visit(), vet);

        ResponseEntity<Map<String, Object>> response = createOrder(token, a.id(), medication(item));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        assertThat(dataOf(response)).containsEntry("orderType", "MEDICATION").containsEntry("prescriptionItemId",
                (int) item);
        assertThat(jdbc.queryForObject("SELECT prescription_item_id FROM admission_orders WHERE id = ?", Long.class,
                idOf(dataOf(response), "admissionOrderId"))).isEqualTo(item);
    }

    @Test
    void severalOrdersMayShareOneMedicationLine() {
        long item = data.clinicPrescriptionItem(a.visit(), vet);

        newMedication(token, a.id(), item);
        newMedication(token, a.id(), item);

        assertThat(orderRows(a.id())).isEqualTo(2);
    }

    @Test
    void anExternalPurchaseLineIsRefusedForAMedicationOrder() {
        long external = data.prescriptionItem(a.visit());

        assertRule(createOrder(token, a.id(), medication(external)), "BR-LT-15");

        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void aMedicationOrderNeedsALineOfTheSameVisit() {
        long otherPet = data.pet(customer);
        long otherVisit = data.visit(branch, customer, otherPet, vet);
        long foreign = data.clinicPrescriptionItem(otherVisit, vet);

        assertRule(createOrder(token, a.id(), medication(foreign)), "BR-LT-15");
        assertRule(createOrder(token, a.id(), medication(987_654_321L)), "BR-LT-15");
        assertRule(createOrder(token, a.id(),
                "{'orderType':'MEDICATION','content':'Tiêm','scheduledAt':'" + SCHEDULED + "'}"), "BR-LT-15");

        assertThat(orderRows(a.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEEDING", "MONITORING", "PROCEDURE", "OTHER"})
    void aNonMedicationOrderMayNotCarryAPrescriptionLine(String type) {
        long item = data.clinicPrescriptionItem(a.visit(), vet);

        assertRule(createOrder(token, a.id(), "{'orderType':'" + type + "','content':'Nội dung','scheduledAt':'"
                + SCHEDULED + "','prescriptionItemId':" + item + "}"), "BR-LT-15");

        assertThat(orderRows(a.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEEDING", "MONITORING", "PROCEDURE", "OTHER"})
    void everyNonMedicationTypeIsAccepted(String type) {
        ResponseEntity<Map<String, Object>> response = createOrder(token, a.id(),
                "{'orderType':'" + type + "','content':'Nội dung','scheduledAt':'" + SCHEDULED + "'}");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        assertThat(dataOf(response)).containsEntry("orderType", type);
    }

    @Test
    void theScheduledTimeIsNotLimitedSoPastAndFarFutureOrdersAreAccepted() {
        assertThat(createOrder(token, a.id(), "{'orderType':'FEEDING','content':'Đã làm trước','scheduledAt':"
                + "'2000-01-01T00:00:00Z'}").getStatusCode().value()).isEqualTo(201);
        assertThat(createOrder(token, a.id(), "{'orderType':'FEEDING','content':'Xa','scheduledAt':"
                + "'2999-01-01T00:00:00Z'}").getStatusCode().value()).isEqualTo(201);
        assertThat(orderRows(a.id())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "'   '", "'\\t\\n'", "'　'", "'\\u3000 \\u2003'"})
    void blankContentIs400BrLt15AndWritesNothing(String content) {
        assertRule(createOrder(token, a.id(), "{'orderType':'FEEDING','content':" + content + ",'scheduledAt':'"
                + SCHEDULED + "'}"), "BR-LT-15");

        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void anAdmissionThatIsNoLongerAdmittedIs409EvenWhenTheBodyBreaksEveryRule() {
        long item = data.prescriptionItem(a.visit());
        assertThat(call(HttpMethod.POST, ADMISSIONS + "/" + a.id() + "/discharge", token,
                "{'outcome':'HOME','summary':'Cho về'}").getStatusCode().value()).isEqualTo(200);

        // nội dung rỗng, thuốc mua ngoài: nếu 400 đứng trước thì sẽ ra BR-LT-15
        assertStatus(createOrder(token, a.id(), "{'orderType':'MEDICATION','content':'  ','scheduledAt':'" + SCHEDULED
                + "','prescriptionItemId':" + item + "}"), 409, "INVALID_STATE_TRANSITION");
        assertStatus(createOrder(token, a.id(), "{'orderType':'MEDICATION','content':'Tiêm','scheduledAt':'"
                + SCHEDULED + "'}"), 409, "INVALID_STATE_TRANSITION");

        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void anUnknownAdmissionIs404() {
        assertStatus(createOrder(token, 987_654_321L, feeding("x")), 404, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- #6 danh sách

    @Test
    void listOrdersSortsByScheduledTimeThenIdAndFiltersByStatus() {
        long late = idOf(dataOf(createOrder(token, a.id(), "{'orderType':'FEEDING','content':'Trưa','scheduledAt':"
                + "'2101-03-14T06:00:00Z'}")), "admissionOrderId");
        long early = idOf(dataOf(createOrder(token, a.id(), "{'orderType':'OTHER','content':'Sáng','scheduledAt':"
                + "'2101-03-14T01:00:00Z'}")), "admissionOrderId");
        long tie = idOf(dataOf(createOrder(token, a.id(), "{'orderType':'FEEDING','content':'Trưa 2','scheduledAt':"
                + "'2101-03-14T06:00:00Z'}")), "admissionOrderId");
        assertThat(cancel(token, tie, "Không cần").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> all = get(ordersPath(a.id()), token);
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(listOf(all)).extracting(o -> idOf(o, "admissionOrderId")).containsExactly(early, late, tie);
        assertThat(listOf(all)).allSatisfy(o -> assertThat(o.keySet()).containsExactlyInAnyOrderElementsOf(ORDER_KEYS));

        assertThat(listOf(get(ordersPath(a.id()) + "?status=ORDERED", token)))
                .extracting(o -> idOf(o, "admissionOrderId")).containsExactly(early, late);
        assertThat(listOf(get(ordersPath(a.id()) + "?status=CANCELLED", token)))
                .extracting(o -> idOf(o, "admissionOrderId")).containsExactly(tie);
        assertThat(listOf(get(ordersPath(a.id()) + "?status=DONE", token))).isEmpty();
    }

    @Test
    void aDischargedAdmissionStillListsItsOrdersAndAnUnknownStatusOrIdIs400() {
        long order = newFeeding(token, a.id());
        assertThat(complete(token, order, "Xong").getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, ADMISSIONS + "/" + a.id() + "/discharge", token,
                "{'outcome':'HOME','summary':'Cho về'}").getStatusCode().value()).isEqualTo(200);

        assertThat(listOf(get(ordersPath(a.id()), token))).hasSize(1);
        assertStatus(get(ordersPath(a.id()) + "?status=FOO", token), 400, "MALFORMED_REQUEST");
        assertStatus(get("/api/admissions/abc/orders", token), 400, "MALFORMED_REQUEST");
        assertStatus(get(ordersPath(987_654_321L), token), 404, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- #7 thực hiện

    @Test
    void completeStoresWhoWhenAndTheStrippedNoteAndTheResponseMatches() {
        long order = newFeeding(token, a.id());

        ResponseEntity<Map<String, Object>> response = complete(token, order, "  Đã cho ăn hết  ");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(ORDER_KEYS);
        assertThat(body).containsEntry("status", "DONE").containsEntry("doneNote", "Đã cho ăn hết")
                .containsEntry("cancelReason", null);
        assertThat(Instant.parse((String) body.get("doneAt"))).isEqualTo(Instant.parse("2101-03-14T02:00:00.123456Z"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, done_by, done_at, done_note, cancel_reason FROM admission_orders WHERE id = ?", order);
        assertThat(row).containsEntry("status", "DONE").containsEntry("done_by", vet)
                .containsEntry("done_note", "Đã cho ăn hết").containsEntry("cancel_reason", null);
        assertThat(((Timestamp) row.get("done_at")).toInstant()).isEqualTo(Instant.parse("2101-03-14T02:00:00.123456Z"));
    }

    @Test
    void anOrderThatIsNotOrderedIs409OnBothEdgesAndStaysUntouched() {
        long done = newFeeding(token, a.id());
        complete(token, done, "Xong");
        long cancelled = newFeeding(token, a.id());
        cancel(token, cancelled, "Hủy");
        String doneBefore = orderFootprint(done);
        String cancelledBefore = orderFootprint(cancelled);

        for (long order : new long[] {done, cancelled}) {
            assertStatus(complete(token, order, "Lại"), 409, "INVALID_STATE_TRANSITION");
            assertStatus(cancel(token, order, "Lại"), 409, "INVALID_STATE_TRANSITION");
        }

        assertThat(orderFootprint(done)).isEqualTo(doneBefore);
        assertThat(orderFootprint(cancelled)).isEqualTo(cancelledBefore);
    }

    @Test
    void aFinishedOrderIs409EvenWhenTheNoteOrReasonIsBlank() {
        long done = newFeeding(token, a.id());
        complete(token, done, "Xong");

        assertStatus(complete(token, done, "   "), 409, "INVALID_STATE_TRANSITION");
        assertStatus(cancel(token, done, "   "), 409, "INVALID_STATE_TRANSITION");
        assertThat(orderStatus(done)).isEqualTo("DONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "'   '", "'\\t\\n'", "'　'", "'\\u3000 \\u2003'"})
    void aBlankNoteOrReasonIs400BrLt15AndTheOrderStaysOrdered(String blank) {
        long order = newFeeding(token, a.id());
        String before = orderFootprint(order);

        assertRule(call(HttpMethod.POST, completePath(order), token, "{'note':" + blank + "}"), "BR-LT-15");
        assertRule(call(HttpMethod.POST, cancelPath(order), token, "{'reason':" + blank + "}"), "BR-LT-15");

        assertThat(orderFootprint(order)).isEqualTo(before);
        assertThat(orderStatus(order)).isEqualTo("ORDERED");
    }

    @Test
    void anUnknownOrderIs404OnBothEdges() {
        assertStatus(complete(token, 987_654_321L, "x"), 404, "RESOURCE_NOT_FOUND");
        assertStatus(cancel(token, 987_654_321L, "x"), 404, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- #8 hủy

    @Test
    void cancelStoresTheStrippedReasonAndLeavesTheDoneColumnsEmpty() {
        long order = newFeeding(token, a.id());

        ResponseEntity<Map<String, Object>> response = cancel(token, order, "  Thú đã ăn  ");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("status", "CANCELLED").containsEntry("cancelReason", "Thú đã ăn")
                .containsEntry("doneAt", null).containsEntry("doneNote", null);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, done_by, done_at, done_note, cancel_reason FROM admission_orders WHERE id = ?", order);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "Thú đã ăn")
                .containsEntry("done_by", null).containsEntry("done_at", null).containsEntry("done_note", null);
    }

    // ---------------------------------------------------------------- liên quan tới xuất viện (BR-LT-14)

    @Test
    void dischargeIsBlockedByAnOrderedOrderAndFreedByDoneOrCancelled() {
        long first = newFeeding(token, a.id());
        long second = newFeeding(token, a.id());
        String discharge = ADMISSIONS + "/" + a.id() + "/discharge";
        String body = "{'outcome':'HOME','summary':'Cho về'}";

        assertRule(call(HttpMethod.POST, discharge, token, body), "BR-LT-14");
        assertThat(complete(token, first, "Xong").getStatusCode().value()).isEqualTo(200);
        assertRule(call(HttpMethod.POST, discharge, token, body), "BR-LT-14");
        assertThat(cancel(token, second, "Không cần").getStatusCode().value()).isEqualTo(200);

        assertThat(call(HttpMethod.POST, discharge, token, body).getStatusCode().value()).isEqualTo(200);
        assertThat(admissionStatus(a.id())).isEqualTo("DISCHARGED");
    }

    // ---------------------------------------------------------------- ma trận đầu vào (errorCode đúng)

    @Test
    void malformedAndInvalidBodiesOfCreateAreTheRightErrorCodeAndWriteNothing() {
        String path = ordersPath(a.id());
        String ok = "'orderType':'FEEDING','content':'x','scheduledAt':'" + SCHEDULED + "'";

        assertStatus(call(HttpMethod.POST, path, token, null), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{" + ok), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':'GONE','content':'x','scheduledAt':'"
                + SCHEDULED + "'}"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':'FEEDING','content':['x'],'scheduledAt':'"
                + SCHEDULED + "'}"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':'FEEDING','content':'x','scheduledAt':'hôm nay'}"),
                400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{" + ok + ",'prescriptionItemId':1.5}"), 400,
                "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{" + ok + ",'prescriptionItemId':'abc'}"), 400,
                "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, "/api/admissions/abc/orders", token, "{" + ok + "}"), 400,
                "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, path, token, "{'content':'x','scheduledAt':'" + SCHEDULED + "'}"), 400,
                "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':'FEEDING','scheduledAt':'" + SCHEDULED + "'}"),
                400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':'FEEDING','content':'x'}"), 400,
                "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{'orderType':null,'content':null,'scheduledAt':null}"), 400,
                "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{" + ok + ",'prescriptionItemId':0}"), 400,
                "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, path, token, "{" + ok + ",'prescriptionItemId':-3}"), 400,
                "VALIDATION_FAILED");
        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void validationNamesTheFieldAndTheLimitsAre500And300OnTheRawText() {
        String path = ordersPath(a.id());

        ResponseEntity<Map<String, Object>> missing = call(HttpMethod.POST, path, token, "{'scheduledAt':'"
                + SCHEDULED + "'}");
        assertStatus(missing, 400, "VALIDATION_FAILED");
        assertThat(message(missing)).contains("orderType").contains("content");

        assertStatus(createOrder(token, a.id(), feeding("a".repeat(501))), 400, "VALIDATION_FAILED");
        assertThat(orderRows(a.id())).isZero();
        assertThat(createOrder(token, a.id(), feeding("a".repeat(500))).getStatusCode().value()).isEqualTo(201);

        long order = newFeeding(token, a.id());
        assertStatus(complete(token, order, "a".repeat(301)), 400, "VALIDATION_FAILED");
        assertStatus(cancel(token, order, "a".repeat(301)), 400, "VALIDATION_FAILED");
        assertThat(orderStatus(order)).isEqualTo("ORDERED");
        assertThat(complete(token, order, "a".repeat(300)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void malformedAndInvalidBodiesOfCompleteAndCancelAreTheRightErrorCodeAndChangeNothing() {
        long order = newFeeding(token, a.id());
        String before = orderFootprint(order);

        for (String path : new String[] {completePath(order), cancelPath(order)}) {
            String field = path.endsWith("/complete") ? "note" : "reason";
            assertStatus(call(HttpMethod.POST, path, token, null), 400, "MALFORMED_REQUEST");
            assertStatus(call(HttpMethod.POST, path, token, "{'" + field + "':'x'"), 400, "MALFORMED_REQUEST");
            assertStatus(call(HttpMethod.POST, path, token, "{'" + field + "':['x']}"), 400, "MALFORMED_REQUEST");
            assertStatus(call(HttpMethod.POST, path, token, "{}"), 400, "VALIDATION_FAILED");
            assertStatus(call(HttpMethod.POST, path, token, "{'" + field + "':null}"), 400, "VALIDATION_FAILED");
        }
        assertStatus(call(HttpMethod.POST, "/api/admission-orders/abc/complete", token, "{'note':'x'}"), 400,
                "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, "/api/admission-orders/abc/cancel", token, "{'reason':'x'}"), 400,
                "MALFORMED_REQUEST");
        assertThat(orderFootprint(order)).isEqualTo(before);
    }

    @Test
    void aBodyThatIsNotJsonIs415AndWritesNothing() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setBearerAuth(token);

        ResponseEntity<Map<String, Object>> response = http.exchange(ordersPath(a.id()), HttpMethod.POST,
                new HttpEntity<>("orderType=FEEDING", headers), MAP);

        assertStatus(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void theErrorMessagesAreVietnameseAndNameTheRule() {
        assertThat(message(createOrder(token, a.id(), "{'orderType':'MEDICATION','content':'x','scheduledAt':'"
                + SCHEDULED + "'}"))).contains("dòng đơn thuốc").endsWith("(BR-LT-15)");
        assertThat(message(complete(token, newFeeding(token, a.id()), "  "))).contains("ghi chú").endsWith("(BR-LT-15)");
        assertThat(message(cancel(token, newFeeding(token, a.id()), "  "))).contains("lý do").endsWith("(BR-LT-15)");
    }
}
