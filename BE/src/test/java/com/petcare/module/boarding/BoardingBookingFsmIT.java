package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.job.BoardingNoShowJob;
import com.petcare.module.boarding.job.BoardingOverdueJob;
import com.petcare.support.MutableClock;

/**
 * T45 — đủ 11 chuyển của bảng "6. Đặt chỗ lưu trú" (docs/03-state-machines.md) và các chuyển Order {@code BOARDING}
 * (Order#3, #9, #10) qua endpoint / job thật, <b>không stand-in nào</b> (IT cũ stand-in {@code VaccinationQueryApi}:
 * ở đây loài chó không có loại vaccine bắt buộc, kiểm ở {@code @BeforeEach}). Cặp sai có đường HTTP → 409 và không ghi
 * gì; nguồn {@code OVERDUE} đi bằng ST15, {@code NO_SHOW} bằng ST05. Era <b>năm 1987</b>: ST05 và ST15 quét mọi đặt
 * chỗ nên IT này phải cũ hơn mọi era khác (CLAUDE.md, mục Testing). Chi nhánh mở 08:00–19:00 mọi ngày (hiệu lực từ 1980).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingBookingFsmIT.TestBeans.class})
class BoardingBookingFsmIT extends BoardingItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(1987, 6, 10);
    private static final long NIGHTLY = 150_000;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(instantAt(DAY, 10, 0));
        }
    }

    @Autowired
    private BoardingNoShowJob noShowJob;
    @Autowired
    private BoardingOverdueJob overdueJob;

    private long branch;
    private long type;
    private long kennel;
    private long spareKennel;
    private long receptionistId;
    private long caretakerId;
    private long managerId;
    private long customerAccount;
    private long customer;
    private long pet;
    private String receptionist;
    private String caretaker;
    private String manager;
    private String customerToken;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vaccination_protocols WHERE species = 'DOG' AND required_for_boarding AND is_active""",
                Long.class)).as("IT khác để sót phác đồ bắt buộc khi lưu trú cho chó").isZero();
        branch = newBranch();
        jdbc.update("UPDATE opening_hours SET effective_from = DATE '1980-01-01' WHERE branch_id = ?", branch);
        type = newKennelType(branch, "DOG", "30", NIGHTLY);
        kennel = newKennel(branch, type, "C-01", "AVAILABLE");
        spareKennel = newKennel(branch, type, "C-02", "AVAILABLE");
        receptionistId = newStaff("RECEPTIONIST", branch);
        caretakerId = newStaff("CARETAKER", branch);
        managerId = newStaff("BRANCH_MANAGER", branch);
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        customer = online[1];
        pet = newPet(customer, "DOG");
        weigh(pet, "10.00");
        at(DAY, 10, 0);
    }

    // ================================================================ chuyển hợp lệ — Đặt chỗ

    /** #1 — khách online và lễ tân đặt chỗ: {@code BOOKED}; giá đêm snapshot (đổi giá sau không đổi đặt chỗ). */
    @Test
    void customerAndReceptionistBookWithASnapshotRate() {
        long mine = book(customerToken, pet, DAY.plusDays(2), DAY.plusDays(3));
        long counterPet = counterPet();
        long counter = book(receptionist, counterPet, DAY, DAY.plusDays(1));

        jdbc.update("UPDATE services SET price = 200000 WHERE id = ?", type);

        for (long id : List.of(mine, counter)) {
            assertThat(statusOf(id)).isEqualTo("BOOKED");
            assertThat(jdbc.queryForObject("SELECT nightly_price FROM boarding_bookings WHERE id = ?", Long.class, id))
                    .isEqualTo(NIGHTLY);
        }
    }

    /** #2 — gia hạn ngày trả khi {@code BOOKED} và khi {@code CHECKED_IN}: trạng thái giữ nguyên. */
    @Test
    void extendingKeepsTheStatusWhenBookedAndWhenCheckedIn() {
        long booked = book(receptionist, counterPet(), DAY.plusDays(1), DAY.plusDays(2));
        long stay = stay(DAY.plusDays(2));

        data(extend(booked, DAY.plusDays(3)), HttpStatus.OK);
        data(extend(stay, DAY.plusDays(3)), HttpStatus.OK);

        assertThat(statusOf(booked)).isEqualTo("BOOKED");
        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
        for (long id : List.of(booked, stay)) {
            assertThat(jdbc.queryForObject("SELECT check_out_date FROM boarding_bookings WHERE id = ?",
                    java.sql.Date.class, id)).isEqualTo(java.sql.Date.valueOf(DAY.plusDays(3)));
        }
    }

    /** #3 — nhận thú: {@code CHECKED_IN}, chuồng {@code OCCUPIED}, cân nặng vào lịch sử. */
    @Test
    void checkInOccupiesTheKennelAndRecordsTheWeight() {
        long id = stay(DAY.plusDays(2));

        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        assertThat(jdbc.queryForObject("SELECT kennel_id FROM boarding_bookings WHERE id = ?", Long.class, id))
                .isEqualTo(kennel);
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        assertThat(count("weight_records", id)).isOne();
    }

    /** #4 — khách hủy: {@code late_cancel} đúng ở mốc 24h [CFG] trước ngày nhận. */
    @Test
    void customerCancelSetsLateCancelAtTheTwentyFourHourMark() {
        long early = book(customerToken, pet, DAY.plusDays(3), DAY.plusDays(4));       // còn ~62h
        long otherPet = newPet(customer, "DOG");
        weigh(otherPet, "10.00");
        long late = book(customerToken, otherPet, DAY.plusDays(1), DAY.plusDays(2));   // còn ~14h

        data(call(HttpMethod.POST, "/api/boarding-bookings/" + early + "/cancel", customerToken,
                Map.of("reason", "Đổi lịch")), HttpStatus.OK);
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + late + "/cancel", customerToken,
                Map.of("reason", "Bận")), HttpStatus.OK);

        assertThat(statusOf(early)).isEqualTo("CANCELLED");
        assertThat(lateCancel(early)).isFalse();
        assertThat(statusOf(late)).isEqualTo("CANCELLED");
        assertThat(lateCancel(late)).isTrue();
    }

    /** #5 (1/2) — phòng khám hủy vì ngày nghỉ / thú mất / chuyển chủ: {@code CANCELLED}, không tính hủy muộn. */
    @Test
    void clinicCancellationsFromHolidayDeceasedAndTransferAreNeverLate() {
        long holidayBooking = book(receptionist, counterPet(), DAY.plusDays(5), DAY.plusDays(6));
        long deceasedPet = counterPet();
        long deceasedBooking = book(receptionist, deceasedPet, DAY.plusDays(7), DAY.plusDays(8));
        long transferPet = counterPet();
        long transferBooking = book(receptionist, transferPet, DAY.plusDays(9), DAY.plusDays(10));

        Map<String, Object> holiday = new HashMap<>();
        holiday.put("holidayDate", DAY.plusDays(5).toString());
        holiday.put("reason", "Nghỉ lễ");
        holiday.put("cancelAffected", true);
        data(call(HttpMethod.POST, "/api/branches/" + branch + "/holidays", manager, holiday), HttpStatus.CREATED);
        data(call(HttpMethod.POST, "/api/pets/" + deceasedPet + "/mark-deceased", receptionist,
                Map.of("deceasedOn", DAY.toString(), "confirmed", true)), HttpStatus.OK);
        long newOwner = newOnlineCustomer()[1];
        data(call(HttpMethod.POST, "/api/pets/" + transferPet + "/transfer-owner", receptionist,
                Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)), HttpStatus.OK);

        for (long id : List.of(holidayBooking, deceasedBooking, transferBooking)) {
            assertThat(statusOf(id)).isEqualTo("CANCELLED");
            assertThat(lateCancel(id)).isFalse();
        }
    }

    /** #5 (2/2) — hết chuồng đúng loại lúc nhận: lễ tân hủy bằng {@code cancel-no-kennel}, không tính hủy muộn. */
    @Test
    void cancelNoKennelCancelsWhenNoKennelOfTheTypeIsAvailable() {
        long id = book(receptionist, counterPet(), DAY, DAY.plusDays(1));
        for (long k : List.of(kennel, spareKennel)) {
            data(call(HttpMethod.POST, "/api/kennels/" + k + "/start-maintenance", manager, null), HttpStatus.OK);
        }

        data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/cancel-no-kennel", receptionist, null),
                HttpStatus.OK);

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(lateCancel(id)).isFalse();
    }

    /** #6 — ST05: tới hết giờ làm việc (19:00) ngày nhận mà chưa nhận thú → {@code NO_SHOW}. */
    @Test
    void st05MarksAnUncollectedBookingNoShow() {
        long id = book(receptionist, counterPet(), DAY, DAY.plusDays(1));

        runNoShow(at(DAY, 18, 59));
        assertThat(statusOf(id)).isEqualTo("BOOKED");

        runNoShow(at(DAY, 19, 0));
        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
    }

    /** #7 — ST15: quá 12:00 ngày trả dự kiến → {@code OVERDUE}; qua ≥ 1 ngày có đúng một Care Task {@code PICKUP_OVERDUE}. */
    @Test
    void st15MarksOverdueAndOpensOnePickupTaskAfterADay() {
        long id = stay(DAY.plusDays(2));

        runOverdue(at(DAY.plusDays(2), 11, 59));
        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        runOverdue(at(DAY.plusDays(2), 12, 1));
        assertThat(statusOf(id)).isEqualTo("OVERDUE");
        assertThat(pickupTasks(id)).isZero();

        runOverdue(at(DAY.plusDays(3), 12, 1));
        runOverdue(at(DAY.plusDays(3), 12, 2));

        assertThat(statusOf(id)).isEqualTo("OVERDUE");
        assertThat(pickupTasks(id)).isOne();
    }

    /** #8 — nhật ký đánh dấu bất thường: giữ nguyên trạng thái, báo khách và lễ tân ngay. */
    @Test
    void abnormalCareLogKeepsTheStatusAndNotifies() {
        long id = stay(DAY.plusDays(2));
        long before = outboxOf(customerAccount, receptionistId);
        Map<String, Object> body = new HashMap<>();
        body.put("eating", "Bỏ ăn");
        body.put("drinking", "Uống ít");
        body.put("note", "Nôn hai lần");
        body.put("photoUrls", List.of());
        body.put("isAbnormal", true);

        data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/care-logs", caretaker, body), HttpStatus.CREATED);

        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        assertThat(outboxOf(customerAccount, receptionistId)).isGreaterThan(before);
    }

    /** #9 + Order#3 — bắt đầu trả thú: giữ trạng thái; Order {@code BOARDING} {@code PENDING}; gọi lại dùng lại Order. */
    @Test
    void startCheckoutPendsOneBoardingOrderAndReusesItOnTheSecondCall() {
        long id = stay(DAY.plusDays(2));
        at(DAY.plusDays(1), 10, 0);

        Map<String, Object> first = data(startCheckout(id), HttpStatus.OK);
        at(DAY.plusDays(1), 11, 0);
        Map<String, Object> second = data(startCheckout(id), HttpStatus.OK);

        long orderId = number(first.get("orderId"));
        assertThat(number(second.get("orderId"))).isEqualTo(orderId);
        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        assertThat(orderCount(id)).isOne();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT source FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("BOARDING");
        assertThat(orderTotal(orderId)).isEqualTo(sumOfLines(orderId));
    }

    /** #10 — giao thú: cần Order lưu trú {@code PAID} (BR-LT-09); xong chuồng {@code AVAILABLE}, Order giữ {@code PAID}. */
    @Test
    void handOverNeedsAPaidOrderThenFreesTheKennel() {
        long id = stay(DAY.plusDays(2));
        at(DAY.plusDays(1), 10, 0);
        long orderId = number(data(startCheckout(id), HttpStatus.OK).get("orderId"));

        ResponseEntity<Map<String, Object>> early = handOver(id);
        assertRule(early, "BR-LT-09");
        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");

        pay(orderId);
        data(handOver(id), HttpStatus.OK);

        assertThat(statusOf(id)).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM boarding_bookings WHERE id = ?", String.class, id))
                .isEqualTo("RETURNED");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
    }

    /**
     * #11 — thú mất: chuồng {@code AVAILABLE}, Order {@code PENDING}. Trước đó thú đang ở chuồng không đánh dấu mất
     * được (BR-KH-05); sau đó thì được.
     */
    @Test
    void endDeceasedFreesTheKennelAndOnlyThenThePetCanBeMarkedDeceased() {
        long id = stay(DAY.plusDays(2));
        long stayPet = petOf(id);
        Map<String, Object> mark = Map.of("deceasedOn", DAY.toString(), "confirmed", true);

        ResponseEntity<Map<String, Object>> blocked = call(HttpMethod.POST, "/api/pets/" + stayPet + "/mark-deceased",
                receptionist, mark);
        assertThat(blocked.getStatusCode().is4xxClientError()).as("%s", blocked.getBody()).isTrue();
        assertThat(jdbc.queryForObject("SELECT deceased_on IS NULL FROM pets WHERE id = ?", Boolean.class, stayPet))
                .isTrue();

        data(endDeceased(id), HttpStatus.OK);

        assertThat(statusOf(id)).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM boarding_bookings WHERE id = ?", String.class, id))
                .isEqualTo("PET_DECEASED");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(orderStatus(activeOrder(id))).isEqualTo("PENDING");
        data(call(HttpMethod.POST, "/api/pets/" + stayPet + "/mark-deceased", receptionist, mark), HttpStatus.OK);
    }

    /** #10 và #11 cũng đi được từ {@code OVERDUE} (03: {@code CHECKED_IN}/{@code OVERDUE} → {@code CHECKED_OUT}). */
    @Test
    void anOverdueStayCanBeHandedOverAfterPaymentAndAnotherEndedAsDeceased() {
        long returned = stay(DAY.plusDays(2));
        long deceased = stayInSpareKennel(DAY.plusDays(2));
        runOverdue(at(DAY.plusDays(2), 12, 1));
        assertThat(statusOf(returned)).isEqualTo("OVERDUE");
        assertThat(statusOf(deceased)).isEqualTo("OVERDUE");

        long orderId = number(data(startCheckout(returned), HttpStatus.OK).get("orderId"));
        pay(orderId);
        data(handOver(returned), HttpStatus.OK);
        data(endDeceased(deceased), HttpStatus.OK);

        assertThat(statusOf(returned)).isEqualTo("CHECKED_OUT");
        assertThat(statusOf(deceased)).isEqualTo("CHECKED_OUT");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(kennelStatus(spareKennel)).isEqualTo("AVAILABLE");
    }

    /** Đặt chỗ đã đóng: hệ thống hủy (ngày nghỉ) là no-op, không phải lỗi. */
    @Test
    void clinicCancellationOfAClosedBookingIsANoOp() {
        long id = stay(DAY.plusDays(3));
        data(endDeceased(id), HttpStatus.OK);
        Map<String, Object> before = footprint(id);

        Map<String, Object> holiday = new HashMap<>();
        holiday.put("holidayDate", DAY.plusDays(1).toString());
        holiday.put("reason", "Nghỉ lễ");
        holiday.put("cancelAffected", true);
        data(call(HttpMethod.POST, "/api/branches/" + branch + "/holidays", manager, holiday), HttpStatus.CREATED);

        assertThat(footprint(id)).isEqualTo(before);
        assertThat(statusOf(id)).isEqualTo("CHECKED_OUT");
    }

    // ================================================================ Order BOARDING (#9, #10)

    /** Order#9 — hủy phiên trả thú: Order {@code CANCELLED}, đặt chỗ giữ {@code CHECKED_IN}, có audit. */
    @Test
    void abortingTheCheckoutCancelsTheBoardingOrderAndKeepsTheStay() {
        long id = stay(DAY.plusDays(2));
        at(DAY.plusDays(1), 10, 0);
        long orderId = number(data(startCheckout(id), HttpStatus.OK).get("orderId"));

        data(call(HttpMethod.POST, "/api/orders/" + orderId + "/abort-checkout", receptionist,
                Map.of("reason", "Khách muốn gửi thêm")), HttpStatus.OK);

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_CHECKOUT_ABORTED' AND entity_id = ?""",
                Long.class, orderId)).isOne();
    }

    /** Order#10 — hủy Order lưu trú khách không thanh toán: chỉ khi thú không còn trong chuồng (BR-BH-05). */
    @Test
    void managerCancelsTheUnpaidBoardingOrderOnlyAfterThePetLeftTheKennel() {
        long id = stay(DAY.plusDays(2));
        at(DAY.plusDays(1), 10, 0);
        long orderId = number(data(startCheckout(id), HttpStatus.OK).get("orderId"));
        Map<String, Object> reason = Map.of("reason", "Khách không thanh toán");

        ResponseEntity<Map<String, Object>> whileInside = call(HttpMethod.POST, "/api/orders/" + orderId
                + "/cancel-unpaid", manager, reason);
        assertThat(whileInside.getStatusCode().is4xxClientError()).as("%s", whileInside.getBody()).isTrue();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");

        data(endDeceased(id), HttpStatus.OK);
        data(call(HttpMethod.POST, "/api/orders/" + orderId + "/cancel-unpaid", manager, reason), HttpStatus.OK);

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(statusOf(id)).isEqualTo("CHECKED_OUT");
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /** Nguồn đi bằng API / job thật; mỗi thao tác không có cạnh trong 03 phải 409 và không ghi gì. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BOOKED", "CHECKED_IN", "OVERDUE", "CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long id = reach(source);
        assertThat(statusOf(id)).isEqualTo(source);
        Map<String, Object> before = footprint(id);

        List<String> tried = switch (source) {
            case "BOOKED" -> List.of("start-checkout", "hand-over", "end-deceased");
            case "CHECKED_IN" -> List.of("check-in", "cancel", "cancel-no-kennel");
            case "OVERDUE" -> List.of("check-in", "cancel", "cancel-no-kennel", "extend");
            default -> List.of("check-in", "cancel", "cancel-no-kennel", "extend", "start-checkout", "hand-over",
                    "end-deceased");
        };
        for (String operation : tried) {
            ResponseEntity<Map<String, Object>> response = attempt(operation, id);
            assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("statusCode", 409);
        }

        assertThat(footprint(id)).isEqualTo(before);
    }

    // ================================================================ helpers — luồng

    private long reach(String source) {
        return switch (source) {
            case "BOOKED" -> book(receptionist, counterPet(), DAY, DAY.plusDays(2));
            case "CHECKED_IN" -> stay(DAY.plusDays(2));
            case "OVERDUE" -> {
                long id = stay(DAY.plusDays(2));
                runOverdue(at(DAY.plusDays(2), 12, 1));
                yield id;
            }
            case "CHECKED_OUT" -> {
                long id = stay(DAY.plusDays(2));
                data(endDeceased(id), HttpStatus.OK);
                yield id;
            }
            case "CANCELLED" -> {
                // hủy phải trước ngày nhận (BR-LT-06): đặt cho ngày mai
                long id = book(receptionist, counterPet(), DAY.plusDays(1), DAY.plusDays(3));
                data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/cancel", receptionist,
                        Map.of("reason", "Khách đổi ý")), HttpStatus.OK);
                yield id;
            }
            case "NO_SHOW" -> {
                long id = book(receptionist, counterPet(), DAY, DAY.plusDays(2));
                runNoShow(at(DAY, 19, 0));
                yield id;
            }
            default -> throw new IllegalArgumentException(source);
        };
    }

    private ResponseEntity<Map<String, Object>> attempt(String operation, long id) {
        String path = "/api/boarding-bookings/" + id + "/" + operation;
        return switch (operation) {
            case "check-in" -> call(HttpMethod.POST, path, receptionist, checkInBody(spareKennel));
            case "cancel" -> call(HttpMethod.POST, path, receptionist, Map.of("reason", "Thử"));
            case "cancel-no-kennel", "start-checkout", "hand-over" -> call(HttpMethod.POST, path, receptionist, null);
            case "extend" -> call(HttpMethod.POST, path, receptionist,
                    Map.of("checkOutDate", DAY.plusDays(6).toString()));
            case "end-deceased" -> call(HttpMethod.POST, path, receptionist, Map.of("note", "Thử"));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    /** Đặt chỗ hôm nay → {@code outDay}, nhận vào chuồng {@code kennel}: {@code CHECKED_IN}. */
    private long stay(LocalDate out) {
        long id = book(receptionist, counterPet(), DAY, out);
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/check-in", receptionist, checkInBody(kennel)),
                HttpStatus.OK);
        return id;
    }

    private long stayInSpareKennel(LocalDate out) {
        long id = book(receptionist, counterPet(), DAY, out);
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/check-in", receptionist,
                checkInBody(spareKennel)), HttpStatus.OK);
        return id;
    }

    private Map<String, Object> checkInBody(long kennelId) {
        Map<String, Object> body = new HashMap<>();
        body.put("kennelId", kennelId);
        body.put("weightKg", "10.00");
        body.put("healthCondition", "Tỉnh táo");
        body.put("belongings", "Chăn");
        body.put("dietInstructions", "Hạt ướt");
        body.put("emergencyPhone", "0901234567");
        return body;
    }

    private long book(String token, long petId, LocalDate in, LocalDate out) {
        Map<String, Object> body = new HashMap<>();
        body.put("petId", petId);
        body.put("branchId", branch);
        body.put("kennelTypeId", type);
        body.put("checkInDate", in.toString());
        body.put("checkOutDate", out.toString());
        return number(map(data(call(HttpMethod.POST, "/api/boarding-bookings", token, body), HttpStatus.CREATED)
                .get("booking")).get("bookingId"));
    }

    /** Thú mới của khách tại quầy (đã có cân nặng). */
    private long counterPet() {
        long owner = newCounterCustomer(null);
        long petId = newPet(owner, "DOG");
        weigh(petId, "10.00");
        return petId;
    }

    private ResponseEntity<Map<String, Object>> extend(long id, LocalDate out) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/extend", receptionist,
                Map.of("checkOutDate", out.toString()));
    }

    private ResponseEntity<Map<String, Object>> startCheckout(long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/start-checkout", receptionist, null);
    }

    private ResponseEntity<Map<String, Object>> handOver(long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/hand-over", receptionist, null);
    }

    private ResponseEntity<Map<String, Object>> endDeceased(long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/end-deceased", receptionist,
                Map.of("note", "Thú lên cơn co giật, cấp cứu không qua khỏi"));
    }

    /** Mở ca của lễ tân (nếu chưa có) rồi thu đúng tổng Order. */
    private void pay(long orderId) {
        if (jdbc.queryForObject("SELECT count(*) FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN'",
                Long.class, receptionistId) == 0) {
            data(call(HttpMethod.POST, "/api/cashier-shifts", receptionist, Map.of("afterHours", false)),
                    HttpStatus.CREATED);
        }
        data(call(HttpMethod.POST, "/api/payments", receptionist,
                Map.of("orderIds", List.of(orderId), "method", "CASH", "amount", orderTotal(orderId))),
                HttpStatus.CREATED);
    }

    /** Đặt đồng hồ rồi mở lại token (phiên hết hạn theo cùng đồng hồ). */
    private Instant at(LocalDate day, int hour, int minute) {
        Instant instant = instantAt(day, hour, minute);
        clock.set(instant);
        receptionist = token(receptionistId);
        caretaker = token(caretakerId);
        manager = token(managerId);
        customerToken = token(customerAccount);
        return instant;
    }

    private void runNoShow(Instant instant) {
        clock.set(instant);
        noShowJob.run();
    }

    private void runOverdue(Instant instant) {
        clock.set(instant);
        overdueJob.run();
    }

    // ================================================================ helpers — dữ liệu

    private String kennelStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, id);
    }

    private boolean lateCancel(long id) {
        return jdbc.queryForObject("SELECT late_cancel FROM boarding_bookings WHERE id = ?", Boolean.class, id);
    }

    private long petOf(long id) {
        return jdbc.queryForObject("SELECT pet_id FROM boarding_bookings WHERE id = ?", Long.class, id);
    }

    private int count(String table, long bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE boarding_booking_id = ?", Integer.class,
                bookingId);
    }

    private long pickupTasks(long id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM care_tasks WHERE boarding_booking_id = ? AND task_type = 'PICKUP_OVERDUE'
                AND status = 'OPEN'""", Long.class, id);
    }

    private long outboxOf(long... accountIds) {
        long total = 0;
        for (long accountId : accountIds) {
            total += jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_account_id = ?",
                    Long.class, accountId);
        }
        return total;
    }

    private long orderCount(long id) {
        return count("orders", id);
    }

    private long activeOrder(long id) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE boarding_booking_id = ? AND status <> 'CANCELLED'",
                Long.class, id);
    }

    private long orderTotal(long orderId) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, orderId);
    }

    private long sumOfLines(long orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(line_total), 0) FROM order_lines WHERE order_id = ?",
                Long.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    /** Dấu "không ghi gì": phiên bản dòng đặt chỗ và chuồng của chi nhánh, số Order / nhật ký, thông báo của khách. */
    private Map<String, Object> footprint(long id) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("booking", jdbc.queryForObject("SELECT xmin::text FROM boarding_bookings WHERE id = ?", String.class,
                id));
        rows.put("kennels", jdbc.queryForList("SELECT id, status, xmin::text AS version FROM kennels WHERE branch_id = ?"
                + " ORDER BY id", branch));
        rows.put("orders", orderCount(id));
        rows.put("careLogs", count("care_logs", id));
        rows.put("outbox", outboxOf(customerAccount));
        return rows;
    }

    private static Instant instantAt(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(VN).toInstant();
    }
}
