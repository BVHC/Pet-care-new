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
 * Nội trú chạm sang lưu trú (T69 N7, docs/adr/0149 mục 37–39) trên Postgres thật, qua HTTP:
 * <ul>
 * <li>BR-LT-17: thú còn ca {@code ADMITTED} (nhập từ lưu trú) thì bắt đầu trả thú, giao thú, kết thúc do thú mất đều 409,
 * và <b>không ghi gì</b> (đặt chỗ, chuồng, Order); xuất viện rồi thì trả thú bình thường;</li>
 * <li>thú nằm chuồng điều trị (nhập từ lượt khám) không được nhận thêm vào chuồng lưu trú (boarding #14), nhưng 409 trạng
 * thái của đặt chỗ vẫn thắng guard này.</li>
 * </ul>
 * Era <b>năm 2109</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionBoardingGuardIT.TestBeans.class})
class AdmissionBoardingGuardIT extends AdmissionBoardingItBase {

    /** 09:00 ngày 14/3/2109 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2109-03-14T02:00:00Z");

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
    void aStayWithAnActiveAdmissionCannotStartCheckoutBeHandedOverOrEndedAsDeceasedAndNothingChanges() {
        long pet = data.pet(customer);
        long booking = stay(pet);
        long kennel = bookingKennel(booking);
        admitFromStay(booking);

        assertStayBlocked(startCheckout(booking));
        assertStayBlocked(handOver(booking));
        assertStayBlocked(endDeceased(booking));

        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        assertThat(boardingOrders(booking)).as("không mở Order lưu trú").isZero();
        assertThat(jdbc.queryForObject("SELECT end_reason FROM boarding_bookings WHERE id = ?", String.class, booking))
                .isNull();
    }

    @Test
    void theGuardBeatsTheMissingPaymentOfTheHandOver() {
        long pet = data.pet(customer);
        long booking = stay(pet);
        admitFromStay(booking);

        ResponseEntity<Map<String, Object>> response = handOver(booking);

        // không có Order PAID: nếu guard đứng sau BR-LT-09 thì đây là 400, không phải 409
        assertStayBlocked(response);
    }

    @Test
    void afterDischargeTheStayIsReturnedNormallyWithTheDischargeLineStillOnTheVisitOrder() {
        long pet = data.pet(customer);
        long booking = stay(pet);
        long kennel = bookingKennel(booking);
        long admission = admitFromStay(booking);
        assertThat(discharge(admission, "HOME").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> started = startCheckout(booking);

        assertThat(started.getStatusCode().value()).as(String.valueOf(started.getBody())).isEqualTo(200);
        long order = idOf(dataOf(started), "orderId");
        pay(order, ((Number) dataOf(started).get("amount")).longValue());
        ResponseEntity<Map<String, Object>> returned = handOver(booking);
        assertThat(returned.getStatusCode().value()).as(String.valueOf(returned.getBody())).isEqualTo(200);
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_OUT");
        assertThat(endReason(booking)).isEqualTo("RETURNED");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(ordersOfVisit(visitOf(admission))).hasSize(1);
    }

    @Test
    void aPetInATreatmentKennelCannotBeCheckedInToAStayKennelUntilDischargedAndTheStatusConflictWins() {
        long pet = data.pet(customer);
        long[] admitted = admitFromVisit(pet);
        long stayKennel = data.kennel(branch);
        long booked = data.bookedBooking(branch, customer, pet, receptionist, stayKennel, today(), today().plusDays(2));

        ResponseEntity<Map<String, Object>> blocked = boardingCheckIn(booked, stayKennel);

        assertStayBlocked(blocked);
        assertThat(bookingStatus(booked)).isEqualTo("BOOKED");
        assertThat(kennelStatus(stayKennel)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weight_records WHERE pet_id = ?", Integer.class, pet))
                .as("không ghi cân nặng nhận thú").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM boarding_check_ins WHERE boarding_booking_id = ?",
                Integer.class, booked)).isZero();

        // trạng thái đặt chỗ thắng guard: đặt chỗ chưa nhận thì "bắt đầu trả thú" là 409 "không còn trong chuồng"
        ResponseEntity<Map<String, Object>> notInKennel = startCheckout(booked);
        assertStatus(notInKennel, 409, "INVALID_STATE_TRANSITION");
        assertThat(message(notInKennel)).doesNotContain(STAY_BLOCKED);

        // xuất viện rồi thì không còn bị guard này chặn (có thể còn lỗi khác như tiêm phòng)
        assertThat(discharge(admitted[0], "HOME").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> after = boardingCheckIn(booked, stayKennel);
        assertThat(after.getStatusCode().value() == 409 && message(after).endsWith(STAY_BLOCKED))
                .as(String.valueOf(after.getBody())).isFalse();
    }
}
