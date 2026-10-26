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
import com.petcare.support.MutableClock;

/**
 * visit-v1 #19 ghi nhận mũi tiêm trên Postgres thật (T28 phần 2, docs/adr/0074): giá trị chụp, dòng Order VACCINE, trừ
 * lô FEFO + movement, mũi cũ / Care Task, mọi guard và thứ tự lỗi (guard sai không ghi gì), rollback khi một bước ghi
 * lỗi, race (advisory 1004, hoàn tất lượt, lô cạn, sản phẩm ngừng kinh doanh) và hoàn tất lượt chỉ tiêm. Lượt chèn bằng
 * SQL. Đồng hồ {@link MutableClock} ở <b>năm 2014</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccinationRecordIT.TestBeans.class})
class VaccinationRecordIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2014, 5, 10);
    private static final Instant T0 = TODAY.atTime(9, 0).atZone(VN).toInstant();
    private static final String PATH = "/api/visits/%d/vaccinations";
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
            String orderXmin, String visitXmin) {
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
    private long lotExpired;
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

        dogType = vaccines.vaccineType("DOG", "Dại IT14");
        dose1 = vaccines.protocol("DOG", dogType, 1, 21, false);
        dose2 = vaccines.protocol("DOG", dogType, 2, 30, false);
        product = vaccines.product(dogType, "Rabisin", true);
        // lô hạn xa có id nhỏ hơn: FEFO phải chọn theo hạn dùng, không theo id
        lotFar = vaccines.lot(branch, product, TODAY.plusDays(100), 5);
        lotNear = vaccines.lot(branch, product, TODAY.plusDays(10), 3);
        lotExpired = vaccines.lot(branch, product, TODAY.minusDays(1), 9);
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
    void recordsTheDoseChargesTheOrderTakesTheNearestExpiringLotAndSupersedesTheOlderDoses() {
        long earlier = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet, pet);
        long lotOther = vaccines.lot(otherBranch, product, TODAY.plusDays(30), 1);
        long older = vaccines.dose(earlier, dogType, dose1, 1, product, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, null);
        long olderToo = vaccines.dose(earlier, dogType, dose1, 1, product, lotOther, TODAY.minusDays(60),
                TODAY.minusDays(39), foreignVet, null);
        Instant alreadySuperseded = T0.minusSeconds(86_400);
        long oldest = vaccines.dose(earlier, dogType, dose1, 1, product, lotOther, TODAY.minusDays(90),
                TODAY.minusDays(69), foreignVet, alreadySuperseded);
        long otherTypeDose = vaccines.dose(earlier, vaccines.vaccineType("DOG", "5 bệnh IT14"), dose1, 1, product,
                lotOther, TODAY.minusDays(30), TODAY.minusDays(9), foreignVet, null);
        long otherPetVisit = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet, otherPet);
        long otherPetDose = vaccines.dose(otherPetVisit, dogType, dose1, 1, product, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, null);
        long dueOpen = vaccines.careTask(older, pet, "VACCINE_DUE", "OPEN");
        long dueDone = vaccines.careTask(older, pet, "VACCINE_DUE", "DONE");
        long overdueOpen = vaccines.careTask(olderToo, pet, "VACCINE_OVERDUE", "OPEN");
        long otherPetTask = vaccines.careTask(otherPetDose, otherPet, "VACCINE_DUE", "OPEN");
        long otherTypeTask = vaccines.careTask(otherTypeDose, pet, "VACCINE_DUE", "OPEN");

        ResponseEntity<Map<String, Object>> response = post(vetToken, visit, body());

        Map<String, Object> result = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã ghi nhận mũi tiêm").containsEntry("code", 201);
        assertThat(result.keySet()).containsExactlyInAnyOrder("vaccination", "warnings");
        assertThat(result).containsEntry("warnings", List.of());
        long id = jdbc.queryForObject("SELECT id FROM vaccinations WHERE visit_id = ? AND pet_id = ?", Long.class, visit,
                pet);
        long line = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND line_type = 'VACCINE'",
                Long.class, order);
        @SuppressWarnings("unchecked")
        Map<String, Object> dose = (Map<String, Object>) result.get("vaccination");
        assertThat(dose.keySet()).containsExactlyInAnyOrder("vaccinationId", "visitId", "branchId", "vaccineTypeId",
                "vaccineTypeName", "protocolId", "doseNumber", "productId", "productName", "lotNumber", "expiryDate",
                "administeredOn", "nextDueDate", "superseded", "recordedBy", "orderLineId");
        assertThat(dose).containsEntry("vaccinationId", (int) id).containsEntry("visitId", (int) visit)
                .containsEntry("branchId", (int) branch).containsEntry("vaccineTypeId", (int) dogType)
                .containsEntry("vaccineTypeName", nameOf("vaccine_types", dogType))
                .containsEntry("protocolId", (int) dose2).containsEntry("doseNumber", 2)
                .containsEntry("productId", (int) product).containsEntry("productName", nameOf("products", product))
                .containsEntry("lotNumber", null).containsEntry("expiryDate", null)
                .containsEntry("administeredOn", TODAY.toString())
                .containsEntry("nextDueDate", TODAY.plusDays(30).toString()).containsEntry("superseded", false)
                .containsEntry("recordedBy", (int) vet).containsEntry("orderLineId", (int) line);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM vaccinations WHERE id = ?", id);
        assertThat(row).containsEntry("pet_id", pet).containsEntry("vaccine_type_id", dogType)
                .containsEntry("visit_id", visit).containsEntry("branch_id", branch)
                .containsEntry("protocol_id", dose2).containsEntry("dose_number", 2)
                .containsEntry("product_id", product).containsEntry("stock_lot_id", lotNear)
                .containsEntry("order_line_id", line).containsEntry("recorded_by", vet)
                .containsEntry("due_reminded_at", null).containsEntry("superseded_at", null);
        assertThat(row.get("administered_on").toString()).isEqualTo(TODAY.toString());
        assertThat(row.get("next_due_date").toString()).isEqualTo(TODAY.plusDays(30).toString());

        Map<String, Object> orderLine = jdbc.queryForMap("SELECT * FROM order_lines WHERE id = ?", line);
        assertThat(orderLine).containsEntry("product_id", product).containsEntry("quantity", 1)
                .containsEntry("unit_price", 150_000L).containsEntry("line_total", 150_000L)
                .containsEntry("owner_account_id", vet).containsEntry("added_by", vet);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit))
                .isEqualTo("IN_PROGRESS");

        assertThat(quantity(lotNear)).isEqualTo(2);
        assertThat(quantity(lotFar)).isEqualTo(5);
        assertThat(quantity(lotExpired)).isEqualTo(9);
        List<Map<String, Object>> movements = jdbc.queryForList("SELECT * FROM stock_movements WHERE source_id = ?"
                + " AND source_type = 'vaccinations'", id);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0)).containsEntry("stock_lot_id", lotNear).containsEntry("movement_type", "VACCINATION")
                .containsEntry("quantity_delta", -1).containsEntry("balance_after", 2).containsEntry("created_by", vet);

        assertThat(supersededAt(older)).isEqualTo(T0);
        assertThat(supersededAt(olderToo)).isEqualTo(T0);
        assertThat(supersededAt(oldest)).as("mũi đã thay thế giữ nguyên").isEqualTo(alreadySuperseded);
        assertThat(supersededAt(otherTypeDose)).as("loại vaccine khác").isNull();
        assertThat(supersededAt(otherPetDose)).as("thú khác").isNull();
        assertThat(supersededAt(id)).isNull();

        assertThat(taskStatus(dueOpen)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, dueOpen))
                .isEqualTo("Đã tiêm lại (BR-TB-03)");
        assertThat(taskStatus(overdueOpen)).isEqualTo("CANCELLED");
        assertThat(taskStatus(dueDone)).as("task đã xong giữ nguyên").isEqualTo("DONE");
        assertThat(taskStatus(otherPetTask)).isEqualTo("OPEN");
        assertThat(taskStatus(otherTypeTask)).isEqualTo("OPEN");

        Map<String, Object> record = data(exchange(vetToken, HttpMethod.GET, "/api/visits/" + visit + "/medical-record",
                null), HttpStatus.OK);
        assertThat(listOf(record.get("vaccinations"))).extracting(d -> d.get("vaccinationId")).containsExactly((int) id);
    }

    @Test
    void theNearestExpiryLotIsTakenNotTheLowestIdAndAnExpiredLotIsNeverTaken() {
        jdbc.update("UPDATE stock_lots SET quantity = 0 WHERE id = ?", lotNear);

        data(post(vetToken, visit, body()), HttpStatus.CREATED);

        assertThat(quantity(lotFar)).isEqualTo(4);
        assertThat(quantity(lotExpired)).isEqualTo(9);
    }

    @Test
    void clientFieldsForDateBranchLotAndPetAreIgnored() {
        Map<String, Object> body = body();
        body.put("administeredOn", "2000-01-01");
        body.put("branchId", otherBranch);
        body.put("stockLotId", lotFar);
        body.put("petId", otherPet);
        body.put("recordedBy", otherVet);

        data(post(vetToken, visit, body), HttpStatus.CREATED);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM vaccinations WHERE visit_id = ?", visit);
        assertThat(row).containsEntry("pet_id", pet).containsEntry("branch_id", branch)
                .containsEntry("stock_lot_id", lotNear).containsEntry("recorded_by", vet);
        assertThat(row.get("administered_on").toString()).isEqualTo(TODAY.toString());
    }

    @Test
    void aSecondIdenticalPostRecordsASecondDoseAndSupersedesTheFirst() {
        data(post(vetToken, visit, body()), HttpStatus.CREATED);
        data(post(vetToken, visit, body()), HttpStatus.CREATED);

        List<Long> ids = jdbc.queryForList("SELECT id FROM vaccinations WHERE pet_id = ? ORDER BY id", Long.class, pet);
        assertThat(ids).hasSize(2);
        assertThat(supersededAt(ids.get(0))).isEqualTo(T0);
        assertThat(supersededAt(ids.get(1))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'VACCINE'",
                Long.class, order)).isEqualTo(2);
        assertThat(quantity(lotNear)).isEqualTo(1);
    }

    @Test
    void underMinAgeIsAWarningAndTheDoseIsStillRecorded() {
        setBirthDate(TODAY.minusWeeks(6).plusDays(1));

        Map<String, Object> result = data(post(vetToken, visit, body()), HttpStatus.CREATED);

        assertThat(result).containsEntry("warnings", List.of("UNDER_MIN_AGE"));
        assertThat(doses()).isEqualTo(1);
        setBirthDate(TODAY.minusWeeks(6));
        assertThat(data(post(vetToken, visit, body()), HttpStatus.CREATED)).as("đúng 6 tuần")
                .containsEntry("warnings", List.of());
        setBirthDate(null);
        assertThat(data(post(vetToken, visit, body()), HttpStatus.CREATED)).as("không ngày sinh")
                .containsEntry("warnings", List.of());
    }

    @Test
    void aVaccineOnlyVisitCompletesOnlyAfterADoseIsRecordedAndTheVaccineLineCannotBeDeletedDirectly() {
        long vaccineService = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'VACCINE', 120000) RETURNING id""", Long.class, "Tiêm " + UUID.randomUUID());
        services.add(vaccineService);
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'SERVICE', ?, 'Tiêm', 1, 120000, 120000, ?, ?)""",
                order, vaccineService, vet, vet);

        ResponseEntity<Map<String, Object>> early = exchange(vetToken, HttpMethod.POST,
                "/api/visits/" + visit + "/complete", null);
        assertError(early, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) early.getBody().get("message")).endsWith("(BR-KB-02)");

        data(post(vetToken, visit, body()), HttpStatus.CREATED);
        long line = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND line_type = 'VACCINE'",
                Long.class, order);
        ResponseEntity<Map<String, Object>> direct = exchange(vetToken, HttpMethod.DELETE,
                "/api/orders/" + order + "/lines/" + line, null);
        assertError(direct, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) direct.getBody().get("message")).endsWith("(BR-BH-02)");

        data(exchange(vetToken, HttpMethod.POST, "/api/visits/" + visit + "/complete", null), HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PENDING");
        State completed = state();

        assertError(post(vetToken, visit, body()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(state()).isEqualTo(completed);
    }

    // ================================================================ guard và thứ tự lỗi (không ghi gì)

    @Test
    void nonInProgressVisitIs409AndWritesNothing() {
        for (String status : List.of("WAITING", "COMPLETED", "CANCELLED")) {
            jdbc.update("UPDATE visits SET status = ? WHERE id = ?", status, visit);
            State before = state();

            assertError(post(vetToken, visit, body()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

            assertThat(state()).as(status).isEqualTo(before);
        }
    }

    @Test
    void visitGuardsAreCheckedInTheContractOrder() {
        State before = state();

        assertError(post(vetToken, Long.MAX_VALUE, body()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post(token(foreignVet), visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long grooming = visit(branch, "GROOMING", "IN_PROGRESS", vet, pet);
        assertError(post(vetToken, grooming, body()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post(token(otherVet), visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long unassigned = visit(branch, "MEDICAL", "WAITING", null, pet);
        assertError(post(vetToken, unassigned, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        // người phụ trách (403) đứng trước trạng thái (409)
        long completed = visit(branch, "MEDICAL", "COMPLETED", vet, pet);
        assertError(post(token(otherVet), completed, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void vaccineTypeDoseAndProductGuards() {
        State before = state();

        Map<String, Object> unknownType = body();
        unknownType.put("vaccineTypeId", Long.MAX_VALUE);
        assertError(post(vetToken, visit, unknownType), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        long catType = vaccines.vaccineType("CAT", "4 bệnh IT14");
        Map<String, Object> wrongSpecies = body();
        wrongSpecies.put("vaccineTypeId", catType);
        assertRule(post(vetToken, visit, wrongSpecies), "BR-KB-04");

        long inactiveType = vaccines.vaccineType("DOG", "Cũ IT14");
        jdbc.update("UPDATE vaccine_types SET is_active = false WHERE id = ?", inactiveType);
        Map<String, Object> inactive = body();
        inactive.put("vaccineTypeId", inactiveType);
        assertRule(post(vetToken, visit, inactive), "BR-SP-07");

        long otherTypeProtocol = vaccines.protocol("DOG", vaccines.vaccineType("DOG", "5 bệnh IT14"), 1, 21, false);
        long catProtocol = vaccines.protocol("CAT", catType, 1, 21, false);
        long inactiveProtocol = vaccines.protocolDose("DOG", dogType, 3, 6, false);
        for (Object protocol : List.of(Long.MAX_VALUE, otherTypeProtocol, catProtocol, inactiveProtocol)) {
            Map<String, Object> dose = body();
            dose.put("protocolId", protocol);
            assertRule(post(vetToken, visit, dose), "BR-KB-04");
        }

        long drug = vaccines.drugProduct("Thuốc");
        vaccines.lot(branch, drug, TODAY.plusDays(30), 5);
        long otherTypeProduct = vaccines.product(vaccines.vaccineType("DOG", "7 bệnh IT14"), "Khác loại", true);
        vaccines.lot(branch, otherTypeProduct, TODAY.plusDays(30), 5);
        long discontinued = vaccines.product(dogType, "Ngừng", false);
        vaccines.lot(branch, discontinued, TODAY.plusDays(30), 5);
        for (Object productId : List.of(Long.MAX_VALUE, drug, otherTypeProduct, discontinued)) {
            Map<String, Object> bad = body();
            bad.put("productId", productId);
            assertRule(post(vetToken, visit, bad), "BR-KB-04");
        }

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void noAvailableStockIsBrKb04ForAnExpiredEmptyOrOtherBranchLot() {
        long expiredOnly = vaccines.product(dogType, "Hết hạn", true);
        vaccines.lot(branch, expiredOnly, TODAY.minusDays(1), 5);
        long empty = vaccines.product(dogType, "Hết hàng", true);
        vaccines.lot(branch, empty, TODAY.plusDays(30), 0);
        long elsewhere = vaccines.product(dogType, "Chi nhánh khác", true);
        vaccines.lot(otherBranch, elsewhere, TODAY.plusDays(30), 9);
        State before = state();

        for (long productId : List.of(expiredOnly, empty, elsewhere)) {
            Map<String, Object> bad = body();
            bad.put("productId", productId);
            assertRule(post(vetToken, visit, bad), "BR-KB-04");
        }

        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ validate, xác thực, phân quyền

    @Test
    void bodyShapesAreRejectedBeforeAnythingIsWritten() {
        State before = state();

        for (Object malformed : List.of("", "{", "[]", "\"x\"", "{\"vaccineTypeId\":\"abc\",\"protocolId\":1,\"productId\":1}",
                "{\"vaccineTypeId\":1,\"protocolId\":1.5,\"productId\":1}")) {
            assertError(post(vetToken, visit, malformed), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        ResponseEntity<Map<String, Object>> missing = post(vetToken, visit, new LinkedHashMap<>());
        assertError(missing, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) missing.getBody().get("message")).contains("vaccineTypeId: Thiếu loại vaccine")
                .contains("protocolId: Thiếu mũi tiêm").contains("productId: Thiếu sản phẩm vaccine");
        Map<String, Object> nulls = new LinkedHashMap<>();
        nulls.put("vaccineTypeId", null);
        nulls.put("protocolId", null);
        nulls.put("productId", null);
        assertError(post(vetToken, visit, nulls), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        for (Object invalid : List.of(0, -1)) {
            Map<String, Object> bad = body();
            bad.put("protocolId", invalid);
            ResponseEntity<Map<String, Object>> response = post(vetToken, visit, bad);
            assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertThat((String) response.getBody().get("message")).contains("protocolId: Mã mũi tiêm không hợp lệ");
        }
        assertError(exchangeRaw(vetToken, "/api/visits/abc/vaccinations", MediaType.APPLICATION_JSON, "{}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchangeRaw(vetToken, "/api/visits/" + visit + "/vaccinations", MediaType.TEXT_PLAIN, "x"),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void unauthenticatedExpiredSessionAndMustChangePasswordAreRejected() {
        State before = state();

        assertError(post(null, visit, body()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        ResponseEntity<Map<String, Object>> mustChange;
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        mustChange = post(vetToken, visit, body());
        assertThat(mustChange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) mustChange.getBody().get("message")).endsWith("(BR-TK-17)");
        jdbc.update("UPDATE accounts SET must_change_password = false WHERE id = ?", vet);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", vet);
        assertError(post(vetToken, visit, body()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void rolesOutsideTheContractAre403AndTheBodyIsValidatedFirst() {
        State before = state();

        for (String role : List.of("CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER")) {
            assertError(post(token(data.staff(role, branch)), visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("SUPER_MANAGER", "ADMIN")) {
            assertError(post(token(data.staff(role, null)), visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(post(token(data.customerAccount()), visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(post(token(data.staff("RECEPTIONIST", branch)), visit, new LinkedHashMap<>()),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(state()).isEqualTo(before);
    }

    /**
     * T28 phần 4 (BR-TN-05, BR-TN-08, BR-TK-11, BR-QT-03): quyền ghi mũi đi theo người phụ trách hiện tại; người cũ sau
     * gán lại, VET bị khóa và VET bị điều chuyển giữa chừng đều không ghi được, và không để lại gì.
     */
    @Test
    void afterReassignTheNewAssigneeRecordsWhileALockedOrTransferredVetCannot() {
        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit);
        State untouched = state();

        assertError(post(vetToken, visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).as("người cũ bị từ chối, không ghi gì").isEqualTo(untouched);

        Map<String, Object> recorded = data(post(token(otherVet), visit, body()), HttpStatus.CREATED);
        assertThat(((Map<?, ?>) recorded.get("vaccination")).get("recordedBy")).isEqualTo((int) otherVet);

        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vet, visit);
        State before = state();

        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertError(post(vetToken, visit, body()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET is_locked = false WHERE id = ?", vet);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, vet);
        assertError(post(vetToken, visit, body()), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branch, vet);

        assertThat(state()).as("VET bị khóa / điều chuyển không ghi gì").isEqualTo(before);
    }

    // ================================================================ rollback khi một bước ghi lỗi

    @Test
    void aFailureInTheStockMovementRollsBackTheOrderLineTheDoseAndTheLot() {
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "stock_movements",
                "NEW.source_type = 'vaccinations'", () -> post(vetToken, visit, body()));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aFailureMarkingTheOlderDoseRollsBackEverythingIncludingTheStock() {
        olderDoseWithOpenTask();
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "vaccinations",
                "NEW.superseded_at IS NOT NULL", () -> post(vetToken, visit, body()));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aFailureCancellingTheCareTaskRollsBackEverythingIncludingTheSupersededMark() {
        olderDoseWithOpenTask();
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "care_tasks",
                "NEW.status = 'CANCELLED'", () -> post(vetToken, visit, body()));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ race

    @Test
    void twoVisitsOfTheSamePetQueueOnTheAdvisoryLockAndLeaveExactlyOneUnsupersededDose() throws Exception {
        long visit2 = visit(branch, "MEDICAL", "IN_PROGRESS", vet, pet);
        vaccines.orderOf(visit2, vet);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT pg_advisory_xact_lock(?, ?)::text", 1004,
                        (int) (pet % Integer.MAX_VALUE)),
                "%pg_advisory_xact_lock%", j -> {
                }, List.of(() -> post(vetToken, visit, body()), () -> post(vetToken, visit2, body())));

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.CREATED);
        List<Long> open = jdbc.queryForList("SELECT id FROM vaccinations WHERE pet_id = ? AND superseded_at IS NULL",
                Long.class, pet);
        assertThat(open).hasSize(1);
        assertThat(open.get(0)).isEqualTo(
                jdbc.queryForObject("SELECT max(id) FROM vaccinations WHERE pet_id = ?", Long.class, pet));
        assertThat(doses()).isEqualTo(2);
    }

    @Test
    void aCompletionCommittedWhileTheRecordWaitsOnTheVisitLockMakesItA409WithNoWrite() throws Exception {
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                        Timestamp.from(T0), visit),
                List.of(() -> post(vetToken, visit, body())));

        assertError(results.get(0), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        State after = state();
        assertThat(after.doses()).isEqualTo(before.doses());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.movements()).isEqualTo(before.movements());
        assertThat(after.lotQuantity()).isEqualTo(before.lotQuantity());
    }

    @Test
    void aLotDrainedWhileTheRecordWaitsIsBrKo05AndRollsBackTheOrderLine() throws Exception {
        jdbc.update("UPDATE stock_lots SET quantity = 0 WHERE id = ?", lotFar);
        jdbc.update("UPDATE stock_lots SET quantity = 1 WHERE id = ?", lotNear);
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM stock_lots WHERE id = ? FOR UPDATE", lotNear),
                "%stock_lots l%for no key update%",
                j -> j.update("UPDATE stock_lots SET quantity = 0 WHERE id = ?", lotNear),
                List.of(() -> post(vetToken, visit, body())));

        ResponseEntity<Map<String, Object>> response = results.get(0);
        assertRule(response, "BR-KO-05");
        State after = state();
        assertThat(after.doses()).isEqualTo(before.doses());
        assertThat(after.lines()).as("dòng Order vừa thêm bị hoàn tác").isEqualTo(before.lines());
        assertThat(after.movements()).isEqualTo(before.movements());
    }

    @Test
    void aProductDiscontinuedWhileTheRecordWaitsOnItsShareLockIsBrKb04NotA500() throws Exception {
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM products WHERE id = ? FOR NO KEY UPDATE", product),
                "%products%for share%",
                j -> j.update("UPDATE products SET is_active = false WHERE id = ?", product),
                List.of(() -> post(vetToken, visit, body())));

        assertRule(results.get(0), "BR-KB-04");
        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ helpers

    private void olderDoseWithOpenTask() {
        long earlier = visit(otherBranch, "MEDICAL", "COMPLETED", foreignVet, pet);
        long lotOther = vaccines.lot(otherBranch, product, TODAY.plusDays(30), 1);
        long older = vaccines.dose(earlier, dogType, dose1, 1, product, lotOther, TODAY.minusDays(30),
                TODAY.minusDays(9), foreignVet, null);
        vaccines.careTask(older, pet, "VACCINE_DUE", "OPEN");
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vaccineTypeId", dogType);
        body.put("protocolId", dose2);
        body.put("productId", product);
        return body;
    }

    private State state() {
        String lots = lotNear + ", " + lotFar + ", " + lotExpired;
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
                jdbc.queryForObject("SELECT xmin::text FROM orders WHERE id = ?", String.class, order),
                jdbc.queryForObject("SELECT xmin::text FROM visits WHERE id = ?", String.class, visit));
    }

    private long doses() {
        return jdbc.queryForObject("SELECT count(*) FROM vaccinations WHERE pet_id = ?", Long.class, pet);
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

    private String nameOf(String table, long id) {
        return jdbc.queryForObject("SELECT name FROM " + table + " WHERE id = ?", String.class, id);
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

    private void setBirthDate(LocalDate birthDate) {
        jdbc.update("UPDATE pets SET birth_date = ? WHERE id = ?", birthDate, pet);
    }

    /** Trigger tạm từ chối INSERT / UPDATE (23514 = check_violation → 409), giới hạn bằng {@code condition}. */
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
     * chính transaction của nó và commit (khuôn {@code BookAppointmentIT.whileHeld}).
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

    private ResponseEntity<Map<String, Object>> post(String bearer, long visitId, Object body) {
        return exchange(bearer, HttpMethod.POST, PATH.formatted(visitId), body);
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

    private ResponseEntity<Map<String, Object>> exchangeRaw(String bearer, String path, MediaType type, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(type);
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST, new HttpEntity<>(body, headers),
                MAP);
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
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VaccinationRecordIT")).accessToken();
    }
}
