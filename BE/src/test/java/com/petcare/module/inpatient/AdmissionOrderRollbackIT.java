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
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã khóa</b> trong y lệnh nội trú (inpatient-v1 #5, #7, #8; docs/adr/0149 mục 28): bước lưu y lệnh lỗi →
 * cả use case rollback (không có dòng y lệnh / y lệnh vẫn {@code ORDERED} với cột thực hiện, lý do còn NULL) và <b>mọi
 * khóa được nhả</b>: lần gọi sau khi hết lỗi thành công, và dòng đơn thuốc đã bị {@code FOR KEY SHARE} xóa được ngay (nếu
 * khóa rò rỉ thì {@code lock_timeout} làm test đỏ thay vì treo). Spy repository đặt <b>trên chính spy</b>, không qua
 * proxy giao dịch (CLAUDE.md, ADR-0149 N3). Era <b>năm 2103</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionOrderRollbackIT.TestBeans.class})
class AdmissionOrderRollbackIT extends AdmissionOrderItBase {

    private static final Instant NOW = Instant.parse("2103-03-14T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private AdmissionOrderRepository admissionOrders;

    private long vet;
    private String token;
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        long branch = data.branch();
        vet = data.staff("VET", branch);
        long service = enabledInpatientService(branch, 200_000L);
        token = token(vet);
        a = admitFromVisit(branch, data.customer(), service, vet, token);
    }

    /** Mockito spy bên trong proxy giao dịch của Spring. */
    private AdmissionOrderRepository spy() {
        return AopTestUtils.getTargetObject(admissionOrders);
    }

    @AfterEach
    void tearDown() {
        reset(spy());
        finish();
    }

    private void failTheSave() {
        doThrow(new DataIntegrityViolationException("save broke")).when(spy()).saveAndFlush(any(AdmissionOrder.class));
    }

    @Test
    void aFailureWhileSavingANewOrderLeavesNoRowFreesTheAdmissionLockAndARetrySucceeds() {
        failTheSave();

        assertStatus(createOrder(token, a.id(), feeding("Cho ăn")), 409, "CONCURRENCY_CONFLICT");

        assertThat(orderRows(a.id())).isZero();
        reset(spy());
        ResponseEntity<Map<String, Object>> retry = createOrder(token, a.id(), feeding("Cho ăn"));
        assertThat(retry.getStatusCode().value()).as(String.valueOf(retry.getBody())).isEqualTo(201);
        assertThat(orderRows(a.id())).isEqualTo(1);
        // khóa ca đã nhả: xuất viện chạy được ngay sau khi hủy y lệnh vừa ra
        assertThat(cancel(token, idOf(dataOf(retry), "admissionOrderId"), "Không cần").getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void aFailureWhileSavingACompletionLeavesTheOrderOrderedAndARetryCompletesIt() {
        long order = newFeeding(token, a.id());
        String before = orderFootprint(order);
        failTheSave();

        assertStatus(complete(token, order, "Đã làm"), 409, "CONCURRENCY_CONFLICT");

        assertThat(orderFootprint(order)).isEqualTo(before);
        assertThat(orderStatus(order)).isEqualTo("ORDERED");
        reset(spy());
        assertThat(complete(token, order, "Đã làm").getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(order)).isEqualTo("DONE");
    }

    @Test
    void aFailureWhileSavingACancellationLeavesTheOrderOrderedAndARetryCancelsIt() {
        long order = newFeeding(token, a.id());
        String before = orderFootprint(order);
        failTheSave();

        assertStatus(cancel(token, order, "Không cần"), 409, "CONCURRENCY_CONFLICT");

        assertThat(orderFootprint(order)).isEqualTo(before);
        reset(spy());
        assertThat(cancel(token, order, "Không cần").getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
    }

    @Test
    void theKeyShareLockOnThePrescriptionLineIsReleasedWhenTheSaveFails() {
        long item = data.clinicPrescriptionItem(a.visit(), vet);
        failTheSave();

        assertStatus(createOrder(token, a.id(), medication(item)), 409, "CONCURRENCY_CONFLICT");

        assertThat(orderRows(a.id())).isZero();
        assertThat(deleteWithShortLockTimeout(item)).as("dòng đơn xóa được: khóa đã nhả").isEqualTo(1);
    }

    @Test
    void theKeyShareLockOnTheLineIsReleasedWhenTheRuleRefusesAfterTheLookup() {
        long external = data.prescriptionItem(a.visit());

        assertRule(createOrder(token, a.id(), medication(external)), "BR-LT-15");

        assertThat(orderRows(a.id())).isZero();
        assertThat(deleteWithShortLockTimeout(external)).as("dòng đơn xóa được: khóa đã nhả").isEqualTo(1);
    }

    /** Xóa dòng đơn trong transaction riêng với {@code lock_timeout}: khóa còn giữ thì ném lỗi thay vì treo. */
    private int deleteWithShortLockTimeout(long itemId) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '3s'");
            return jdbc.update("DELETE FROM prescription_items WHERE id = ?", itemId);
        });
    }
}
