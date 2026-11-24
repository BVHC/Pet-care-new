package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Chuỗi thú mất trong lúc nội trú (BR-LT-14, T72; docs/adr/0149 mục 37) qua HTTP thật: bốn bước đúng thứ tự — (1) VET xuất
 * viện {@code DECEASED}, (2) VET hoàn tất lượt, (3) lễ tân kết thúc lưu trú {@code PET_DECEASED} (chỉ khi nhập từ lưu trú),
 * (4) lễ tân đánh dấu thú mất — và mỗi bước làm sai thứ tự bị từ chối mà không đổi gì. Xuất viện không tự đánh dấu mất, không
 * tự kết thúc lưu trú. Era <b>năm 2110</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionDeceasedChainIT.TestBeans.class})
class AdmissionDeceasedChainIT extends AdmissionBoardingItBase {

    /** 09:00 ngày 14/3/2110 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2110-03-14T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @BeforeEach
    void setUp() {
        setUpBoarding(NOW);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    @Test
    void theFourStepsFromAStayWorkInOrderAndEveryEarlierAttemptIsRefusedWithoutChange() {
        long pet = data.pet(customer);
        long booking = stay(pet);
        long stayKennel = bookingKennel(booking);
        long admission = admitFromStay(booking);
        long visit = visitOf(admission);

        // trước bước 1: kết thúc lưu trú, hoàn tất lượt, đánh dấu mất đều bị từ chối
        assertStayBlocked(endDeceased(booking));
        assertStatus(completeVisit(visit), 409, "INVALID_STATE_TRANSITION");
        ResponseEntity<Map<String, Object>> early = markDeceased(pet);
        assertRule(early, "BR-KH-05");
        assertThat(message(early)).contains("lượt khám chưa kết thúc").contains("đang lưu trú");
        assertThat(admissionStatus(admission)).isEqualTo("ADMITTED");
        assertThat(visitStatus(visit)).isEqualTo("IN_PROGRESS");
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_IN");
        assertThat(deceasedOn(pet)).isNull();

        // bước 1: xuất viện DECEASED — chỉ ghi kết cục
        ResponseEntity<Map<String, Object>> discharged = discharge(admission, "DECEASED");
        assertThat(discharged.getStatusCode().value()).as(String.valueOf(discharged.getBody())).isEqualTo(200);
        assertThat(dataOf(discharged)).containsEntry("dischargeOutcome", "DECEASED").containsEntry("billedDays", 1);
        assertThat(bookingStatus(booking)).as("không tự kết thúc lưu trú").isEqualTo("CHECKED_IN");
        assertThat(deceasedOn(pet)).as("không tự đánh dấu mất").isNull();
        assertThat(visitStatus(visit)).isEqualTo("IN_PROGRESS");
        assertRule(markDeceased(pet), "BR-KH-05");

        // bước 2: hoàn tất lượt — Order lượt khám chốt với đúng một dòng nội trú
        ResponseEntity<Map<String, Object>> completed = completeVisit(visit);
        assertThat(completed.getStatusCode().value()).as(String.valueOf(completed.getBody())).isEqualTo(200);
        assertThat(ordersOfVisit(visit)).singleElement().satisfies(order -> assertThat(order)
                .containsEntry("source", "VISIT").containsEntry("status", "PENDING").containsEntry("total_amount", PRICE));
        ResponseEntity<Map<String, Object>> stillStaying = markDeceased(pet);
        assertRule(stillStaying, "BR-KH-05");
        assertThat(message(stillStaying)).contains("đang lưu trú").doesNotContain("lượt khám");
        assertThat(deceasedOn(pet)).isNull();

        // bước 3: kết thúc lưu trú do thú mất — chuồng lưu trú trống, Order lưu trú chờ thu
        ResponseEntity<Map<String, Object>> ended = endDeceased(booking);
        assertThat(ended.getStatusCode().value()).as(String.valueOf(ended.getBody())).isEqualTo(200);
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_OUT");
        assertThat(endReason(booking)).isEqualTo("PET_DECEASED");
        assertThat(kennelStatus(stayKennel)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForMap("SELECT source, status FROM orders WHERE boarding_booking_id = ?", booking))
                .containsEntry("source", "BOARDING").containsEntry("status", "PENDING");

        // bước 4: đánh dấu thú mất
        ResponseEntity<Map<String, Object>> marked = markDeceased(pet);
        assertThat(marked.getStatusCode().value()).as(String.valueOf(marked.getBody())).isEqualTo(200);
        assertThat(deceasedOn(pet)).isNotNull();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admissions WHERE pet_id = ? AND status = 'ADMITTED'",
                Integer.class, pet)).isZero();
        assertThat(orderCount(pet)).as("chỉ Order lượt khám; Order lưu trú gắn đặt chỗ, không gắn lượt").isEqualTo(1);
        assertThat(boardingOrders(booking)).isEqualTo(1);
    }

    @Test
    void theThreeStepsFromATreatmentKennelWorkInOrderAndThereIsNoStayToEnd() {
        long pet = data.pet(customer);
        long[] admitted = admitFromVisit(pet);
        long admission = admitted[0];
        long visit = admitted[1];
        long kennel = admitted[2];

        assertStatus(completeVisit(visit), 409, "INVALID_STATE_TRANSITION");
        ResponseEntity<Map<String, Object>> early = markDeceased(pet);
        assertRule(early, "BR-KH-05");
        assertThat(message(early)).contains("lượt khám chưa kết thúc").doesNotContain("lưu trú");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");

        assertThat(discharge(admission, "DECEASED").getStatusCode().value()).isEqualTo(200);
        assertThat(kennelStatus(kennel)).as("chuồng điều trị được trả khi xuất viện").isEqualTo("AVAILABLE");
        assertRule(markDeceased(pet), "BR-KH-05");

        assertThat(completeVisit(visit).getStatusCode().value()).isEqualTo(200);
        assertThat(markDeceased(pet).getStatusCode().value()).isEqualTo(200);
        assertThat(deceasedOn(pet)).isNotNull();
        assertThat(ordersOfVisit(visit)).singleElement().satisfies(order -> assertThat(order)
                .containsEntry("status", "PENDING").containsEntry("total_amount", PRICE));
    }
}
