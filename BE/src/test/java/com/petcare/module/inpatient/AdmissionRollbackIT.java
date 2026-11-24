package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
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
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã ghi</b> trong nhập viện (inpatient-v1 #1; docs/adr/0149 mục 18): chuồng đã chiếm (đường a) hoặc Visit
 * điều trị + Order đã tạo (đường b), rồi bước lưu ca lỗi — cả use case phải rollback (chuồng trống lại, không Visit /
 * Order / ca, bộ đếm mã không tăng) và lần gọi sau khi hết lỗi tạo đúng một ca. Thêm nhánh unique index
 * {@code ux_admissions_pet_admitted} là lưới đỡ cuối khi lần kiểm trùng bị lọt (mô phỏng hai nhập viện đồng thời): 409
 * {@code CONCURRENCY_CONFLICT}, không ghi gì. Spy repository vì {@code @MockitoSpyBean} không stub được bean
 * {@code @Transactional} (CLAUDE.md). Spy nằm <b>sau</b> proxy giao dịch của repository (method {@code MANDATORY}), nên stub
 * phải đặt trên chính spy ({@link #spy()}); stub qua proxy sẽ chạy lớp giao dịch, ném giữa chừng và để lại stub dang dở
 * cho lần gọi sau. Era <b>năm 2099</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionRollbackIT.TestBeans.class})
class AdmissionRollbackIT extends AdmissionItBase {

    private static final Instant NOW = Instant.parse("2099-03-14T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private AdmissionRepository admissions;

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
        service = enabledInpatientService(branch, 200_000L);
        token = token(vet);
    }

    /** Mockito spy bên trong proxy giao dịch của Spring. */
    private AdmissionRepository spy() {
        return AopTestUtils.getTargetObject(admissions);
    }

    @AfterEach
    void tearDown() {
        reset(spy());
        finish();
    }

    @Test
    void aFailureAfterTheKennelWasTakenFreesItAgainAndARetryAdmitsOnce() {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        doThrow(new DataIntegrityViolationException("save broke")).when(spy()).saveAndFlush(any(Admission.class));

        ResponseEntity<Map<String, Object>> failed = admit(token, fromVisit(visit, service, kennel));

        assertStatus(failed, 409, "CONCURRENCY_CONFLICT");
        assertThat(kennelStatus(kennel)).as("Chuồng#6 đã hoàn tác").isEqualTo("AVAILABLE");
        assertThat(admissionCount(pet)).isZero();
        assertThat(counter(Admission.CODE_DOC_TYPE)).as("mã ca trả lại khi rollback").isZero();

        reset(spy());
        ResponseEntity<Map<String, Object>> retry = admit(token, fromVisit(visit, service, kennel));

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat((String) dataOf(retry).get("code")).isEqualTo("NT-9903-00001");
        assertThat(admissionCount(pet)).isEqualTo(1);
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
    }

    @Test
    void aFailureAfterTheTreatmentVisitWasOpenedRollsTheVisitAndItsOrderBack() {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        int visits = counter("visits");
        int orders = counter(OnlineSalesOrderApi.ORDER_DOC_TYPE);
        doThrow(new DataIntegrityViolationException("save broke")).when(spy()).saveAndFlush(any(Admission.class));

        ResponseEntity<Map<String, Object>> failed = admit(token, fromBooking(booking, service));

        assertStatus(failed, 409, "CONCURRENCY_CONFLICT");
        assertThat(visitCount(pet)).as("Visit#7 đã hoàn tác").isZero();
        assertThat(orderCount(pet)).as("Order#14 đã hoàn tác").isZero();
        assertThat(admissionCount(pet)).isZero();
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_IN");
        assertThat(counter("visits")).isEqualTo(visits);
        assertThat(counter(OnlineSalesOrderApi.ORDER_DOC_TYPE)).isEqualTo(orders);
    }

    /** Xuất viện (N4): lưu ca là bước cuối; lỗi ở đó phải kéo theo dòng Order và chuồng đã ghi trước đó. */
    @Test
    void aFailureWhileSavingTheDischargeRollsTheOrderLineAndTheKennelBackAndARetryBillsOnce() {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        long id = idOf(dataOf(admit(token, fromVisit(visit, service, kennel))), "admissionId");
        doThrow(new DataIntegrityViolationException("save broke")).when(spy()).saveAndFlush(any(Admission.class));

        ResponseEntity<Map<String, Object>> failed = call(org.springframework.http.HttpMethod.POST,
                ADMISSIONS + "/" + id + "/discharge", token, "{'outcome':'HOME','summary':'Ổn'}");

        assertStatus(failed, 409, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, visit)).as("dòng nội trú đã hoàn tác").isZero();
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE visit_id = ?", Long.class, visit))
                .isZero();
        assertThat(kennelStatus(kennel)).as("Chuồng#7 đã hoàn tác").isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, id))
                .isEqualTo("ADMITTED");

        reset(spy());
        ResponseEntity<Map<String, Object>> retry = call(org.springframework.http.HttpMethod.POST,
                ADMISSIONS + "/" + id + "/discharge", token, "{'outcome':'HOME','summary':'Ổn'}");

        assertThat(retry.getStatusCode().value()).as(String.valueOf(retry.getBody())).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, visit)).isEqualTo(1);
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void whenTheDuplicateCheckIsSkippedTheUniqueIndexStillStopsASecondAdmission() {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        assertThat(admit(token, fromVisit(visit, service, data.kennel(branch))).getStatusCode().value())
                .isEqualTo(201);
        long second = data.kennel(branch);
        // Mô phỏng hai nhập viện đồng thời: lần kiểm "đã có ca" không thấy ca kia (chưa commit).
        doReturn(false).when(spy()).existsActiveByPetId(anyLong());

        ResponseEntity<Map<String, Object>> response = admit(token, fromVisit(visit, service, second));

        assertStatus(response, 409, "CONCURRENCY_CONFLICT");
        assertThat(admissionCount(pet)).isEqualTo(1);
        assertThat(kennelStatus(second)).as("chuồng thứ hai không bị giữ").isEqualTo("AVAILABLE");
    }
}
