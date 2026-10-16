package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;

/**
 * Đánh dấu đã mất, xóa, chuyển chủ — {@code POST /api/pets/{id}/mark-deceased}, {@code DELETE /api/pets/{id}},
 * {@code POST /api/pets/{id}/transfer-owner} (UC24, UC26; customer-v1 #17–19; BR-KH-05, 06, 08; docs/adr/0041) qua HTTP
 * trên Postgres 17 thật. Một listener thử đứng thay appointment / boarding / care để bắt hai sự kiện (cùng transaction,
 * payload) và để làm rollback khi nó ném lỗi (06 §3). Các race dùng khóa chia sẻ dòng {@code pets} như bên chèn Visit /
 * lịch hẹn phải làm ({@code PetQueryApi.findPetForShare}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PetLifecycleIT.EventProbe.class})
class PetLifecycleIT extends CustomerMeItSupport {

    /** Listener thử: ghi lại sự kiện, có transaction đang chạy không, và có thể ném lỗi theo yêu cầu. */
    static class RecordingListener {
        final List<Object> events = new CopyOnWriteArrayList<>();
        final List<Boolean> inTransaction = new CopyOnWriteArrayList<>();
        volatile boolean failNext;

        @EventListener
        void on(PetDeceasedEvent event) {
            record(event);
        }

        @EventListener
        void on(PetOwnerTransferredEvent event) {
            record(event);
        }

        private void record(Object event) {
            events.add(event);
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("listener của module khác lỗi");
            }
        }

        void reset() {
            events.clear();
            inTransaction.clear();
            failNext = false;
        }
    }

    @TestConfiguration
    static class EventProbe {
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    private static final String TZ = "Asia/Ho_Chi_Minh";

    @Autowired
    private RecordingListener listener;

    private PetFixtures fx;
    private String tag;
    private long account;
    private long customer;
    private String customerToken;
    private long receptionistId;
    private String receptionist;

    @BeforeEach
    void setUp() {
        listener.reset();
        fx = new PetFixtures(jdbc, transactionManager);
        tag = UUID.randomUUID().toString().substring(0, 8);
        account = customerAccount(newEmail());
        customer = fx.track(onlineProfile(account, "Khách " + tag, null, null, null, false));
        customerToken = open(account);
        receptionistId = staffOf("RECEPTIONIST");
        receptionist = open(receptionistId);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_pet_audit ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_pet_audit()");
        fx.cleanup();
    }

    private static String today() {
        return LocalDate.now(ZoneId.of(TZ)).toString();
    }

    private ResponseEntity<Map<String, Object>> mark(String token, long pet, Object body) {
        return call(HttpMethod.POST, "/api/pets/" + pet + "/mark-deceased", token, body);
    }

    private ResponseEntity<Map<String, Object>> transfer(String token, long pet, Object body) {
        return call(HttpMethod.POST, "/api/pets/" + pet + "/transfer-owner", token, body);
    }

    private static Map<String, Object> confirmedToday() {
        return Map.of("deceasedOn", today(), "confirmed", true);
    }

    private long owner(String name) {
        return fx.track(counterProfile(null, name + " " + tag, "0944000" + (100 + (int) (Math.random() * 899)), null));
    }

    // ---------------------------------------------------------------- đánh dấu đã mất

    @Test
    void receptionistMarksAPetDeceasedAndTheEventIsPublishedInTheSameTransaction() {
        long pet = fx.pet(customer, "Mực", "DOG");

        ResponseEntity<Map<String, Object>> response = mark(receptionist, pet, confirmedToday());

        Map<String, Object> data = data(response, HttpStatus.OK);
        assertThat(data).containsEntry("deceasedOn", today()).containsEntry("deletable", false)
                .containsEntry("petId", Math.toIntExact(pet));
        assertThat(jdbc.queryForObject("SELECT deceased_on::text FROM pets WHERE id = ?", String.class, pet))
                .isEqualTo(today());
        assertThat(listener.events).containsExactly(new PetDeceasedEvent(pet, customer,
                LocalDate.parse(today()), receptionistId));
        assertThat(listener.inTransaction).containsExactly(true);
    }

    @Test
    void ownerMarksOwnPetButNotSomeoneElses() {
        long mine = fx.pet(customer, "Của tôi", "DOG");
        long other = owner("Người khác");
        long theirs = fx.pet(other, "Của họ", "DOG");

        data(mark(customerToken, mine, confirmedToday()), HttpStatus.OK);
        assertError(mark(customerToken, theirs, confirmedToday()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, theirs)).isNull();
        assertThat(listener.events).hasSize(1);
    }

    @Test
    void roleMatrixForMarkDeletTransfer() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");
        for (String role : List.of("ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "VET", "CARETAKER")) {
            String token = open(staffOf(role));
            assertError(mark(token, pet, confirmedToday()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.DELETE, "/api/pets/" + pet, token, null), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
            assertError(transfer(token, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(transfer(customerToken, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(mark(null, pet, confirmedToday()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(listener.events).isEmpty();
        assertThat(jdbc.queryForMap("SELECT customer_id, deceased_on FROM pets WHERE id = ?", pet))
                .containsEntry("customer_id", customer).containsEntry("deceased_on", null);
    }

    @Test
    void pendingLinkDecisionBlocksTheCustomerButNotTheReceptionist() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);
        long pet = fx.pet(customer, "Mực", "DOG");

        assertRule(mark(customerToken, pet, confirmedToday()), "BR-TK-19");
        assertRule(call(HttpMethod.DELETE, "/api/pets/" + pet, customerToken, null), "BR-TK-19");
        data(mark(receptionist, pet, confirmedToday()), HttpStatus.OK);
    }

    @Test
    void markingTwiceIs409AndPublishesOnce() {
        long pet = fx.pet(customer, "Mực", "DOG");
        data(mark(receptionist, pet, confirmedToday()), HttpStatus.OK);

        assertError(mark(receptionist, pet, confirmedToday()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(mark(customerToken, pet, confirmedToday()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(listener.events).hasSize(1);
    }

    @Test
    void openVisitOrStayBlocksButFinishedOrBookedDoesNot() {
        long withWaiting = fx.pet(customer, "Chờ", "DOG");
        long withProgress = fx.pet(customer, "Đang khám", "DOG");
        long withStay = fx.pet(customer, "Lưu trú", "DOG");
        long withOverdue = fx.pet(customer, "Quá hạn", "DOG");
        long fine = fx.pet(customer, "Ổn", "DOG");
        fx.visit(customer, withWaiting, "WAITING");
        fx.visit(customer, withProgress, "IN_PROGRESS");
        fx.booking(customer, withStay, "CHECKED_IN", 10);
        fx.booking(customer, withOverdue, "OVERDUE", 20);
        fx.visit(customer, fine, "COMPLETED");
        fx.visit(customer, fine, "CANCELLED");
        fx.booking(customer, fine, "BOOKED", 30);
        fx.booking(customer, fine, "CHECKED_OUT", 40);

        for (long blocked : new long[] {withWaiting, withProgress, withStay, withOverdue}) {
            assertRule(mark(receptionist, blocked, confirmedToday()), "BR-KH-05");
            assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, blocked))
                    .isNull();
        }
        assertThat(listener.events).isEmpty();
        data(mark(receptionist, fine, confirmedToday()), HttpStatus.OK);
        assertThat(listener.events).hasSize(1);
    }

    @Test
    void markRequiresConfirmationAndAValidDate() {
        long pet = fx.pet(customer, "Mực", "DOG");
        jdbc.update("UPDATE pets SET birth_date = '2024-03-15' WHERE id = ?", pet);
        String tomorrow = LocalDate.now(ZoneId.of(TZ)).plusDays(1).toString();

        assertError(mark(receptionist, pet, Map.of("deceasedOn", today(), "confirmed", false)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(mark(receptionist, pet, Map.of("deceasedOn", today())), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(mark(receptionist, pet, Map.of("confirmed", true)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(mark(receptionist, pet, Map.of("deceasedOn", "30/09/2026", "confirmed", true)),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertRule(mark(receptionist, pet, Map.of("deceasedOn", tomorrow, "confirmed", true)), "BR-KH-05");
        assertRule(mark(receptionist, pet, Map.of("deceasedOn", "2024-03-14", "confirmed", true)), "BR-KH-05");
        assertError(mark(receptionist, 999_999_999L, confirmedToday()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(listener.events).isEmpty();
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, pet)).isNull();
    }

    @Test
    void aListenerFailureRollsBackTheDeceasedMark() {
        long pet = fx.pet(customer, "Mực", "DOG");
        listener.failNext = true;

        ResponseEntity<Map<String, Object>> response = mark(receptionist, pet, confirmedToday());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, pet)).isNull();
        data(mark(receptionist, pet, confirmedToday()), HttpStatus.OK);
    }

    @Test
    void aVisitInsertedWhileTheMarkWaitsForThePetLockIsSeenAndRefuses() throws Exception {
        long pet = fx.pet(customer, "Mực", "DOG");

        ResponseEntity<Map<String, Object>> response = whilePetSharedLocked(pet,
                () -> fx.visit(customer, pet, "WAITING"), () -> mark(receptionist, pet, confirmedToday()));

        assertRule(response, "BR-KH-05");
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, pet)).isNull();
        assertThat(listener.events).isEmpty();
    }

    // ---------------------------------------------------------------- xóa

    @Test
    void ownerAndReceptionistDeleteAFreshPetAndItsOwnWeights() {
        long first = fx.pet(customer, "Một", "DOG");
        long second = fx.pet(customer, "Hai", "CAT");
        fx.weight(first, "5.00", "OWNER", "2026-10-01T03:00:00Z");

        ResponseEntity<Map<String, Object>> byOwner = call(HttpMethod.DELETE, "/api/pets/" + first, customerToken,
                null);
        ResponseEntity<Map<String, Object>> byStaff = call(HttpMethod.DELETE, "/api/pets/" + second, receptionist,
                null);

        assertThat(byOwner.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(byOwner.getBody()).isNull();
        assertThat(byStaff.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE customer_id = ?", Long.class, customer))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weight_records WHERE pet_id = ?", Long.class, first))
                .isZero();
        assertError(call(HttpMethod.DELETE, "/api/pets/" + first, receptionist, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void anyTransactionBlocksDeletionAndKeepsTheRow() {
        long viaAppointment = fx.pet(customer, "Lịch", "DOG");
        long viaBooking = fx.pet(customer, "Đặt chỗ", "DOG");
        long viaVisit = fx.pet(customer, "Visit", "DOG");
        long viaOrder = fx.pet(customer, "Order", "DOG");
        fx.appointment(customer, viaAppointment, "CANCELLED");
        fx.booking(customer, viaBooking, "CHECKED_OUT", 10);
        fx.visit(customer, viaVisit, "CANCELLED");
        fx.orderForVisit(customer, fx.visit(customer, viaOrder, "COMPLETED"), "CANCELLED");

        for (long pet : new long[] {viaAppointment, viaBooking, viaVisit, viaOrder}) {
            assertRule(call(HttpMethod.DELETE, "/api/pets/" + pet, receptionist, null), "BR-KH-06");
            assertRule(call(HttpMethod.DELETE, "/api/pets/" + pet, customerToken, null), "BR-KH-06");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ?", Long.class, pet)).isOne();
        }
    }

    @Test
    void deceasedPetIsNotDeletable() {
        long pet = fx.pet(customer, "Mực", "DOG");
        data(mark(receptionist, pet, confirmedToday()), HttpStatus.OK);

        assertRule(call(HttpMethod.DELETE, "/api/pets/" + pet, receptionist, null), "BR-KH-05");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ?", Long.class, pet)).isOne();
    }

    @Test
    void foreignPetCannotBeDeletedByACustomer() {
        long theirs = fx.pet(owner("Người khác"), "Của họ", "DOG");

        assertError(call(HttpMethod.DELETE, "/api/pets/" + theirs, customerToken, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ?", Long.class, theirs)).isOne();
    }

    @Test
    void anAppointmentInsertedWhileTheDeleteWaitsIsSeenAndKeepsThePet() throws Exception {
        long pet = fx.pet(customer, "Mực", "DOG");

        ResponseEntity<Map<String, Object>> response = whilePetSharedLocked(pet,
                () -> fx.appointment(customer, pet, "BOOKED"),
                () -> call(HttpMethod.DELETE, "/api/pets/" + pet, receptionist, null));

        assertRule(response, "BR-KH-06");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ?", Long.class, pet)).isOne();
    }

    // ---------------------------------------------------------------- chuyển chủ

    @Test
    void transferMovesThePetPublishesTheEventAndWritesTheAudit() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long visit = fx.visit(customer, pet, "COMPLETED");
        long order = fx.orderForVisit(customer, visit, "CANCELLED");
        fx.weight(pet, "7.00", "VET", "2026-10-01T03:00:00Z");
        long newOwner = owner("Chủ mới");

        ResponseEntity<Map<String, Object>> response = transfer(receptionist, pet,
                Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true));

        Map<String, Object> data = data(response, HttpStatus.OK);
        assertThat(data).containsEntry("customerId", Math.toIntExact(newOwner)).containsEntry("name", "Mực");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(newOwner);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM orders WHERE id = ?", Long.class, order))
                .as("Order cũ ở lại với chủ cũ").isEqualTo(customer);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM visits WHERE id = ?", Long.class, visit))
                .as("lịch sử khám đi theo thú nhưng Visit giữ chủ lúc khám").isEqualTo(customer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weight_records WHERE pet_id = ?", Long.class, pet))
                .isOne();
        assertThat(listener.events).containsExactly(new PetOwnerTransferredEvent(pet, customer, newOwner,
                receptionistId));
        assertThat(listener.inTransaction).containsExactly(true);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT action, entity_type, actor_account_id, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'pets' AND entity_id = ?
                """, pet);
        assertThat(audit).containsEntry("action", "PET_OWNER_TRANSFERRED").containsEntry("entity_type", "pets")
                .containsEntry("actor_account_id", receptionistId);
        assertThat((String) audit.get("before_data")).contains("\"customerId\"").contains(String.valueOf(customer));
        assertThat((String) audit.get("after_data")).contains(String.valueOf(newOwner));
    }

    @Test
    void theFormerOwnerLosesAccessAndTheNewOwnerGainsIt() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newAccount = customerAccount(newEmail());
        long newOwner = fx.track(onlineProfile(newAccount, "Chủ mới " + tag, null, null, null, false));
        data(transfer(receptionist, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)),
                HttpStatus.OK);

        assertError(call(HttpMethod.GET, "/api/pets/" + pet, customerToken, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.PATCH, "/api/pets/" + pet, customerToken, Map.of("name", "Chiếm")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(call(HttpMethod.GET, "/api/pets/" + pet, open(newAccount), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void transferRefusalsChangeNothingAndPublishNothing() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");
        Map<String, Object> ok = Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true);

        assertError(transfer(receptionist, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", false)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(transfer(receptionist, pet, Map.of("previousOwnerConfirmed", true)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(transfer(receptionist, pet, Map.of("newCustomerId", newOwner)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertRule(transfer(receptionist, pet, Map.of("newCustomerId", customer, "previousOwnerConfirmed", true)),
                "BR-KH-08");
        assertError(transfer(receptionist, pet, Map.of("newCustomerId", 999_999_999L, "previousOwnerConfirmed",
                true)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(transfer(receptionist, 999_999_999L, ok), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        fx.visit(customer, pet, "IN_PROGRESS");
        assertRule(transfer(receptionist, pet, ok), "BR-KH-08");
        jdbc.update("UPDATE visits SET status = 'COMPLETED' WHERE pet_id = ?", pet);
        long done = jdbc.queryForObject("SELECT id FROM visits WHERE pet_id = ?", Long.class, pet);
        fx.orderForVisit(customer, done, "PENDING");
        assertRule(transfer(receptionist, pet, ok), "BR-KH-08");
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE visit_id = ?", done);
        long staying = fx.booking(customer, pet, "CHECKED_IN", 10);
        assertRule(transfer(receptionist, pet, ok), "BR-KH-08");
        jdbc.update("UPDATE boarding_bookings SET status = 'BOOKED', kennel_id = NULL WHERE id = ?", staying);

        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(customer);
        assertThat(listener.events).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'pets' AND entity_id = ?",
                Long.class, pet)).isZero();
        data(transfer(receptionist, pet, ok), HttpStatus.OK);
    }

    @Test
    void deceasedPetCannotBeTransferred() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");
        data(mark(receptionist, pet, confirmedToday()), HttpStatus.OK);
        listener.reset();

        assertRule(transfer(receptionist, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)),
                "BR-KH-05");
        assertThat(listener.events).isEmpty();
    }

    @Test
    void aListenerFailureRollsBackTheTransferAndItsAudit() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");
        listener.failNext = true;

        ResponseEntity<Map<String, Object>> response = transfer(receptionist, pet,
                Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true));

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(customer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'pets' AND entity_id = ?",
                Long.class, pet)).isZero();
    }

    @Test
    void aFailingAuditInsertRollsBackTheTransfer() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");
        jdbc.execute("""
                CREATE FUNCTION it_fail_pet_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'PET_OWNER_TRANSFERRED' THEN
                        RAISE EXCEPTION 'IT: audit insert fails';
                    END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER it_fail_pet_audit BEFORE INSERT ON audit_logs FOR EACH ROW "
                + "EXECUTE FUNCTION it_fail_pet_audit()");

        ResponseEntity<Map<String, Object>> response = transfer(receptionist, pet,
                Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true));

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(customer);
    }

    @Test
    void twoTransfersOfTheSamePetToDifferentOwnersLeaveExactlyOneWinner() throws Exception {
        long pet = fx.pet(customer, "Mực", "DOG");
        long ownerA = owner("Chủ A");
        long ownerB = owner("Chủ B");
        List<Callable<ResponseEntity<Map<String, Object>>>> requests = List.of(
                () -> transfer(receptionist, pet, Map.of("newCustomerId", ownerA, "previousOwnerConfirmed", true)),
                () -> transfer(receptionist, pet, Map.of("newCustomerId", ownerB, "previousOwnerConfirmed", true)));

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(customer, () -> {
        }, requests);

        List<HttpStatus> statuses = new ArrayList<>();
        responses.forEach(r -> statuses.add(HttpStatus.valueOf(r.getStatusCode().value())));
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isIn(ownerA, ownerB);
        assertThat(listener.events).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'pets' AND entity_id = ?",
                Long.class, pet)).isOne();
    }

    @Test
    void aVisitInsertedWhileTheTransferWaitsForThePetLockIsSeenAndRefuses() throws Exception {
        long pet = fx.pet(customer, "Mực", "DOG");
        long newOwner = owner("Chủ mới");

        ResponseEntity<Map<String, Object>> response = whilePetSharedLocked(pet,
                () -> fx.visit(customer, pet, "WAITING"),
                () -> transfer(receptionist, pet, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true)));

        assertRule(response, "BR-KH-08");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(customer);
    }

    // ---------------------------------------------------------------- nội bộ

    /**
     * Đóng vai bên chèn Visit / lịch hẹn theo nghĩa vụ của {@code PetQueryApi.findPetForShare}: giữ {@code FOR SHARE} trên
     * dòng thú, để request của customer đứng chờ khóa ghi, chèn dòng giao dịch rồi mới commit. Request phải thấy dòng đó.
     */
    private ResponseEntity<Map<String, Object>> whilePetSharedLocked(long petId, Runnable insertWhileLocked,
            Callable<ResponseEntity<Map<String, Object>>> request) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> sharer = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM pets WHERE id = ? FOR SHARE", Long.class, petId);
                    held.countDown();
                    try {
                        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return request.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitBlockedOnPetWriteLock();
        assertThat(pending).as("request phải chờ khóa dòng pets").isNotDone();
        insertWhileLocked.run();
        release.countDown();

        sharer.get(30, TimeUnit.SECONDS);
        return pending.get(30, TimeUnit.SECONDS);
    }

    private void awaitBlockedOnPetWriteLock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from pets%for no key update%'
                    """, Long.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("không thấy câu FOR NO KEY UPDATE trên pets đang chờ khóa");
    }
}
