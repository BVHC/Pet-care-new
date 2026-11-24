package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.math.BigDecimal;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Theo dõi nội trú (inpatient-v1 #9–#10; BR-LT-16; docs/adr/0149 mục 31–36) trên Postgres thật:
 * <ul>
 * <li>đường đúng: response khớp từng cột DB; {@code createdAt} của #9 = cột DB = giá trị #10 (đồng hồ có nano giây, cột
 * chỉ giữ micro giây); người ghi là người gọi dù body có {@code recordedBy}; mọi VET / CARETAKER của chi nhánh ghi được;</li>
 * <li>cân nặng vào hồ sơ cân với nguồn {@code VET} gắn Visit của ca, kể cả khi CARETAKER ghi;</li>
 * <li>thông báo (outbox): đúng mẫu / kênh / người nhận / đủ biến bắt buộc cho từng loại khách; VET phụ trách không tự nhận;
 * mục bình thường không gửi gì;</li>
 * <li>ma trận đầu vào với đúng {@code errorCode}, số mũ cực lớn trả lời nhanh, 409 trước 400; sau mỗi lời gọi bị từ chối
 * DB không đổi;</li>
 * <li>đọc #10: mới nhất trước, phân trang, tên người ghi, đọc được ca đã xuất viện.</li>
 * </ul>
 * Phân quyền ở {@code AdmissionObservationSecurityIT}, khóa chờ ở {@code AdmissionObservationLockIT}, rollback ở
 * {@code AdmissionObservationRollbackIT}. Era <b>năm 2105</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionObservationIT.TestBeans.class})
class AdmissionObservationIT extends AdmissionObservationItBase {

    /** 09:00 ngày 14/3/2105 giờ Việt Nam, <b>có nano giây</b> (cột chỉ giữ micro giây). */
    private static final Instant NOW = Instant.parse("2105-03-14T02:00:00.123456789Z");

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
    private long otherVet;
    private long caretaker;
    private long service;
    private long customerAccount;
    private long customer;
    private String vetToken;
    private String caretakerToken;
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        otherVet = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        service = enabledInpatientService(branch, 250_000L);
        long[] online = data.onlineCustomer("Nguyễn Văn An", "ACTIVE");
        customerAccount = online[0];
        customer = online[1];
        vetToken = token(vet);
        caretakerToken = token(caretaker);
        a = admitFromVisit(branch, customer, service, vet, vetToken);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- #9 đường đúng

    @Test
    void anObservationIsStoredAndTheResponseMatchesEveryColumn() {
        ResponseEntity<Map<String, Object>> response = record(caretakerToken, a.id(), body(false, "5.555"));

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(OBSERVATION_KEYS);
        assertThat(body).containsEntry("admissionId", (int) a.id()).containsEntry("heartRate", 120)
                .containsEntry("respiratoryRate", 30).containsEntry("appetite", "REDUCED")
                .containsEntry("note", "Ăn ít").containsEntry("abnormal", false);
        assertThat(decimal(body.get("temperatureC"))).isEqualByComparingTo("38.6");
        assertThat(decimal(body.get("weightKg"))).isEqualByComparingTo("5.56");
        assertThat((String) body.get("recordedByName")).isEqualTo(
                jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class, caretaker));

        long id = idOf(body, "observationId");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT admission_id, recorded_by, temperature_c, heart_rate, respiratory_rate, weight_kg, appetite, note,
                       abnormal, created_at
                FROM admission_observations WHERE id = ?""", id);
        assertThat(row).containsEntry("admission_id", a.id()).containsEntry("recorded_by", caretaker)
                .containsEntry("appetite", "REDUCED").containsEntry("note", "Ăn ít").containsEntry("abnormal", false);
        assertThat(((Number) row.get("heart_rate")).intValue()).isEqualTo(120);
        assertThat(((Number) row.get("respiratory_rate")).intValue()).isEqualTo(30);
        assertThat((BigDecimal) row.get("temperature_c")).isEqualByComparingTo("38.6");
        assertThat((BigDecimal) row.get("weight_kg")).isEqualByComparingTo("5.56");
        assertThat(observationRows(a.id())).isEqualTo(1);
    }

    @Test
    void createdAtOfTheResponseIsTheStoredMicrosecondValueAndEqualsWhatTheListReturns() {
        ResponseEntity<Map<String, Object>> response = record(vetToken, a.id(), body(false, null));
        long id = idOf(dataOf(response), "observationId");

        Instant created = Instant.parse((String) dataOf(response).get("createdAt"));
        Instant stored = jdbc.queryForObject("SELECT created_at FROM admission_observations WHERE id = ?",
                Timestamp.class, id).toInstant();
        assertThat(created).isEqualTo(stored);
        // đồng hồ có nano giây: nếu map thẳng từ entity thì response lệch cột DB
        assertThat(created.getNano() % 1000).as("không còn phần dưới micro giây").isZero();

        Map<String, Object> listed = contentOf(listObservations(vetToken, a.id(), "")).get(0);
        assertThat(Instant.parse((String) listed.get("createdAt"))).isEqualTo(created);
    }

    @Test
    void aWeightIsAlsoWrittenToThePetWeightHistoryWithTheVetSourceAndTheVisitOfTheCase() {
        record(caretakerToken, a.id(), body(false, "5.555"));

        Map<String, Object> weight = jdbc.queryForMap("""
                SELECT weight_kg, source, visit_id, boarding_booking_id, recorded_by
                FROM weight_records WHERE pet_id = ?""", a.pet());
        assertThat((BigDecimal) weight.get("weight_kg")).isEqualByComparingTo("5.56");
        // người ghi là CARETAKER nhưng nguồn vẫn là VET gắn Visit của ca (ADR-0149 mục 31)
        assertThat(weight).containsEntry("source", "VET").containsEntry("visit_id", a.visit())
                .containsEntry("boarding_booking_id", null).containsEntry("recorded_by", caretaker);
        assertThat(weightRows(a.pet())).isEqualTo(1);
    }

    @Test
    void withoutAWeightNoWeightRecordIsWritten() {
        record(vetToken, a.id(), body(false, null));
        record(vetToken, a.id(), noteOnly("Nằm yên", false));

        assertThat(weightRows(a.pet())).isZero();
        assertThat(observationRows(a.id())).isEqualTo(2);
    }

    @Test
    void anyVetOrCaretakerOfTheBranchMayRecordNotOnlyTheAssignee() {
        assertThat(record(token(otherVet), a.id(), body(false, null)).getStatusCode().value()).isEqualTo(201);
        assertThat(record(caretakerToken, a.id(), body(false, null)).getStatusCode().value()).isEqualTo(201);
        assertThat(record(vetToken, a.id(), body(false, null)).getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT recorded_by FROM admission_observations WHERE admission_id = ? ORDER BY id",
                Long.class, a.id())).containsExactly(otherVet, caretaker, vet);
    }

    @Test
    void theRecorderIsAlwaysTheCallerWhateverTheBodySays() {
        ResponseEntity<Map<String, Object>> response = record(caretakerToken, a.id(),
                "{'note':'Ổn','abnormal':false,'recordedBy':" + vet + ",'admissionId':999999,'createdAt':'2001-01-01T00:00:00Z'}");

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT recorded_by FROM admission_observations WHERE id = ?", Long.class,
                idOf(dataOf(response), "observationId"))).isEqualTo(caretaker);
        assertThat(observationRows(a.id())).isEqualTo(1);
    }

    @Test
    void aNoteIsStrippedAndASingleMeasurementOrAnAppetiteAloneIsEnough() {
        ResponseEntity<Map<String, Object>> stripped = record(vetToken, a.id(), noteOnly("  Nằm yên  ", false));
        assertThat(dataOf(stripped)).containsEntry("note", "Nằm yên").containsEntry("heartRate", null)
                .containsEntry("temperatureC", null).containsEntry("weightKg", null).containsEntry("appetite", null);

        assertThat(record(vetToken, a.id(), "{'appetite':'NONE','abnormal':false}").getStatusCode().value())
                .isEqualTo(201);
        assertThat(record(vetToken, a.id(), "{'heartRate':90,'abnormal':false}").getStatusCode().value()).isEqualTo(201);
        assertThat(record(vetToken, a.id(), "{'temperatureC':0,'abnormal':false}").getStatusCode().value())
                .isEqualTo(201);
    }

    // ---------------------------------------------------------------- thông báo (outbox)

    @Test
    void anAbnormalObservationOfAnAccountCustomerQueuesInAppAndEmailForThemAndAnInAppForTheAssignedVet() {
        long id = newObservation(caretakerToken, a.id(), body(true, null));

        List<Map<String, Object>> rows = outbox(a.id());
        assertThat(rows).hasSize(3);
        Map<String, Object> app = rows.get(0);
        assertThat(app).containsEntry("template_code", "INPATIENT_OBSERVATION_ABNORMAL_APP")
                .containsEntry("channel", "IN_APP").containsEntry("recipient_account_id", customerAccount)
                .containsEntry("link_url", "/pets");
        Map<String, Object> email = rows.get(1);
        assertThat(email).containsEntry("template_code", "INPATIENT_OBSERVATION_ABNORMAL")
                .containsEntry("channel", "EMAIL").containsEntry("recipient_account_id", null).containsEntry(
                        "recipient_email", jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class,
                                customerAccount));
        Map<String, Object> staff = rows.get(2);
        assertThat(staff).containsEntry("template_code", "INPATIENT_OBSERVATION_ABNORMAL_APP")
                .containsEntry("channel", "IN_APP").containsEntry("recipient_account_id", vet)
                .containsEntry("link_url", "/admin/inpatient");
        assertThat(id).isPositive();
    }

    @Test
    void everyQueuedRowCarriesAllRequiredVariablesOfItsTemplateWithTheRightValues() {
        record(caretakerToken, a.id(), body(true, null));

        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String code = jdbc.queryForObject("SELECT code FROM admissions WHERE id = ?", String.class, a.id());
        for (Map<String, Object> row : outbox(a.id())) {
            assertThat(row).containsEntry("ten_khach", "Nguyễn Văn An").containsEntry("ten_thu_cung", "Milu")
                    .containsEntry("chi_nhanh", branchName).containsEntry("ma_noi_tru", code)
                    // 02:00 UTC = 09:00 giờ Việt Nam cùng ngày
                    .containsEntry("ngay_ghi", "14/03/2105");
            List<String> required = jdbc.queryForList("""
                    SELECT jsonb_array_elements_text(required_vars) FROM notification_templates WHERE code = ?""",
                    String.class, (String) row.get("template_code"));
            assertThat(required).isNotEmpty();
            for (String variable : required) {
                assertThat(jdbc.queryForObject("""
                        SELECT payload ->> ? FROM notification_outbox WHERE payload ->> 'ma_noi_tru' = ? AND template_code = ?
                        AND channel = ? LIMIT 1""", String.class, variable, code, row.get("template_code"),
                        row.get("channel"))).as(variable).isNotBlank();
            }
        }
    }

    @Test
    void aNormalObservationQueuesNothing() {
        record(caretakerToken, a.id(), body(false, "5.5"));

        assertThat(outboxRows(a.id())).isZero();
    }

    @Test
    void theAssignedVetRecordingAnAbnormalObservationDoesNotNotifyThemselvesButTheCustomerStillHearsOfIt() {
        record(vetToken, a.id(), body(true, null));

        List<Map<String, Object>> rows = outbox(a.id());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("recipient_account_id")).doesNotContain(vet);
        assertThat(rows).extracting(r -> r.get("channel")).containsExactly("IN_APP", "EMAIL");
    }

    @Test
    void anotherVetRecordingAnAbnormalObservationStillNotifiesTheAssignedVet() {
        record(token(otherVet), a.id(), body(true, null));

        assertThat(outbox(a.id())).extracting(r -> r.get("recipient_account_id")).contains(vet);
    }

    @Test
    void aCounterCustomerWithAnEmailGetsOnlyAnEmail() {
        Admitted counter = admitFromVisit(branch, data.counterCustomer("Trần Thị Bích", "bich@example.com"), service,
                vet, vetToken);

        record(caretakerToken, counter.id(), body(true, null));

        List<Map<String, Object>> rows = outbox(counter.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("channel", "EMAIL").containsEntry("recipient_email", "bich@example.com")
                .containsEntry("template_code", "INPATIENT_OBSERVATION_ABNORMAL").containsEntry("ten_khach", "Trần Thị Bích");
        assertThat(rows.get(1)).containsEntry("recipient_account_id", vet);
    }

    @Test
    void aCounterCustomerWithoutAnEmailGetsNothingButTheAssignedVetIsStillTold() {
        Admitted counter = admitFromVisit(branch, data.counterCustomer("Lê Văn Cường", null), service, vet, vetToken);

        assertThat(record(caretakerToken, counter.id(), body(true, null)).getStatusCode().value()).isEqualTo(201);

        List<Map<String, Object>> rows = outbox(counter.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("recipient_account_id", vet).containsEntry("channel", "IN_APP");
    }

    // ---------------------------------------------------------------- ma trận đầu vào

    @Test
    void invalidBodiesAreRejectedWithTheRightErrorCodeAndWriteNothing() {
        String before = footprintOf(a);
        String longNote = "x".repeat(501);
        String[][] cases = {
                // hình thức → 400 VALIDATION_FAILED / MALFORMED_REQUEST (Bean Validation, chạy trước @PreAuthorize)
                {"{}", "VALIDATION_FAILED"},
                {"{'note':'Ổn'}", "VALIDATION_FAILED"},
                {"{'note':'Ổn','abnormal':null}", "VALIDATION_FAILED"},
                {"{'note':'" + longNote + "','abnormal':false}", "VALIDATION_FAILED"},
                {"{'heartRate':1.5,'abnormal':false}", "MALFORMED_REQUEST"},
                {"{'heartRate':'abc','abnormal':false}", "MALFORMED_REQUEST"},
                {"{'appetite':'HUNGRY','abnormal':false}", "MALFORMED_REQUEST"},
                {"{'weightKg':'abc','abnormal':false}", "MALFORMED_REQUEST"},
                {"{'note':'Ổn','abnormal':", "MALFORMED_REQUEST"},
                // rule → 400 BR-LT-16
                {"{'abnormal':false}", "BR-LT-16"},
                {"{'note':'   ','abnormal':true}", "BR-LT-16"},
                {"{'note':'\\u3000','abnormal':true}", "BR-LT-16"},
                {"{'temperatureC':1000,'abnormal':false}", "BR-LT-16"},
                {"{'temperatureC':-1000,'abnormal':false}", "BR-LT-16"},
                {"{'heartRate':0,'abnormal':false}", "BR-LT-16"},
                {"{'heartRate':32768,'abnormal':false}", "BR-LT-16"},
                {"{'respiratoryRate':-1,'abnormal':false}", "BR-LT-16"},
                {"{'weightKg':0,'abnormal':false}", "BR-LT-16"},
                {"{'weightKg':-1,'abnormal':false}", "BR-LT-16"},
                {"{'weightKg':0.004,'abnormal':false}", "BR-LT-16"},
                {"{'weightKg':10000,'abnormal':false}", "BR-LT-16"}};
        for (String[] c : cases) {
            ResponseEntity<Map<String, Object>> response = record(vetToken, a.id(), c[0]);
            if (c[1].startsWith("BR-")) {
                assertRule(response, c[1]);
            } else {
                assertStatus(response, 400, c[1]);
            }
            assertThat(footprintOf(a)).as(c[0]).isEqualTo(before);
        }
    }

    @Test
    void theBoundaryValuesOfTheColumnsAreAccepted() {
        for (String json : new String[] {"{'temperatureC':999.9,'abnormal':false}",
                "{'temperatureC':-999.9,'abnormal':false}", "{'heartRate':32767,'abnormal':false}",
                "{'respiratoryRate':1,'abnormal':false}", "{'weightKg':0.005,'abnormal':false}",
                "{'weightKg':9999.994,'abnormal':false}"}) {
            ResponseEntity<Map<String, Object>> response = record(vetToken, a.id(), json);
            assertThat(response.getStatusCode().value()).as(json + " " + response.getBody()).isEqualTo(201);
        }
        assertThat(jdbc.queryForList("SELECT weight_kg FROM weight_records WHERE pet_id = ? ORDER BY id",
                BigDecimal.class, a.pet())).hasSize(2).first().satisfies(w -> assertThat(w).isEqualByComparingTo("0.01"));
    }

    /**
     * {@code 1e-100000000} là JSON ngắn nhưng {@code setScale} của nó chạy hàng giây hoặc không bao giờ xong (D081 ở
     * customer): giới hạn phải so bằng {@code compareTo} trước, nên trả lời ngay.
     */
    @Test
    void anAbsurdExponentIsAnsweredQuicklyAndNeverReachesTheWeightHistory() {
        assertTimeout(Duration.ofSeconds(10), () -> {
            assertRule(record(vetToken, a.id(), "{'weightKg':1e-100000000,'abnormal':false}"), "BR-LT-16");
            assertRule(record(vetToken, a.id(), "{'weightKg':1e999999999,'abnormal':false}"), "BR-LT-16");
            assertRule(record(vetToken, a.id(), "{'temperatureC':1e999999999,'abnormal':false}"), "BR-LT-16");
            // nhiệt độ gần 0 hợp lệ và làm tròn về 0.0
            ResponseEntity<Map<String, Object>> tiny = record(vetToken, a.id(),
                    "{'temperatureC':1e-100000000,'abnormal':false}");
            assertThat(tiny.getStatusCode().value()).as(String.valueOf(tiny.getBody())).isEqualTo(201);
            assertThat(decimal(dataOf(tiny).get("temperatureC"))).isEqualByComparingTo("0.0");
        });
        assertThat(weightRows(a.pet())).isZero();
        assertThat(observationRows(a.id())).isEqualTo(1);
    }

    @Test
    void aMissingAdmissionIs404AndNothingIsWritten() {
        assertStatus(record(vetToken, 999_999_999L, body(true, "5")), 404, "RESOURCE_NOT_FOUND");
        assertStatus(listObservations(vetToken, 999_999_999L, ""), 404, "RESOURCE_NOT_FOUND");
        assertThat(footprintOf(a)).isEqualTo("0/0/0");
    }

    // ---------------------------------------------------------------- 409 trước 400

    @Test
    void aDischargedAdmissionIs409EvenWhenTheValuesAreAlsoInvalidAndNothingIsWritten() {
        long first = newObservation(vetToken, a.id(), body(false, "5"));
        assertThat(call(HttpMethod.POST, ADMISSIONS + "/" + a.id() + "/discharge", vetToken,
                "{'outcome':'HOME','summary':'Ổn'}").getStatusCode().value()).isEqualTo(200);
        String before = footprintOf(a);

        // rule sai (không chỉ số nào) mà ca đã xuất viện: 409 đứng trước 400 (khuôn y lệnh, ADR-0149 mục 26)
        assertStatus(record(vetToken, a.id(), "{'abnormal':false}"), 409, "INVALID_STATE_TRANSITION");
        assertStatus(record(vetToken, a.id(), "{'weightKg':-1,'abnormal':true}"), 409, "INVALID_STATE_TRANSITION");
        assertStatus(record(vetToken, a.id(), body(true, "5")), 409, "INVALID_STATE_TRANSITION");
        // hình thức sai vẫn là 400 (Bean Validation chạy trước service)
        assertStatus(record(vetToken, a.id(), "{}"), 400, "VALIDATION_FAILED");

        assertThat(footprintOf(a)).isEqualTo(before);
        assertThat(first).isPositive();
    }

    // ---------------------------------------------------------------- #10 đọc

    @Test
    void theListIsNewestFirstPagedAndCarriesTheRecorderNames() {
        long one = newObservation(vetToken, a.id(), noteOnly("Một", false));
        clock.advance(Duration.ofSeconds(60));
        long two = newObservation(caretakerToken, a.id(), noteOnly("Hai", false));
        clock.advance(Duration.ofSeconds(60));
        long three = newObservation(vetToken, a.id(), noteOnly("Ba", false));

        ResponseEntity<Map<String, Object>> all = listObservations(vetToken, a.id(), "");
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> content = contentOf(all);
        assertThat(content).extracting(m -> idOf(m, "observationId")).containsExactly(three, two, one);
        assertThat(content.get(0).keySet()).containsExactlyInAnyOrderElementsOf(OBSERVATION_KEYS);
        assertThat(content).extracting(m -> m.get("recordedByName")).containsExactly(
                name(vet), name(caretaker), name(vet));
        assertThat(((Number) dataOf(all).get("totalElements")).intValue()).isEqualTo(3);

        ResponseEntity<Map<String, Object>> secondPage = listObservations(vetToken, a.id(), "?page=1&size=2");
        assertThat(contentOf(secondPage)).extracting(m -> idOf(m, "observationId")).containsExactly(one);
        assertThat(contentOf(listObservations(vetToken, a.id(), "?page=-5&size=0"))).hasSize(1);
        assertThat(contentOf(listObservations(vetToken, a.id(), "?size=500"))).hasSize(3);
    }

    @Test
    void theListOfAnAdmissionWithNoObservationsIsEmptyAndItStaysReadableAfterDischarge() {
        assertThat(contentOf(listObservations(vetToken, a.id(), ""))).isEmpty();
        long id = newObservation(vetToken, a.id(), body(false, null));
        call(HttpMethod.POST, ADMISSIONS + "/" + a.id() + "/discharge", vetToken, "{'outcome':'HOME','summary':'Ổn'}");

        ResponseEntity<Map<String, Object>> after = listObservations(token(data.staff("RECEPTIONIST", branch)), a.id(), "");

        assertThat(after.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(after)).extracting(m -> idOf(m, "observationId")).containsExactly(id);
    }

    @Test
    void aNonNumericPageIsMalformed() {
        assertStatus(listObservations(vetToken, a.id(), "?page=abc"), 400, "MALFORMED_REQUEST");
        assertStatus(get(ADMISSIONS + "/abc/observations", vetToken), 400, "MALFORMED_REQUEST");
    }

    private String name(long staffId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class, staffId);
    }
}
