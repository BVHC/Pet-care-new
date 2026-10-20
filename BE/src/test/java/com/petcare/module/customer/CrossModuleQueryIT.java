package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.content.api.FeedbackQueryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Các method của visit / appointment / boarding / sales / content mà BE-2 cài thật một phần cho T17 (docs/adr/0041):
 * đọc thẳng bảng thật trên Postgres 17, mỗi method một cặp "có / không". Method chưa cài vẫn ném (không trả giá trị an
 * toàn — 06 §1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CrossModuleQueryIT extends CustomerMeItSupport {

    @Autowired
    private VisitQueryApi visits;
    @Autowired
    private AppointmentQueryApi appointments;
    @Autowired
    private BoardingQueryApi boardings;
    @Autowired
    private OrderQueryApi orders;
    @Autowired
    private FeedbackQueryApi feedbacks;

    private PetFixtures fx;
    private long customer;
    private long pet;

    @BeforeEach
    void setUp() {
        fx = new PetFixtures(jdbc, transactionManager);
        customer = fx.track(counterProfile(null, "Khách chéo module", "0911000111", null));
        pet = fx.pet(customer, "Mực", "DOG");
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    @Test
    void visitQueries() {
        assertThat(visits.hasOpenVisit(pet)).isFalse();
        assertThat(visits.existsByPet(pet)).isFalse();
        assertThat(visits.findVisitIdsByPet(pet)).isEmpty();
        assertThat(visits.hasMedicalHistory(pet)).isFalse();

        long done = fx.visit(customer, pet, "COMPLETED");
        assertThat(visits.existsByPet(pet)).isTrue();
        assertThat(visits.hasOpenVisit(pet)).as("COMPLETED không phải Visit mở").isFalse();
        assertThat(visits.hasMedicalHistory(pet)).as("Visit chưa có bệnh án").isFalse();

        long waiting = fx.visit(customer, pet, "WAITING");
        long inProgress = fx.visit(customer, pet, "IN_PROGRESS");
        assertThat(visits.hasOpenVisit(pet)).isTrue();
        assertThat(visits.findVisitIdsByPet(pet)).containsExactly(done, waiting, inProgress);

        fx.medicalRecord(done);
        assertThat(visits.hasMedicalHistory(pet)).isTrue();
    }

    @Test
    void cancelledVisitIsNotOpen() {
        fx.visit(customer, pet, "CANCELLED");

        assertThat(visits.hasOpenVisit(pet)).isFalse();
        assertThat(visits.existsByPet(pet)).isTrue();
    }

    @Test
    void vaccinationAloneLocksSpecies() {
        long visit = fx.visit(customer, pet, "COMPLETED");
        fx.vaccination(pet, visit);

        assertThat(visits.hasMedicalHistory(pet)).isTrue();
        long other = fx.pet(customer, "Khác", "CAT");
        assertThat(visits.hasMedicalHistory(other)).isFalse();
    }

    @Test
    void appointmentQueries() {
        assertThat(appointments.existsByPet(pet)).isFalse();
        assertThat(appointments.existsByCustomer(customer)).isFalse();

        fx.appointment(customer, pet, "CANCELLED");

        assertThat(appointments.existsByPet(pet)).as("lịch đã hủy vẫn là giao dịch (BR-KH-06)").isTrue();
        assertThat(appointments.existsByCustomer(customer)).isTrue();
        assertThat(appointments.existsByPet(fx.pet(customer, "Mèo", "CAT"))).isFalse();
    }

    @Test
    void boardingQueries() {
        assertThat(boardings.isPetInStay(pet)).isFalse();
        assertThat(boardings.existsByPet(pet)).isFalse();
        assertThat(boardings.existsByCustomer(customer)).isFalse();
        assertThat(boardings.findBookingIdsByPet(pet)).isEmpty();

        long booked = fx.booking(customer, pet, "BOOKED", 10);
        assertThat(boardings.isPetInStay(pet)).as("BOOKED chưa vào chuồng").isFalse();
        assertThat(boardings.existsByPet(pet)).isTrue();
        assertThat(boardings.existsByCustomer(customer)).isTrue();

        long checkedIn = fx.booking(customer, pet, "CHECKED_IN", 20);
        assertThat(boardings.isPetInStay(pet)).isTrue();
        assertThat(boardings.findBookingIdsByPet(pet)).containsExactly(booked, checkedIn);
    }

    /** T29: sales đọc trạng thái đặt chỗ cho BR-BH-05 (Order lưu trú khi thú còn trong chuồng). */
    @Test
    void findBookingReadsTheBookingRowOrNothing() {
        long overdue = fx.booking(customer, pet, "OVERDUE", 50);

        assertThat(boardings.findBooking(overdue)).hasValueSatisfying(stay -> {
            assertThat(stay.bookingId()).isEqualTo(overdue);
            assertThat(stay.customerId()).isEqualTo(customer);
            assertThat(stay.petId()).isEqualTo(pet);
            assertThat(stay.status()).isEqualTo(BoardingQueryApi.StayStatus.OVERDUE);
            assertThat(stay.emergencyPhone()).as("chưa có boarding_check_ins").isNull();
        });
        assertThat(boardings.findBooking(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void overdueCountsAsInStayButCheckedOutDoesNot() {
        fx.booking(customer, pet, "CHECKED_OUT", 30);
        assertThat(boardings.isPetInStay(pet)).isFalse();

        fx.booking(customer, pet, "OVERDUE", 40);
        assertThat(boardings.isPetInStay(pet)).isTrue();
    }

    @Test
    void pendingOrderForPetFollowsVisitAndBookingOrders() {
        assertThat(orders.hasPendingOrderForPet(pet)).isFalse();
        assertThat(orders.existsByCustomer(customer)).isFalse();

        long visit = fx.visit(customer, pet, "COMPLETED");
        fx.orderForVisit(customer, visit, "CANCELLED");
        assertThat(orders.hasPendingOrderForPet(pet)).as("CANCELLED không chặn").isFalse();
        assertThat(orders.existsByCustomer(customer)).isTrue();

        long visit2 = fx.visit(customer, pet, "COMPLETED");
        fx.orderForVisit(customer, visit2, "PENDING");
        assertThat(orders.hasPendingOrderForPet(pet)).isTrue();
    }

    @Test
    void findVisitReadsTheVisitRowOrNothing() {
        assertThat(visits.findVisit(Long.MAX_VALUE)).isEmpty();

        long waiting = fx.visit(customer, pet, "WAITING");
        long running = fx.visit(customer, pet, "IN_PROGRESS");

        assertThat(visits.findVisit(waiting)).hasValueSatisfying(v -> {
            assertThat(v.customerId()).isEqualTo(customer);
            assertThat(v.petId()).isEqualTo(pet);
            assertThat(v.status()).isEqualTo(VisitQueryApi.VisitStatus.WAITING);
            assertThat(v.group()).isEqualTo(com.petcare.module.catalog.api.ServiceGroup.MEDICAL);
            assertThat(v.assigneeId()).as("WAITING chưa gán").isNull();
        });
        assertThat(visits.findVisit(running)).hasValueSatisfying(v -> {
            assertThat(v.status()).isEqualTo(VisitQueryApi.VisitStatus.IN_PROGRESS);
            assertThat(v.assigneeId()).isEqualTo(999999L);
        });
    }

    @Test
    void unpaidOrdersOfCustomerAreOpenAndPendingWithTheirBranch() {
        assertThat(orders.findUnpaidOrdersByCustomer(customer)).isEmpty();

        long open = fx.orderForVisit(customer, fx.visit(customer, pet, "COMPLETED"), "OPEN");
        long pending = fx.orderForVisit(customer, fx.visit(customer, pet, "COMPLETED"), "PENDING");
        fx.orderForVisit(customer, fx.visit(customer, pet, "COMPLETED"), "CANCELLED");

        assertThat(orders.findUnpaidOrdersByCustomer(customer))
                .containsExactly(new OrderQueryApi.UnpaidOrderRef(open, 999999L),
                        new OrderQueryApi.UnpaidOrderRef(pending, 999999L));
    }

    @Test
    void pendingBoardingOrderBlocksToo() {
        long booking = fx.booking(customer, pet, "CHECKED_OUT", 50);
        fx.orderForBooking(customer, booking, "PENDING");

        assertThat(orders.hasPendingOrderForPet(pet)).isTrue();
        assertThat(orders.hasPendingOrderForPet(fx.pet(customer, "Thú khác", "CAT"))).isFalse();
    }

    @Test
    void feedbackQuery() {
        assertThat(feedbacks.existsByCustomer(customer)).isFalse();

        fx.feedback(customer);

        assertThat(feedbacks.existsByCustomer(customer)).isTrue();
    }

    @Test
    void queriesUseTheNewIndexes() {
        for (String sql : new String[] {
                "SELECT 1 FROM boarding_bookings WHERE pet_id = %d",
                "SELECT 1 FROM boarding_bookings WHERE customer_id = %d",
                "SELECT 1 FROM care_tasks WHERE pet_id = %d",
                "SELECT 1 FROM visits WHERE customer_id = %d",
                "SELECT 1 FROM payments WHERE customer_id = %d"}) {
            String plan = explain(sql.formatted(pet));
            assertThat(plan).as(sql).doesNotContain("Seq Scan");
        }
    }

    @Test
    void notYetImplementedMethodsStillThrow() {
        assertThatThrownBy(() -> visits.findUnfinishedVisitsAssignedTo(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> boardings.findActiveStaysByCustomer(1L))
                .isInstanceOf(UnsupportedOperationException.class);
<<<<<<< HEAD
        // appointments.findBookedByCustomer thật từ T16 phần 5 (AppointmentLookupService, docs/adr/0050)
=======
        // AppointmentQueryApi là bản thật của BE-1 (docs/adr/0046, AppointmentLookupService) từ khi gộp nhánh.
        assertThat(appointments.findBookedByCustomer(-1L)).isEmpty();
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    }

    /** Bảng rỗng nên planner chọn Seq Scan; tắt seqscan trong transaction để chứng minh index dùng được. */
    private String explain(String sql) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        });
    }
}
