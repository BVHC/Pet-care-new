package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #20 xóa mũi tiêm ghi nhầm trên Postgres thật (T28 phần 3, docs/adr/0075): hoàn đúng lô (kể cả lô đã hết hạn),
 * xóa dòng Order VACCINE, tính lại mũi cũ (BR-TB-03), mọi bên đọc (bệnh án, gợi ý #18, Order, lưu trú) thấy kết quả, mọi
 * guard theo thứ tự hợp đồng (guard sai không ghi gì), quyền theo người phụ trách hiện tại sau khi gán lại (D2),
 * rollback khi từng bước ghi lỗi và race (advisory 1004, hoàn tất lượt, gán lại lượt, xóa đôi, ghi mũi #19 ở lượt khác).
 * Mũi dựng bằng chính #19 để dữ liệu đúng thực tế. Đồng hồ {@link MutableClock} ở <b>năm 2015</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccinationDeleteIT.TestBeans.class})
class VaccinationDeleteIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2015, 5, 10);
    private static final Instant T0 = TODAY.atTime(9, 0).atZone(VN).toInstant();
    private static final String RECORD = "/api/visits/%d/vaccinations";
    private static final String DELETE = "/api/visits/%d/vaccinations/%d";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Trạng thái đếm được của luồng: guard sai / rollback phải để nguyên. */
    private record State(long doses, long superseded, long lines, long movements, long lotQuantity, long openTasks,
            long orderTotal, String orderXmin, String visitXmin) {
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private VaccinationQueryApi vaccinationQuery;

    private AppointmentItSupport data;
    private VaccinationItSupport vaccines;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> services = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long vet;
    private long otherVet;
    private long foreignVet;
    private long customer;
    private long pet;
    private long otherPet;
    private long visit;
    private long order;
    private long dogType;
    private long dose1;
    private long dose2;
    private long product;
    private long lotNear;
    private long lotFar;
    private String vetToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        vaccines = new VaccinationItSupport(jdbc);
        branch = branch();
        otherBranch = branch();
        vet = data.staff("VET", branch);
        otherVet = data.staff("VET", branch);
        foreignVet = data.staff("VET", otherBranch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
        otherPet = data.pet(customer);
        visit = visit(branch, "MEDICAL", "IN_PROGRESS", vet, pet);
        order = vaccines.orderOf(visit, vet);
        vetToken = token(vet);

        dogType = vaccines.vaccineType("DOG", "Dại IT15");
        dose1 = vaccines.protocol("DOG", dogType, 1, 21, false);
        dose2 = vaccines.protocol("DOG", dogType, 2, 30, false);
        product = vaccines.product(dogType, "Rabisin", true);
        // lô hạn xa có id nhỏ hơn: FEFO phải chọn lô hạn gần (lotNear) cho mũi đầu tiên
        lotFar = vaccines.lot(branch, product, TODAY.plusDays(100), 5);
        lotNear = vaccines.lot(branch, product, TODAY.plusDays(10), 3);
    }

    @AfterEach
    void tearDown() {
        vaccines.deleteDosesOfPet(pet);
        vaccines.deleteDosesOfPet(otherPet);
        vaccines.deleteDoses();
        for (long b : branches) {
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        for (long id : services) {
            jdbc.update("DELETE FROM services WHERE id = ?", id);
        }
        vaccines.deleteCatalog();
        data.cleanUp();
    }

    // ================================================================ đường đúng

    @Test
    void deletesTheDoseHandsTheSameLotBackEvenWhenItExpiredAndEveryReaderSeesIt() {
        long dose = record(visit);
        long line = lineOf(dose);
        assertThat(orderTotal()).isEqualTo(150_000);
        assertThat(quantity(lotNear)).isEqualTo(2);
        // lô hết hạn sau khi tiêm: hoàn kho vẫn về đúng lô đó (BR-KB-04)
        jdbc.update("UPDATE stock_lots SET expiry_date = ? WHERE id = ?", TODAY.minusDays(1), lotNear);

        ResponseEntity<Map<String, Object>> response = delete(vetToken, visit, dose);

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(doses()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE id = ?", Long.class, line)).isZero();
        assertThat(orderTotal()).isZero();
        assertThat(quantity(lotNear)).as("đúng lô đã trừ").isEqualTo(3);
        assertThat(quantity(lotFar)).isEqualTo(5);
        List<Map<String, Object>> movements = jdbc.queryForList(
                "SELECT * FROM stock_movements WHERE source_type = 'vaccinations' AND source_id = ? ORDER BY id", dose);
        assertThat(movements).extracting(m -> m.get("movement_type")).containsExactly("VACCINATION",
                "VACCINATION_REVERT");
        assertThat(movements.get(1)).containsEntry("stock_lot_id", lotNear).containsEntry("quantity_delta", 1)
                .containsEntry("balance_after", 3).containsEntry("created_by", vet);
        assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)).isEqualTo("OPEN");

        // bệnh án (#12), gợi ý (#18), Order đọc lại không còn dấu vết của mũi
        Map<String, Object> record = data(get(vetToken, "/api/visits/" + visit + "/medical-record"), HttpStatus.OK);
        assertThat(listOf(record.get("vaccinations"))).isEmpty();
        Map<String, Object> options = data(
                get(vetToken, "/api/visits/" + visit + "/vaccination-options?vaccineTypeId=" + dogType), HttpStatus.OK);
        assertThat(options).containsEntry("lastDose", null);
        Map<String, Object> view = data(get(vetToken, "/api/orders/" + order), HttpStatus.OK);
        assertThat(view).containsEntry("totalAmount", 0);
        assertThat(listOf(view.get("lines"))).extracting(l -> l.get("lineType")).doesNotContain("VACCINE");
    }

    @Test
    void deletingTheLatestDoseRestoresThePreviousOneAndTheCancelledTaskStaysCancelled() {
        long earlier = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet, pet);
        long lotOther = vaccines.lot(otherBranch, product, TODAY.plusDays(30), 1);
        long older = vaccines.dose(earlier, dogType, dose1, 1, product, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, null);
        Instant already = T0.minusSeconds(86_400);
        long otherTypeDose = vaccines.dose(earlier, vaccines.vaccineType("DOG", "5 bệnh IT15"), dose1, 1, product,
                lotOther, TODAY.minusDays(30), TODAY.minusDays(9), foreignVet, already);
        long otherPetVisit = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet, otherPet);
        long otherPetDose = vaccines.dose(otherPetVisit, dogType, dose1, 1, product, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, already);
        long dueOpen = vaccines.careTask(older, pet, "VACCINE_DUE", "OPEN");
        long dueDone = vaccines.careTask(older, pet, "VACCINE_DUE", "DONE");

        long newer = record(visit);
        assertThat(supersededAt(older)).isEqualTo(T0);
        assertThat(taskStatus(dueOpen)).isEqualTo("CANCELLED");

        assertThat(delete(vetToken, visit, newer).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(supersededAt(older)).as("mũi cũ hết bị thay thế").isNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM vaccinations WHERE id = ?", Timestamp.class, older)
                .toInstant()).as("updated_at từ Clock").isEqualTo(T0);
        assertThat(taskStatus(dueOpen)).as("Care Task CANCELLED là trạng thái cuối").isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, dueOpen))
                .isEqualTo("Đã tiêm lại (BR-TB-03)");
        assertThat(taskStatus(dueDone)).isEqualTo("DONE");
        assertThat(supersededAt(otherTypeDose)).as("loại khác không đổi").isEqualTo(already);
        assertThat(supersededAt(otherPetDose)).as("thú khác không đổi").isEqualTo(already);
        assertThat(quantity(lotOther)).as("lô của mũi cũ không đổi").isEqualTo(1);

        // ghi lại sau khi xóa: mũi cũ được thay thế đúng một lần nữa, chỉ còn một mũi chưa thay thế
        long again = record(visit);
        assertThat(supersededAt(older)).isEqualTo(T0);
        assertThat(jdbc.queryForList("SELECT id FROM vaccinations WHERE pet_id = ? AND vaccine_type_id = ? "
                + "AND superseded_at IS NULL", Long.class, pet, dogType)).containsExactly(again);
    }

    @Test
    void deletingASupersededDoseChangesNoOtherDose() {
        long first = record(visit);
        long second = record(visit);
        assertThat(supersededAt(first)).isEqualTo(T0);
        String secondXmin = jdbc.queryForObject("SELECT xmin::text FROM vaccinations WHERE id = ?", String.class, second);

        assertThat(delete(vetToken, visit, first).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForList("SELECT id FROM vaccinations WHERE pet_id = ?", Long.class, pet))
                .containsExactly(second);
        assertThat(supersededAt(second)).isNull();
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM vaccinations WHERE id = ?", String.class, second))
                .as("mũi mới nhất không bị ghi").isEqualTo(secondXmin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'VACCINE'",
                Long.class, order)).isEqualTo(1);
        assertThat(orderTotal()).isEqualTo(150_000);
        assertThat(quantity(lotNear)).as("hai mũi trừ 2, xóa mũi đầu hoàn 1").isEqualTo(2);
    }

    @Test
    void deletingTheOnlyDoseOfATypeLeavesNoDoseAndTheBoardingGapReappears() {
        jdbc.update("UPDATE vaccination_protocols SET required_for_boarding = true WHERE id = ?", dose1);
        assertThat(gapTypes()).contains(dogType);
        long dose = record(visit);
        assertThat(gapTypes()).as("có mũi còn hạn").doesNotContain(dogType);

        assertThat(delete(vetToken, visit, dose).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(doses()).isZero();
        assertThat(gapTypes()).as("không còn mũi nào tại hệ thống").contains(dogType);
    }

    @Test
    void aVaccineOnlyVisitCannotCompleteAfterItsOnlyDoseIsDeleted() {
        long vaccineService = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'VACCINE', 120000) RETURNING id""", Long.class, "Tiêm " + UUID.randomUUID());
        services.add(vaccineService);
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'SERVICE', ?, 'Tiêm', 1, 120000, 120000, ?, ?)""",
                order, vaccineService, vet, vet);
        long dose = record(visit);

        assertThat(delete(vetToken, visit, dose).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertRule(exchange(vetToken, HttpMethod.POST, "/api/visits/" + visit + "/complete", null), "BR-KB-02");
        assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit))
                .isEqualTo("IN_PROGRESS");
        record(visit);
        data(exchange(vetToken, HttpMethod.POST, "/api/visits/" + visit + "/complete", null), HttpStatus.OK);
    }

    // ================================================================ đầu vào, xác thực, phân quyền

    @Test
    void malformedPathsAre400BeforeTheRoleAndNothingIsWritten() {
        long dose = record(visit);
        State before = state();

        for (String id : List.of("abc", "1.5", "99999999999999999999")) {
            ResponseEntity<Map<String, Object>> badVisit = exchange(vetToken, HttpMethod.DELETE,
                    "/api/visits/" + id + "/vaccinations/" + dose, null);
            assertError(badVisit, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(badVisit.getBody()).containsEntry("message", "Tham số 'visitId' sai kiểu dữ liệu");
            ResponseEntity<Map<String, Object>> badDose = exchange(vetToken, HttpMethod.DELETE,
                    "/api/visits/" + visit + "/vaccinations/" + id, null);
            assertError(badDose, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            assertThat(badDose.getBody()).containsEntry("message", "Tham số 'vaccinationId' sai kiểu dữ liệu");
        }
        // sai kiểu đứng trước phân quyền: lễ tân cũng nhận 400, không phải 403
        assertError(exchange(token(data.staff("RECEPTIONIST", branch)), HttpMethod.DELETE,
                "/api/visits/" + visit + "/vaccinations/abc", null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void unauthenticatedExpiredSessionAndMustChangePasswordAreRejected() {
        long dose = record(visit);
        State before = state();

        assertError(delete(null, visit, dose), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        ResponseEntity<Map<String, Object>> mustChange = delete(vetToken, visit, dose);
        assertThat(mustChange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) mustChange.getBody().get("message")).endsWith("(BR-TK-17)");
        // BR-TK-17 đứng trước cả kiểm tra kiểu của path
        assertThat(exchange(vetToken, HttpMethod.DELETE, "/api/visits/abc/vaccinations/abc", null).getBody())
                .containsEntry("message", mustChange.getBody().get("message"));
        jdbc.update("UPDATE accounts SET must_change_password = false WHERE id = ?", vet);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", vet);
        assertError(delete(vetToken, visit, dose), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void rolesOutsideTheContractAre403() {
        long dose = record(visit);
        State before = state();

        for (String role : List.of("CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER")) {
            assertError(delete(token(data.staff(role, branch)), visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("SUPER_MANAGER", "ADMIN")) {
            assertError(delete(token(data.staff(role, null)), visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(delete(token(data.customerAccount()), visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void visitGuardsAreCheckedInTheContractOrder() {
        long dose = record(visit);
        State before = state();

        assertError(delete(vetToken, Long.MAX_VALUE, dose), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(delete(token(foreignVet), visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long grooming = visit(branch, "GROOMING", "IN_PROGRESS", vet, pet);
        assertError(delete(vetToken, grooming, dose), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(delete(token(otherVet), visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long unassigned = visit(branch, "MEDICAL", "WAITING", null, pet);
        assertError(delete(vetToken, unassigned, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long completed = visit(branch, "MEDICAL", "COMPLETED", vet, pet);
        // người phụ trách (403) đứng trước trạng thái (409), trạng thái (409) đứng trước mũi (404)
        assertError(delete(token(otherVet), completed, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(delete(vetToken, completed, 987_654_321L), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(state()).isEqualTo(before);
        for (String status : List.of("WAITING", "COMPLETED", "CANCELLED")) {
            jdbc.update("UPDATE visits SET status = ? WHERE id = ?", status, visit);
            assertError(delete(vetToken, visit, dose), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        }
        jdbc.update("UPDATE visits SET status = 'IN_PROGRESS' WHERE id = ?", visit);

        // mũi không có / mũi của lượt khác: 404, không lộ sự tồn tại
        assertError(delete(vetToken, visit, 987_654_321L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        long visit2 = visit(branch, "MEDICAL", "IN_PROGRESS", vet, pet);
        vaccines.orderOf(visit2, vet);
        long doseOfVisit2 = record(visit2);
        State beforeForeignDose = state();
        assertError(delete(vetToken, visit, doseOfVisit2), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(state()).isEqualTo(beforeForeignDose);
    }

    @Test
    void afterReassignOnlyTheNewAssigneeMayDeleteTheOldAssigneesDose() {
        long dose = record(visit);
        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit);
        State before = state();

        assertError(delete(vetToken, visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo(before);

        assertThat(delete(token(otherVet), visit, dose).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(doses()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT created_by FROM stock_movements
                WHERE source_type = 'vaccinations' AND source_id = ? AND movement_type = 'VACCINATION_REVERT'""",
                Long.class, dose)).isEqualTo(otherVet);
    }

    @Test
    void aLockedVetIs401AndATransferredVetIs403() {
        long dose = record(visit);
        State before = state();

        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertError(delete(vetToken, visit, dose), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET is_locked = false WHERE id = ?", vet);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, vet);
        assertError(delete(vetToken, visit, dose), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branch, vet);

        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ rollback khi một bước ghi lỗi

    @Test
    void aFailureRemovingTheOrderLineRestoresTheDose() {
        long dose = record(visit);
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("DELETE", "order_lines",
                "OLD.line_type = 'VACCINE'", () -> delete(vetToken, visit, dose));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccinations WHERE id = ?", Long.class, dose)).isEqualTo(1);
    }

    @Test
    void aFailureInTheRevertMovementRollsBackTheDoseAndTheLine() {
        long dose = record(visit);
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "stock_movements",
                "NEW.movement_type = 'VACCINATION_REVERT'", () -> delete(vetToken, visit, dose));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aFailureRestoringTheOlderDoseRollsBackEverythingIncludingTheStock() {
        long first = record(visit);
        long second = record(visit);
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "vaccinations",
                "OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS NULL", () -> delete(vetToken, visit, second));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
        assertThat(supersededAt(first)).isEqualTo(T0);
        assertThat(supersededAt(second)).isNull();
    }

    @Test
    void aCareTaskPointingAtTheDoseIsA409AndNothingChanges() {
        long dose = record(visit);
        vaccines.careTask(dose, pet, "VACCINE_DUE", "OPEN");
        State before = state();

        assertError(delete(vetToken, visit, dose), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");

        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ race

    @Test
    void aSecondDeleteOfTheSameDoseIs404AndTheLotIsRestoredOnce() throws Exception {
        long dose = record(visit);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT pg_advisory_xact_lock(?, ?)::text", 1004, (int) (pet % Integer.MAX_VALUE)),
                "%pg_advisory_xact_lock%", j -> {
                }, List.of(() -> delete(vetToken, visit, dose), () -> delete(vetToken, visit, dose)));

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
        assertThat(doses()).isZero();
        assertThat(quantity(lotNear)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements
                WHERE source_type = 'vaccinations' AND source_id = ? AND movement_type = 'VACCINATION_REVERT'""",
                Long.class, dose)).isEqualTo(1);
    }

    @Test
    void aCompletionCommittedWhileTheDeleteWaitsOnTheVisitLockMakesItA409WithNoWrite() throws Exception {
        long dose = record(visit);
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET status = 'COMPLETED', completed_at = ? WHERE id = ?", Timestamp.from(T0),
                        visit),
                List.of(() -> delete(vetToken, visit, dose)));

        assertError(results.get(0), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        State after = state();
        assertThat(after.doses()).isEqualTo(before.doses());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.movements()).isEqualTo(before.movements());
        assertThat(after.lotQuantity()).isEqualTo(before.lotQuantity());
        assertThat(after.orderTotal()).isEqualTo(before.orderTotal());
    }

    @Test
    void aReassignCommittedWhileTheDeleteWaitsMakesTheOldAssignee403WithNoWrite() throws Exception {
        long dose = record(visit);
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit),
                List.of(() -> delete(vetToken, visit, dose)));

        assertError(results.get(0), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        State after = state();
        assertThat(after.doses()).isEqualTo(before.doses());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.movements()).isEqualTo(before.movements());
        assertThat(after.lotQuantity()).isEqualTo(before.lotQuantity());
    }

    @Test
    void aRecordInAnotherVisitOfTheSamePetQueuesOnTheAdvisoryLockAndLeavesExactlyOneUnsupersededDose()
            throws Exception {
        long dose = record(visit);
        long visit2 = visit(branch, "MEDICAL", "IN_PROGRESS", vet, pet);
        vaccines.orderOf(visit2, vet);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT pg_advisory_xact_lock(?, ?)::text", 1004, (int) (pet % Integer.MAX_VALUE)),
                "%pg_advisory_xact_lock%", j -> {
                }, List.of(() -> delete(vetToken, visit, dose), () -> exchange(vetToken, HttpMethod.POST,
                        RECORD.formatted(visit2), body())));

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.CREATED);
        assertThat(doses()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM vaccinations WHERE pet_id = ? AND superseded_at IS NULL",
                Long.class, pet)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT visit_id FROM vaccinations WHERE pet_id = ?", Long.class, pet))
                .isEqualTo(visit2);
        assertThat(quantity(lotNear)).as("mũi đã xóa hoàn, mũi mới trừ").isEqualTo(2);
    }

    // ================================================================ helpers

    /** Ghi một mũi bằng chính #19 (mũi 2 của phác đồ) và trả id. */
    private long record(long visitId) {
        Map<String, Object> result = data(exchange(vetToken, HttpMethod.POST, RECORD.formatted(visitId), body()),
                HttpStatus.CREATED);
        return ((Number) ((Map<?, ?>) result.get("vaccination")).get("vaccinationId")).longValue();
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vaccineTypeId", dogType);
        body.put("protocolId", dose2);
        body.put("productId", product);
        return body;
    }

    private State state() {
        String lots = lotNear + ", " + lotFar;
        return new State(doses(),
                jdbc.queryForObject("SELECT count(*) FROM vaccinations WHERE pet_id = ? AND superseded_at IS NOT NULL",
                        Long.class, pet),
                jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class, order),
                jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id IN (" + lots + ")",
                        Long.class),
                jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM stock_lots WHERE id IN (" + lots + ")",
                        Long.class),
                jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE pet_id = ? AND status = 'OPEN'", Long.class,
                        pet),
                orderTotal(),
                jdbc.queryForObject("SELECT xmin::text FROM orders WHERE id = ?", String.class, order),
                jdbc.queryForObject("SELECT xmin::text FROM visits WHERE id = ?", String.class, visit));
    }

    private long doses() {
        return jdbc.queryForObject("SELECT count(*) FROM vaccinations WHERE pet_id = ?", Long.class, pet);
    }

    private long orderTotal() {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, order);
    }

    private long lineOf(long vaccinationId) {
        return jdbc.queryForObject("SELECT order_line_id FROM vaccinations WHERE id = ?", Long.class, vaccinationId);
    }

    private long quantity(long lot) {
        return jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Long.class, lot);
    }

    private Instant supersededAt(long vaccinationId) {
        Timestamp at = jdbc.queryForObject("SELECT superseded_at FROM vaccinations WHERE id = ?", Timestamp.class,
                vaccinationId);
        return at == null ? null : at.toInstant();
    }

    private String taskStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM care_tasks WHERE id = ?", String.class, id);
    }

    private List<Long> gapTypes() {
        return tx.execute(s -> vaccinationQuery.findBoardingVaccineGaps(pet, TODAY)).stream()
                .map(BoardingVaccineGap::vaccineTypeId).toList();
    }

    private long branch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    /** Lượt chèn thẳng (không qua tiếp nhận); {@code assignee} null chỉ khi {@code WAITING}. */
    private long visit(long branchId, String group, String status, Long assignee, long petId) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customer, petId, group, status,
                Timestamp.from(T0), assignee, vet, Timestamp.from(T0),
                "WAITING".equals(status) ? null : Timestamp.from(T0),
                "COMPLETED".equals(status) ? Timestamp.from(T0) : null);
    }

    /** Trigger tạm từ chối INSERT / UPDATE / DELETE (23514 = check_violation → 409), giới hạn bằng {@code condition}. */
    private <T> T withRejectingTrigger(String event, String table, String condition, Supplier<T> action) {
        String name = "it_reject_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table + " FOR EACH ROW WHEN ("
                + condition + ") EXECUTE FUNCTION " + name + "()");
        try {
            return action.get();
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON " + table);
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    /**
     * Một luồng giữ khóa ({@code lock}) trong transaction riêng; các request bắn lần lượt, mỗi request phải đứng chờ
     * (pg_stat_activity, {@code waitingLike}) trước request kế tiếp. Rồi luồng giữ khóa chạy {@code beforeRelease} trong
     * chính transaction của nó và commit (khuôn {@code VaccinationRecordIT.whileHeld}).
     */
    private List<ResponseEntity<Map<String, Object>>> whileHeld(Consumer<JdbcTemplate> lock, String waitingLike,
            Consumer<JdbcTemplate> beforeRelease, List<Callable<ResponseEntity<Map<String, Object>>>> requests)
            throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                lock.accept(jdbc);
                held.countDown();
                await(release);
                beforeRelease.accept(jdbc);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(waitingLike, calls.size());
            }
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                assertThat(call.isDone()).as("request phải chờ khóa").isFalse();
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(String like, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE ?""", Long.class, like);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy " + count + " request chờ khóa (" + like + ")");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private ResponseEntity<Map<String, Object>> delete(String bearer, long visitId, long vaccinationId) {
        return exchange(bearer, HttpMethod.DELETE, DELETE.formatted(visitId, vaccinationId), null);
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, String path) {
        return exchange(bearer, HttpMethod.GET, path, null);
    }

    private ResponseEntity<Map<String, Object>> exchange(String bearer, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (body instanceof String) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    /** 400 {@code BUSINESS_RULE_VIOLATION} kèm mã rule ở cuối message. */
    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VaccinationDeleteIT")).accessToken();
    }
}
