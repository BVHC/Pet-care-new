package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã khóa và đã ghi</b> trong ghi theo dõi nội trú (inpatient-v1 #9; docs/adr/0149 mục 34): ba bản ghi
 * (mục theo dõi, dòng cân nặng, dòng outbox) nằm trong một transaction nên lỗi ở bất kỳ bước nào cũng hoàn tác cả ba và
 * <b>nhả mọi khóa</b> (ca, thú): lần gọi sau khi hết lỗi thành công. Lỗi ở bước cân nặng và bước outbox được gây bằng
 * trigger DB giới hạn đúng thú / đúng mã ca (không đụng dữ liệu của IT khác). Spy repository đặt <b>trên chính spy</b>,
 * không qua proxy giao dịch (CLAUDE.md, ADR-0149 N3). Era <b>năm 2108</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionObservationRollbackIT.TestBeans.class})
class AdmissionObservationRollbackIT extends AdmissionObservationItBase {

    private static final Instant NOW = Instant.parse("2108-03-14T02:00:00Z");
    private static final String FAIL_FUNCTION = "obs_rollback_it_fail";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private AdmissionObservationRepository observations;

    private long vet;
    private String token;
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        long branch = data.branch();
        vet = data.staff("VET", branch);
        long service = enabledInpatientService(branch, 200_000L);
        long[] online = data.onlineCustomer("Nguyễn Văn An", "ACTIVE");
        token = token(vet);
        a = admitFromVisit(branch, online[1], service, vet, token);
    }

    /** Mockito spy bên trong proxy giao dịch của Spring. */
    private AdmissionObservationRepository spy() {
        return AopTestUtils.getTargetObject(observations);
    }

    @AfterEach
    void tearDown() {
        reset(spy());
        dropTriggers();
        finish();
    }

    @Test
    void aFailureWhileSavingTheObservationWritesNothingElseFreesTheLocksAndARetrySucceeds() {
        doThrow(new DataIntegrityViolationException("save broke")).when(spy())
                .saveAndFlush(any(AdmissionObservation.class));

        assertStatus(record(token, a.id(), body(true, "5")), 409, "CONCURRENCY_CONFLICT");

        assertThat(footprintOf(a)).isEqualTo("0/0/0");
        assertLocksAreFree();
        reset(spy());
        ResponseEntity<Map<String, Object>> retry = record(token, a.id(), body(true, "5"));
        assertThat(retry.getStatusCode().value()).as(String.valueOf(retry.getBody())).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("1/1/2");
    }

    @Test
    void aFailureWhileWritingTheWeightRollsTheObservationBackAndNoNotificationIsQueued() {
        failOn("weight_records", "NEW.pet_id = " + a.pet());
        try {
            ResponseEntity<Map<String, Object>> response = record(token, a.id(), body(true, "5"));

            assertThat(response.getStatusCode().is5xxServerError()).as(String.valueOf(response.getBody())).isTrue();
            assertThat(footprintOf(a)).isEqualTo("0/0/0");
            assertLocksAreFree();
        } finally {
            dropTriggers();
        }
        assertThat(record(token, a.id(), body(true, "5")).getStatusCode().value()).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("1/1/2");
    }

    @Test
    void aFailureWhileQueuingTheNotificationRollsBackTheObservationAndTheWeight() {
        failOn("notification_outbox", "NEW.payload ->> 'ma_noi_tru' = '" + code() + "'");
        try {
            ResponseEntity<Map<String, Object>> response = record(token, a.id(), body(true, "5"));

            assertThat(response.getStatusCode().is5xxServerError()).as(String.valueOf(response.getBody())).isTrue();
            // mục theo dõi và dòng cân đã ghi trước outbox: phải được hoàn tác cùng
            assertThat(footprintOf(a)).isEqualTo("0/0/0");
            assertLocksAreFree();
        } finally {
            dropTriggers();
        }
        assertThat(record(token, a.id(), body(true, "5")).getStatusCode().value()).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("1/1/2");
    }

    @Test
    void aRejectedRequestAlsoReleasesTheAdmissionLock() {
        assertRule(record(token, a.id(), "{'abnormal':false}"), "BR-LT-16");
        assertLocksAreFree();

        newObservation(token, a.id(), body(false, null));
        call(org.springframework.http.HttpMethod.POST, ADMISSIONS + "/" + a.id() + "/discharge", token,
                "{'outcome':'HOME','summary':'Ổn'}");
        assertStatus(record(token, a.id(), body(true, "5")), 409, "INVALID_STATE_TRANSITION");
        assertLocksAreFree();
        assertThat(footprintOf(a)).isEqualTo("1/0/0");
    }

    // ---------------------------------------------------------------- helpers

    private String code() {
        return jdbc.queryForObject("SELECT code FROM admissions WHERE id = ?", String.class, a.id());
    }

    /** Khóa dòng ca và dòng thú lấy được ngay (NOWAIT): nếu request còn giữ thì ném lỗi thay vì treo. */
    private void assertLocksAreFree() {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admissions WHERE id = ? FOR UPDATE NOWAIT", Long.class, a.id());
            jdbc.queryForObject("SELECT id FROM pets WHERE id = ? FOR UPDATE NOWAIT", Long.class, a.pet());
        });
    }

    /** Trigger làm mọi INSERT thỏa {@code condition} ném lỗi, chỉ ảnh hưởng đúng dữ liệu của test này. */
    private void failOn(String table, String condition) {
        jdbc.execute("CREATE OR REPLACE FUNCTION " + FAIL_FUNCTION + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'observation it forced failure'; END $$");
        jdbc.execute("CREATE TRIGGER " + FAIL_FUNCTION + "_" + table + " BEFORE INSERT ON " + table
                + " FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION " + FAIL_FUNCTION + "()");
    }

    private void dropTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + FAIL_FUNCTION + "_weight_records ON weight_records");
        jdbc.execute("DROP TRIGGER IF EXISTS " + FAIL_FUNCTION + "_notification_outbox ON notification_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS " + FAIL_FUNCTION + "()");
    }
}
