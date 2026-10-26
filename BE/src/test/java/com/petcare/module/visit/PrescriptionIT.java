package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.module.visit.repository.PrescriptionItemRepository;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #16 kê đơn và #17 xóa dòng đơn trên Postgres thật (T38 phần 1–2, docs/adr/0077, 0078): đủ tồn → dòng Order DRUG + dòng đơn thuốc + bệnh
 * án rỗng + audit; thiếu tồn → 400 kèm số tồn, giảm số lượng / mua ngoài; tồn khả dụng chỉ tính lô chưa hết hạn của chi
 * nhánh; mọi guard và thứ tự lỗi (guard sai không ghi gì); rollback khi một bước ghi lỗi; race (hoàn tất lượt, khóa thú,
 * thuốc ngừng kinh doanh, ghi bệnh án song song); hệ quả với hoàn tất lượt, gán lại, đổi loài và thu tiền. Lượt chèn
 * bằng SQL. Đồng hồ {@link MutableClock} ở <b>năm 2017</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PrescriptionIT.TestBeans.class})
class PrescriptionIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2017, 3, 15);
    private static final Instant T0 = TODAY.atTime(9, 0).atZone(VN).toInstant();
    private static final String PATH = "/api/visits/%d/prescriptions";
    private static final String DOSAGE = "Ngày 2 lần, mỗi lần 1 viên sau ăn";
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
    private record State(long items, long records, long lines, long total, long audits, long removals,
            long lotQuantity, String orderXmin, String visitXmin) {
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
    private PrescriptionItemRepository itemRepository;
    @Autowired
    private PrescriptionApi prescriptionApi;

    private AppointmentItSupport data;
    private VaccinationItSupport catalog;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> services = new ArrayList<>();
    private final List<Long> shifts = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long vet;
    private long otherVet;
    private long foreignVet;
    private long customer;
    private long pet;
    private long visit;
    private long order;
    private long drug;
    private long lotNear;
    private long lotFar;
    private long lotExpired;
    private long lotOtherBranch;
    private String vetToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        catalog = new VaccinationItSupport(jdbc);
        branch = branch();
        otherBranch = branch();
        vet = data.staff("VET", branch);
        otherVet = data.staff("VET", branch);
        foreignVet = data.staff("VET", otherBranch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
        visit = visit(branch, "MEDICAL", "IN_PROGRESS", vet);
        order = catalog.orderOf(visit, vet);
        vetToken = token(vet);

        drug = catalog.prescriptionDrug("Amoxicillin");
        // tồn khả dụng tại chi nhánh = 2 + 3 = 5: lô hết hạn và lô chi nhánh khác không tính (BR-KO-01)
        lotFar = catalog.lot(branch, drug, TODAY.plusDays(200), 3);
        lotNear = catalog.lot(branch, drug, TODAY.plusDays(30), 2);
        lotExpired = catalog.lot(branch, drug, TODAY.minusDays(1), 50);
        lotOtherBranch = catalog.lot(otherBranch, drug, TODAY.plusDays(30), 40);
    }

    @AfterEach
    void tearDown() {
        catalog.deletePrescriptionsOfPet(pet);
        catalog.deleteDoses();
        for (long shift : shifts) {
            jdbc.update("DELETE FROM payments WHERE cashier_shift_id = ?", shift);
            jdbc.update("DELETE FROM cashier_shifts WHERE id = ?", shift);
        }
        for (long b : branches) {
            // gán lại lượt (BR-TN-08) ghi lịch sử gán, FK RESTRICT
            jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        for (long id : services) {
            jdbc.update("DELETE FROM services WHERE id = ?", id);
        }
        catalog.deleteCatalog();
        data.cleanUp();
    }

    // ================================================================ đủ tồn / thiếu tồn (docs/adr/0077 §3)

    @Test
    void enoughStockAddsADrugLineAnEmptyRecordTheItemAndOneAudit() {
        long totalBefore = total();

        Map<String, Object> item = data(post(vetToken, visit, body(3, null)), HttpStatus.CREATED);

        long itemId = id(item.get("itemId"));
        long lineId = id(item.get("orderLineId"));
        assertThat(id(item.get("productId"))).isEqualTo(drug);
        assertThat((String) item.get("productName")).startsWith("Amoxicillin");
        assertThat(item.get("quantity")).isEqualTo(3);
        assertThat(item.get("dosageInstructions")).isEqualTo(DOSAGE);
        assertThat(item.get("isExternalPurchase")).isEqualTo(false);
        assertThat(item.get("externalReason")).isNull();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM prescription_items WHERE id = ?", itemId);
        assertThat(id(row.get("medical_record_id"))).isEqualTo(visit);
        assertThat(id(row.get("order_line_id"))).isEqualTo(lineId);
        assertThat(row.get("is_external_purchase")).isEqualTo(false);
        assertThat(row.get("external_reason")).isNull();

        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM order_lines WHERE id = ?", lineId);
        assertThat(id(line.get("order_id"))).isEqualTo(order);
        assertThat(line.get("line_type")).isEqualTo("DRUG");
        assertThat(id(line.get("product_id"))).isEqualTo(drug);
        assertThat(line.get("quantity")).isEqualTo(3);
        assertThat(id(line.get("unit_price"))).as("giá snapshot lúc kê (BR-BH-03)").isEqualTo(30_000L);
        assertThat(id(line.get("owner_account_id"))).as("dòng thuộc VET kê (BR-BH-02)").isEqualTo(vet);
        assertThat(id(line.get("added_by"))).isEqualTo(vet);
        assertThat(total()).isEqualTo(totalBefore + 90_000L);

        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM medical_records WHERE visit_id = ?", visit);
        assertThat(record.get("diagnosis")).isNull();
        assertThat(record.get("last_edited_by")).as("bệnh án rỗng chưa có người ghi").isNull();
        assertThat(record.get("locked_at")).isNull();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id IN (?, ?)", Long.class,
                lotNear, lotFar)).as("kho chỉ trừ lúc thu tiền (BR-BH-04)").isZero();

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, entity_type, entity_id, before_data::text AS before_data, after_data::text AS after
                FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_ADDED' AND entity_id = ?""", itemId);
        assertThat(id(audit.get("actor_account_id"))).isEqualTo(vet);
        assertThat(audit.get("entity_type")).isEqualTo("prescription_items");
        assertThat(audit.get("before_data")).isNull();
        assertThat((String) audit.get("after")).contains("\"visitId\": " + visit).contains("\"quantity\": 3")
                .contains("\"requestedQuantity\": 3").contains("\"orderLineId\": " + lineId)
                .contains("\"isExternalPurchase\": false");
        assertThat(audits()).isEqualTo(1);

        // response POST = phần tử trong GET bệnh án (#12)
        Map<String, Object> record12 = data(exchange(vetToken, HttpMethod.GET,
                "/api/visits/" + visit + "/medical-record", null), HttpStatus.OK);
        assertThat(listOf(record12.get("prescriptions"))).containsExactly(item);
    }

    @Test
    void anExistingRecordIsNotRewritten() {
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("examination", "Sốt nhẹ");
        put.put("diagnosis", "Viêm họng");
        data(exchange(vetToken, HttpMethod.PUT, "/api/visits/" + visit + "/medical-record", put), HttpStatus.OK);
        String xmin = jdbc.queryForObject("SELECT xmin::text FROM medical_records WHERE visit_id = ?", String.class,
                visit);

        data(post(vetToken, visit, body(1, null)), HttpStatus.CREATED);

        assertThat(jdbc.queryForObject("SELECT xmin::text FROM medical_records WHERE visit_id = ?", String.class, visit))
                .isEqualTo(xmin);
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE visit_id = ?", String.class, visit))
                .isEqualTo("Viêm họng");
    }

    @Test
    void aShortageIs400WithTheAvailableQuantityOfUnexpiredLotsOfThisBranchAndWritesNothing() {
        State before = state();

        ResponseEntity<Map<String, Object>> response = post(vetToken, visit, body(6, null));

        assertRule(response, "BR-KB-03");
        assertThat((String) response.getBody().get("message")).contains("chỉ còn 5");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void reduceToAvailableAddsALineOfTheAvailableQuantity() {
        Map<String, Object> item = data(post(vetToken, visit, body(8, "REDUCE_TO_AVAILABLE")), HttpStatus.CREATED);

        assertThat(item.get("quantity")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_lines WHERE id = ?", Integer.class,
                id(item.get("orderLineId")))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT after_data->>'requestedQuantity' FROM audit_logs WHERE entity_id = ? "
                + "AND action = 'PRESCRIPTION_ITEM_ADDED'", String.class, id(item.get("itemId")))).isEqualTo("8");
    }

    @Test
    void externalPurchaseKeepsTheQuantityAndAddsNoOrderLine() {
        State before = state();

        Map<String, Object> item = data(post(vetToken, visit, body(8, "EXTERNAL_PURCHASE")), HttpStatus.CREATED);

        assertThat(item.get("quantity")).isEqualTo(8);
        assertThat(item.get("isExternalPurchase")).isEqualTo(true);
        assertThat(item.get("externalReason")).isEqualTo("OUT_OF_STOCK_AT_PRESCRIBE");
        assertThat(item.get("orderLineId")).isNull();
        State after = state();
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.total()).isEqualTo(before.total());
        assertThat(after.items()).isEqualTo(before.items() + 1);
        assertThat(after.records()).isEqualTo(1);
    }

    @Test
    void aResolutionIsIgnoredWhenTheStockIsEnough() {
        for (String resolution : List.of("REDUCE_TO_AVAILABLE", "EXTERNAL_PURCHASE")) {
            Map<String, Object> item = data(post(vetToken, visit, body(2, resolution)), HttpStatus.CREATED);

            assertThat(item.get("quantity")).isEqualTo(2);
            assertThat(item.get("isExternalPurchase")).as(resolution).isEqualTo(false);
            assertThat(item.get("orderLineId")).isNotNull();
        }
    }

    @Test
    void reduceWithNoStockLeftIs400AndExternalStillWorks() {
        long empty = catalog.prescriptionDrug("Không tồn");
        catalog.lot(branch, empty, TODAY.minusDays(3), 9);
        State before = state();

        ResponseEntity<Map<String, Object>> reduce = post(vetToken, visit, body(empty, 2, "REDUCE_TO_AVAILABLE"));

        assertRule(reduce, "BR-KB-03");
        assertThat((String) reduce.getBody().get("message")).contains("chỉ chọn được mua ngoài");
        assertThat(state()).isEqualTo(before);
        Map<String, Object> external = data(post(vetToken, visit, body(empty, 2, "EXTERNAL_PURCHASE")),
                HttpStatus.CREATED);
        assertThat(external.get("isExternalPurchase")).isEqualTo(true);
    }

    @Test
    void aSecondIdenticalPostPrescribesASecondLine() {
        data(post(vetToken, visit, body(1, null)), HttpStatus.CREATED);
        data(post(vetToken, visit, body(1, null)), HttpStatus.CREATED);

        assertThat(state().items()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'DRUG'",
                Long.class, order)).isEqualTo(2);
    }

    @Test
    void clientFieldsForTheLineAndTheBranchAreIgnored() {
        Map<String, Object> body = body(1, null);
        body.put("isExternalPurchase", true);
        body.put("orderLineId", 1);
        body.put("branchId", otherBranch);
        body.put("quantityAvailable", 999);

        Map<String, Object> item = data(post(vetToken, visit, body), HttpStatus.CREATED);

        assertThat(item.get("isExternalPurchase")).isEqualTo(false);
        assertThat(id(item.get("orderLineId"))).isNotEqualTo(1L);
    }

    // ================================================================ thuốc

    @Test
    void onlyAnActivePrescriptionDrugCanBePrescribedEvenAsAnExternalPurchase() {
        long otc = catalog.drugProduct("Vitamin");
        long vaccineType = catalog.vaccineType("DOG", "Dại IT17");
        long vaccine = catalog.product(vaccineType, "Rabisin", true);
        long discontinued = catalog.prescriptionDrug("Ngừng");
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", discontinued);
        State before = state();

        for (long product : List.of(otc, vaccine, discontinued, Long.MAX_VALUE)) {
            assertRule(post(vetToken, visit, body(product, 1, null)), "BR-KB-03");
            assertRule(post(vetToken, visit, body(product, 1, "EXTERNAL_PURCHASE")), "BR-KB-03");
        }

        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ guard và thứ tự lỗi (không ghi gì)

    @Test
    void visitGuardsAreCheckedInTheContractOrder() {
        State before = state();

        assertError(post(vetToken, Long.MAX_VALUE, body(1, null)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post(token(foreignVet), visit, body(1, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        long grooming = visit(branch, "GROOMING", "IN_PROGRESS", vet);
        assertError(post(vetToken, grooming, body(1, null)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post(token(otherVet), visit, body(1, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long unassigned = visit(branch, "MEDICAL", "WAITING", null);
        assertError(post(vetToken, unassigned, body(1, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long completed = visit(branch, "MEDICAL", "COMPLETED", vet);
        // người phụ trách (403) đứng trước trạng thái (409); trạng thái (409) đứng trước thuốc (400)
        assertError(post(token(otherVet), completed, body(1, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(post(vetToken, completed, body(Long.MAX_VALUE, 1, null)), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        long waiting = visit(branch, "MEDICAL", "WAITING", vet);
        assertError(post(vetToken, waiting, body(1, null)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        // thuốc (400) đứng trước tồn (400)
        ResponseEntity<Map<String, Object>> product = post(vetToken, visit, body(Long.MAX_VALUE, 99, null));
        assertRule(product, "BR-KB-03");
        assertThat((String) product.getBody().get("message")).contains("không phải thuốc kê đơn");

        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_records WHERE visit_id IN (?, ?, ?, ?)",
                Long.class, grooming, unassigned, completed, waiting)).isZero();
    }

    @Test
    void bodyShapesAreRejectedBeforeAnythingIsWritten() {
        State before = state();

        for (Object malformed : List.of("", "{", "[]", "\"x\"",
                "{\"productId\":\"abc\",\"quantity\":1,\"dosageInstructions\":\"x\"}",
                "{\"productId\":1,\"quantity\":2.5,\"dosageInstructions\":\"x\"}",
                "{\"productId\":1,\"quantity\":3000000000,\"dosageInstructions\":\"x\"}",
                "{\"productId\":1,\"quantity\":1,\"dosageInstructions\":\"x\",\"shortageResolution\":\"external_purchase\"}",
                "{\"productId\":1,\"quantity\":1,\"dosageInstructions\":\"x\",\"shortageResolution\":\"OTHER\"}")) {
            assertError(post(vetToken, visit, malformed), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        ResponseEntity<Map<String, Object>> missing = post(vetToken, visit, new LinkedHashMap<>());
        assertError(missing, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) missing.getBody().get("message")).contains("productId: Thiếu thuốc")
                .contains("quantity: Thiếu số lượng").contains("dosageInstructions: Bắt buộc nhập liều dùng");
        Map<String, Object> blank = body(1, null);
        blank.put("dosageInstructions", "　  ");
        assertError(post(vetToken, visit, blank), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        Map<String, Object> tooLong = body(1, null);
        tooLong.put("dosageInstructions", "x".repeat(501));
        ResponseEntity<Map<String, Object>> longResponse = post(vetToken, visit, tooLong);
        assertError(longResponse, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) longResponse.getBody().get("message")).contains("Liều dùng tối đa 500 ký tự");
        Map<String, Object> zero = body(0, null);
        ResponseEntity<Map<String, Object>> zeroResponse = post(vetToken, visit, zero);
        assertError(zeroResponse, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) zeroResponse.getBody().get("message")).contains("quantity: Số lượng phải lớn hơn 0");
        assertError(exchangeRaw(vetToken, "/api/visits/abc/prescriptions", MediaType.APPLICATION_JSON, "{}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchangeRaw(vetToken, "/api/visits/" + visit + "/prescriptions", MediaType.TEXT_PLAIN, "x"),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void theDosageIsStoredStripped() {
        Map<String, Object> body = body(1, null);
        body.put("dosageInstructions", "　 " + DOSAGE + " \n");

        Map<String, Object> item = data(post(vetToken, visit, body), HttpStatus.CREATED);

        assertThat(item.get("dosageInstructions")).isEqualTo(DOSAGE);
        assertThat(jdbc.queryForObject("SELECT dosage_instructions FROM prescription_items WHERE id = ?", String.class,
                id(item.get("itemId")))).isEqualTo(DOSAGE);
    }

    @Test
    void unauthenticatedExpiredSessionAndMustChangePasswordAreRejected() {
        State before = state();

        assertError(post(null, visit, body(1, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        ResponseEntity<Map<String, Object>> mustChange = post(vetToken, visit, body(1, null));
        assertThat(mustChange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) mustChange.getBody().get("message")).endsWith("(BR-TK-17)");
        jdbc.update("UPDATE accounts SET must_change_password = false WHERE id = ?", vet);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", vet);
        assertError(post(vetToken, visit, body(1, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void rolesOutsideTheContractAre403AndTheBodyIsValidatedFirst() {
        State before = state();

        for (String role : List.of("CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER")) {
            assertError(post(token(data.staff(role, branch)), visit, body(1, null)), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        for (String role : List.of("SUPER_MANAGER", "ADMIN")) {
            assertError(post(token(data.staff(role, null)), visit, body(1, null)), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        assertError(post(token(data.customerAccount()), visit, body(1, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(post(token(data.staff("RECEPTIONIST", branch)), visit, new LinkedHashMap<>()),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ hệ quả với luồng khác

    /**
     * BR-TN-08: gán lại lượt đã gọi chuyển dòng DRUG của VET cũ cho người mới (`transferLineOwnership`); người mới kê
     * được, người cũ thì không.
     */
    @Test
    void reassignMovesTheDrugLineToTheNewVetWhoCanPrescribeWhileTheOldOneCannot() {
        long lineId = id(data(post(vetToken, visit, body(1, null)), HttpStatus.CREATED).get("orderLineId"));
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        long manager = data.staff("BRANCH_MANAGER", branch);
        Map<String, Object> reassign = new LinkedHashMap<>();
        reassign.put("assigneeId", otherVet);
        reassign.put("reason", "BS cũ bị khóa giữa ca");

        data(exchange(token(manager), HttpMethod.POST, "/api/visits/" + visit + "/reassign", reassign), HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE id = ?", Long.class, lineId))
                .isEqualTo(otherVet);
        data(post(token(otherVet), visit, body(1, null)), HttpStatus.CREATED);
        jdbc.update("UPDATE accounts SET is_locked = false WHERE id = ?", vet);
        State before = state();
        assertError(post(vetToken, visit, body(1, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo(before);
    }

    /** BR-KH-03: kê đơn tạo bệnh án nên thú bị khóa đổi loài. */
    @Test
    void afterPrescribingTheSpeciesIsLocked() {
        data(post(vetToken, visit, body(1, null)), HttpStatus.CREATED);
        String species = jdbc.queryForObject("SELECT species FROM pets WHERE id = ?", String.class, pet);

        ResponseEntity<Map<String, Object>> patch = exchange(token(data.staff("RECEPTIONIST", branch)),
                HttpMethod.PATCH, "/api/pets/" + pet, Map.of("species", "DOG".equals(species) ? "CAT" : "DOG"));

        assertRule(patch, "BR-KH-03");
    }

    /**
     * Lượt chỉ có đơn thuốc: bệnh án rỗng không có chẩn đoán nên lượt có dịch vụ Khám vẫn bị BR-KB-02; có chẩn đoán thì
     * hoàn tất → Order PENDING kèm dòng DRUG, kê thêm là 409; thu tiền trừ dòng thuốc theo FEFO (BR-BH-04) và dòng đơn
     * thuốc vẫn trỏ tới dòng Order.
     */
    @Test
    void aPrescribedDrugIsChargedAtCompletionAndIssuedFefoAtPayment() {
        long exam = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'EXAM', 100000) RETURNING id""", Long.class, "Khám " + UUID.randomUUID());
        services.add(exam);
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'SERVICE', ?, 'Khám', 1, 100000, 100000, ?, ?)""",
                order, exam, vet, vet);
        long itemId = id(data(post(vetToken, visit, body(3, null)), HttpStatus.CREATED).get("itemId"));
        String complete = "/api/visits/" + visit + "/complete";

        ResponseEntity<Map<String, Object>> early = exchange(vetToken, HttpMethod.POST, complete, null);
        assertRule(early, "BR-KB-02");

        data(exchange(vetToken, HttpMethod.PUT, "/api/visits/" + visit + "/medical-record",
                Map.of("diagnosis", "Viêm da")), HttpStatus.OK);
        data(exchange(vetToken, HttpMethod.POST, complete, null), HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PENDING");
        assertError(post(vetToken, visit, body(1, null)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        long receptionist = data.staff("RECEPTIONIST", branch);
        shifts.add(jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, ?, 'OPEN', ?) RETURNING id""", Long.class, branch, receptionist, TODAY,
                Timestamp.from(T0)));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("orderIds", List.of(order));
        pay.put("method", "CASH");
        pay.put("amount", total());

        data(exchange(token(receptionist), HttpMethod.POST, "/api/payments", pay), HttpStatus.CREATED);

        assertThat(quantity(lotNear)).as("FEFO: lô hạn gần hết trước").isZero();
        assertThat(quantity(lotFar)).isEqualTo(2);
        assertThat(quantity(lotExpired)).as("không bao giờ xuất lô hết hạn").isEqualTo(50);
        assertThat(quantity(lotOtherBranch)).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT order_line_id IS NOT NULL FROM prescription_items WHERE id = ?",
                Boolean.class, itemId)).isTrue();
    }

    /**
     * Thiếu thuốc lúc thu → chuyển mua ngoài → thu lại (T38 phần 3). Kê không giữ hàng (ADR-0077 A28), nên tồn có thể
     * tụt giữa lúc kê và lúc thu: thu bị từ chối 400 BR-BH-04 và không để lại dấu vết (kể cả số phiếu thu); sau khi dòng
     * thuốc chuyển mua ngoài thì thu lại được, không tính tiền và không xuất kho thuốc đó; bệnh án (đã khóa) hiện lý do
     * theo thời điểm: {@code OUT_OF_STOCK_AT_PAYMENT} cho thuốc A, {@code OUT_OF_STOCK_AT_PRESCRIBE} cho thuốc B.
     *
     * <p>Bước chuyển là <b>stand-in cho sales-v1 #11</b> (BE-2, T43, chưa có endpoint): một transaction đúng thứ tự khóa
     * của hợp đồng ({@code orders} → {@code PrescriptionApi.markExternalPurchaseAtPayment} → xóa dòng Order → tính lại
     * tổng; docs/adr/0076 §4, 06-module-contracts). Không audit, không kiểm BR-BH-04 phía sales (D048). Khi #11 có thật,
     * thay khối đó bằng {@code POST /api/orders/{id}/lines/{lineId}/move-to-external-purchase}.
     */
    @Test
    void aShortageAtPaymentMovesTheDrugToAnExternalPurchaseAndTheSecondPaymentSkipsIt() {
        long exam = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'EXAM', 100000) RETURNING id""", Long.class, "Khám " + UUID.randomUUID());
        services.add(exam);
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'SERVICE', ?, 'Khám', 1, 100000, 100000, ?, ?)""",
                order, exam, vet, vet);
        Map<String, Object> itemA = prescribe(3);
        long itemAId = id(itemA.get("itemId"));
        long lineA = id(itemA.get("orderLineId"));
        long drugB = catalog.prescriptionDrug("Cefalexin");
        Map<String, Object> itemB = data(post(vetToken, visit, body(drugB, 2, "EXTERNAL_PURCHASE")),
                HttpStatus.CREATED);
        long itemBId = id(itemB.get("itemId"));
        assertThat(itemB.get("orderLineId")).as("mua ngoài lúc kê: không có dòng Order").isNull();
        data(exchange(vetToken, HttpMethod.PUT, "/api/visits/" + visit + "/medical-record",
                Map.of("diagnosis", "Viêm da")), HttpStatus.OK);
        data(exchange(vetToken, HttpMethod.POST, "/api/visits/" + visit + "/complete", null), HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PENDING");

        // tồn tụt giữa lúc kê và lúc thu (A28); UPDATE thẳng chỉ để tạo trạng thái thiếu, bỏ qua StockLedger có chủ đích
        jdbc.update("UPDATE stock_lots SET quantity = 0 WHERE id IN (?, ?)", lotNear, lotFar);
        long receptionist = data.staff("RECEPTIONIST", branch);
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, ?, 'OPEN', ?) RETURNING id""", Long.class, branch, receptionist, TODAY,
                Timestamp.from(T0));
        shifts.add(shift);
        String receptionistToken = token(receptionist);

        // 1) thu bị từ chối: không ghi gì, kể cả số phiếu thu
        String orderXmin = xmin("orders", order);
        String itemXmin = xmin("prescription_items", itemAId);
        long counter = paymentCounter();
        long movements = saleMovements();
        ResponseEntity<Map<String, Object>> rejected = collect(receptionistToken, total());
        assertRule(rejected, "BR-BH-04");
        assertThat((String) rejected.getBody().get("message")).contains("cần 3, còn 0");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PENDING");
        assertThat(xmin("orders", order)).isEqualTo(orderXmin);
        assertThat(xmin("prescription_items", itemAId)).isEqualTo(itemXmin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE cashier_shift_id = ?", Long.class, shift))
                .isZero();
        assertThat(paymentCounter()).as("thu thất bại không tiêu số phiếu thu").isEqualTo(counter);
        assertThat(saleMovements()).isEqualTo(movements);
        assertThat(quantity(lotNear) + quantity(lotFar)).isZero();

        // 2) stand-in sales #11: một transaction, khóa Order trước, gọi PrescriptionApi, xóa dòng Order, tính lại tổng
        long lineTotal = jdbc.queryForObject("SELECT line_total FROM order_lines WHERE id = ?", Long.class, lineA);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR NO KEY UPDATE", Long.class, order);
            prescriptionApi.markExternalPurchaseAtPayment(lineA, receptionist);
            jdbc.update("DELETE FROM order_lines WHERE id = ?", lineA);
            jdbc.update("UPDATE orders SET total_amount = total_amount - ? WHERE id = ?", lineTotal, order);
        });
        // đã commit: connection khác thấy cả ba thay đổi cùng lúc
        assertThat(jdbc.queryForMap("""
                SELECT is_external_purchase, external_reason, order_line_id, quantity, dosage_instructions
                FROM prescription_items WHERE id = ?""", itemAId))
                .containsEntry("is_external_purchase", true).containsEntry("external_reason", "OUT_OF_STOCK_AT_PAYMENT")
                .containsEntry("quantity", 3).containsEntry("dosage_instructions", DOSAGE)
                .containsEntry("order_line_id", null);
        assertThat(countLine(lineA)).isZero();
        assertThat(total()).isEqualTo(100_000);

        // 3) thu lại: chỉ dòng Khám, không xuất kho thuốc đã chuyển mua ngoài
        long amount = total();
        Map<String, Object> paid = data(collect(receptionistToken, amount), HttpStatus.CREATED);
        assertThat(paid).containsEntry("amount", 100_000);
        assertThat(jdbc.queryForMap("SELECT status, payment_id FROM orders WHERE id = ?", order))
                .containsEntry("status", "PAID").containsEntry("payment_id", id(paid.get("paymentId")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE cashier_shift_id = ? AND amount = ?",
                Long.class, shift, amount)).isEqualTo(1L);
        assertThat(paymentCounter()).as("thu thành công tiêu đúng một số").isEqualTo(counter + 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ORDER_PAID' AND entity_id = ?""", Long.class, order))
                .isEqualTo(1L);
        assertThat(saleMovements()).as("không có movement SALE cho thuốc mua ngoài").isEqualTo(movements);
        assertThat(quantity(lotExpired)).as("không bao giờ xuất lô hết hạn").isEqualTo(50);
        assertThat(quantity(lotOtherBranch)).isEqualTo(40);

        // 4) bệnh án (đã khóa) hiện lý do theo thời điểm
        Map<String, Object> record = data(exchange(vetToken, HttpMethod.GET, "/api/visits/" + visit + "/medical-record",
                null), HttpStatus.OK);
        assertThat(record).containsEntry("locked", true);
        List<Map<String, Object>> items = listOf(record.get("prescriptions"));
        assertThat(items).extracting(i -> id(i.get("itemId"))).containsExactly(itemAId, itemBId);
        assertThat(items.get(0)).containsEntry("isExternalPurchase", true)
                .containsEntry("externalReason", "OUT_OF_STOCK_AT_PAYMENT").containsEntry("quantity", 3)
                .containsEntry("dosageInstructions", DOSAGE);
        assertThat(items.get(0).get("orderLineId")).isNull();
        assertThat(items.get(1)).containsEntry("isExternalPurchase", true)
                .containsEntry("externalReason", "OUT_OF_STOCK_AT_PRESCRIBE").containsEntry("quantity", 2);
        assertThat(items.get(1).get("orderLineId")).isNull();
    }

    // ================================================================ rollback khi một bước ghi lỗi

    @Test
    void aFailureInsertingTheItemRollsBackTheRecordAndTheOrderLine() {
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "prescription_items",
                "NEW.medical_record_id = " + visit, () -> post(vetToken, visit, body(1, null)));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aFailureAddingTheOrderLineRollsBackTheRecord() {
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "order_lines",
                "NEW.order_id = " + order, () -> post(vetToken, visit, body(1, null)));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aFailureWritingTheAuditRollsBackEverything() {
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "audit_logs",
                "NEW.action = 'PRESCRIPTION_ITEM_ADDED' AND NEW.actor_account_id = " + vet,
                () -> post(vetToken, visit, body(1, "EXTERNAL_PURCHASE")));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
    }

    // ================================================================ race

    @Test
    void aCompletionCommittedWhileThePrescriptionWaitsOnTheVisitLockMakesItA409WithNoWrite() throws Exception {
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                        Timestamp.from(T0), visit),
                List.of(() -> post(vetToken, visit, body(1, null))));

        assertError(results.get(0), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        State after = state();
        assertThat(after.items()).isEqualTo(before.items());
        assertThat(after.records()).isEqualTo(before.records());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.audits()).isEqualTo(before.audits());
    }

    /** Khóa thú FOR SHARE trước lượt: đổi loài đang giữ khóa ghi thì kê đơn phải chờ (BR-KH-03). */
    @Test
    void thePrescriptionWaitsForAPetWriterBeforeCreatingTheRecord() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet),
                "%from pets%for share%",
                j -> {
                },
                List.of(() -> post(vetToken, visit, body(1, null))));

        data(results.get(0), HttpStatus.CREATED);
        assertThat(state().records()).isEqualTo(1);
    }

    @Test
    void aDrugDiscontinuedWhileThePrescriptionWaitsOnItsShareLockIsBrKb03NotA500() throws Exception {
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM products WHERE id = ? FOR NO KEY UPDATE", drug),
                "%products%for share%",
                j -> j.update("UPDATE products SET is_active = false WHERE id = ?", drug),
                List.of(() -> post(vetToken, visit, body(1, null))));

        assertRule(results.get(0), "BR-KB-03");
        assertThat(state()).isEqualTo(before);
    }

    /** #13 và #16 cùng tạo bệnh án: khóa `visits` xếp hàng nên đúng một dòng, nội dung của PUT giữ nguyên. */
    @Test
    void aRecordWriteAndAPrescriptionQueuedOnTheVisitLockLeaveExactlyOneRecord() throws Exception {
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("diagnosis", "Viêm tai");

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> {
                },
                List.of(() -> exchange(vetToken, HttpMethod.PUT, "/api/visits/" + visit + "/medical-record", put),
                        () -> post(vetToken, visit, body(1, null))));

        data(results.get(0), HttpStatus.OK);
        data(results.get(1), HttpStatus.CREATED);
        assertThat(state().records()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT diagnosis FROM medical_records WHERE visit_id = ?", String.class, visit))
                .isEqualTo("Viêm tai");
    }

    // ================================================================ #17 xóa dòng đơn thuốc (docs/adr/0078)

    @Test
    void removeDeletesTheItemAndItsDrugLineAndAudits() {
        long totalBefore = total();
        Map<String, Object> kept = prescribe(1);
        Map<String, Object> item = prescribe(2);
        long itemId = id(item.get("itemId"));
        long lineId = id(item.get("orderLineId"));
        assertThat(total()).isEqualTo(totalBefore + 90_000L);

        assertNoContent(delete(vetToken, visit, itemId));

        assertThat(countItem(itemId)).isZero();
        assertThat(countLine(lineId)).as("dòng Order DRUG đi kèm cùng mất").isZero();
        assertThat(countLine(id(kept.get("orderLineId")))).as("dòng khác giữ nguyên").isEqualTo(1);
        assertThat(total()).as("tổng Order tính lại").isEqualTo(totalBefore + 30_000L);
        assertThat(state().records()).as("bệnh án giữ nguyên").isEqualTo(1);
        assertThat(quantity(lotNear) + quantity(lotFar)).as("kho không đổi (BR-BH-04)").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id IN (?, ?)", Long.class,
                lotNear, lotFar)).isZero();

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, entity_type, entity_id, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_REMOVED' AND entity_id = ?""", itemId);
        assertThat(id(audit.get("actor_account_id"))).isEqualTo(vet);
        assertThat(audit.get("entity_type")).isEqualTo("prescription_items");
        assertThat(audit.get("after_data")).isNull();
        assertThat((String) audit.get("before_data")).contains("\"visitId\": " + visit).contains("\"quantity\": 2")
                .contains("\"productId\": " + drug).contains("\"orderLineId\": " + lineId)
                .contains("\"isExternalPurchase\": false").contains("\"externalReason\": null");
        assertThat(removals()).isEqualTo(1);

        Map<String, Object> record12 = data(exchange(vetToken, HttpMethod.GET,
                "/api/visits/" + visit + "/medical-record", null), HttpStatus.OK);
        assertThat(listOf(record12.get("prescriptions"))).as("#12 không còn dòng đã xóa").containsExactly(kept);
    }

    @Test
    void removeAnExternalItemLeavesTheOrderUntouched() {
        Map<String, Object> item = data(post(vetToken, visit, body(9, "EXTERNAL_PURCHASE")), HttpStatus.CREATED);
        long itemId = id(item.get("itemId"));
        assertThat(item.get("orderLineId")).isNull();
        State before = state();

        assertNoContent(delete(vetToken, visit, itemId));

        State after = state();
        assertThat(countItem(itemId)).isZero();
        assertThat(after.items()).isEqualTo(before.items() - 1);
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.total()).isEqualTo(before.total());
        assertThat(after.orderXmin()).as("không gọi sales, không chạm Order").isEqualTo(before.orderXmin());
        assertThat(after.removals()).isEqualTo(1);
        String audit = jdbc.queryForObject("""
                SELECT before_data::text FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_REMOVED' AND entity_id = ?""",
                String.class, itemId);
        assertThat(audit).contains("\"quantity\": 9").contains("\"isExternalPurchase\": true")
                .contains("\"externalReason\": \"OUT_OF_STOCK_AT_PRESCRIBE\"").contains("\"orderLineId\": null");
    }

    /** Quyết định ADR-0078: bệnh án rỗng do #16 tạo được giữ nguyên, nên thú vẫn bị khóa đổi loài (BR-KH-03). */
    @Test
    void removingTheLastItemKeepsTheEmptyRecordAndTheSpeciesLock() {
        long itemId = id(prescribe(1).get("itemId"));

        assertNoContent(delete(vetToken, visit, itemId));

        assertThat(state().items()).isZero();
        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM medical_records WHERE visit_id = ?", visit);
        assertThat(record.get("diagnosis")).isNull();
        assertThat(record.get("last_edited_by")).isNull();
        assertThat(record.get("locked_at")).isNull();
        String species = jdbc.queryForObject("SELECT species FROM pets WHERE id = ?", String.class, pet);
        assertRule(exchange(token(data.staff("RECEPTIONIST", branch)), HttpMethod.PATCH, "/api/pets/" + pet,
                Map.of("species", "DOG".equals(species) ? "CAT" : "DOG")), "BR-KH-03");
    }

    @Test
    void removeGuardsAreCheckedInTheContractOrder() {
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        assertError(delete(vetToken, Long.MAX_VALUE, itemId), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(delete(token(foreignVet), visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long grooming = visit(branch, "GROOMING", "IN_PROGRESS", vet);
        assertError(delete(vetToken, grooming, itemId), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(delete(token(otherVet), visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long unassigned = visit(branch, "MEDICAL", "WAITING", null);
        assertError(delete(vetToken, unassigned, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        long completed = visit(branch, "MEDICAL", "COMPLETED", vet);
        // người phụ trách (403) đứng trước trạng thái (409); trạng thái (409) đứng trước dòng (404)
        assertError(delete(token(otherVet), completed, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(delete(vetToken, completed, Long.MAX_VALUE), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        long waiting = visit(branch, "MEDICAL", "WAITING", vet);
        assertError(delete(vetToken, waiting, itemId), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(delete(vetToken, visit, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(state()).isEqualTo(before);
        assertThat(countItem(itemId)).isEqualTo(1);
    }

    @Test
    void removeIdShapesAre400BeforeTheRoleCheck() {
        State before = state();
        String base = "/api/visits/" + visit + "/prescriptions/";

        assertError(exchange(vetToken, HttpMethod.DELETE, "/api/visits/abc/prescriptions/1", null),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchange(vetToken, HttpMethod.DELETE, base + "abc", null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(exchange(token(data.staff("CARETAKER", branch)), HttpMethod.DELETE, base + "abc", null),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void anItemOfAnotherVisitAndASecondDeleteAre404() {
        long itemId = id(prescribe(1).get("itemId"));
        long other = visit(branch, "MEDICAL", "IN_PROGRESS", vet);
        State before = state();

        assertError(delete(vetToken, other, itemId), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(state()).as("dòng của lượt khác qua lượt này: không xóa").isEqualTo(before);

        assertNoContent(delete(vetToken, visit, itemId));
        assertError(delete(vetToken, visit, itemId), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(removals()).isEqualTo(1);
    }

    @Test
    void removeRolesAndSessionsAreCheckedBeforeAnythingIsRead() {
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        assertError(delete(null, visit, itemId), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        ResponseEntity<Map<String, Object>> mustChange = delete(vetToken, visit, itemId);
        assertThat(mustChange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) mustChange.getBody().get("message")).endsWith("(BR-TK-17)");
        jdbc.update("UPDATE accounts SET must_change_password = false WHERE id = ?", vet);

        for (String role : List.of("CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER")) {
            assertError(delete(token(data.staff(role, branch)), visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("SUPER_MANAGER", "ADMIN")) {
            assertError(delete(token(data.staff(role, null)), visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(delete(token(data.customerAccount()), visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        // khóa tài khoản có hiệu lực ngay (BR-QT-11) và đổi chức vụ có hiệu lực ngay (BR-QT-05): role đọc từ DB mỗi request
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertError(delete(vetToken, visit, itemId), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET is_locked = false WHERE id = ?", vet);
        jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", vet);
        assertError(delete(vetToken, visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", vet);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", vet);
        assertError(delete(vetToken, visit, itemId), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(state()).isEqualTo(before);
        assertThat(countItem(itemId)).isEqualTo(1);
    }

    /** BR-BH-02: dòng thuốc chỉ xóa qua đơn thuốc, nên #17 là đường duy nhất; sales vẫn chặn cả lễ tân lẫn VET. */
    @Test
    void aReceptionistCannotDeleteTheDrugLineThroughSales() {
        Map<String, Object> item = prescribe(1);
        State before = state();
        String path = "/api/orders/" + order + "/lines/" + id(item.get("orderLineId"));

        assertRule(exchange(token(data.staff("RECEPTIONIST", branch)), HttpMethod.DELETE, path, null), "BR-BH-02");
        assertRule(exchange(vetToken, HttpMethod.DELETE, path, null), "BR-BH-02");

        assertThat(state()).isEqualTo(before);
    }

    /** BR-TN-08: VET mới của lượt (sau gán lại) xóa được dòng VET cũ đã kê; VET cũ thì không. */
    @Test
    void afterReassignOnlyTheNewVetCanRemove() {
        Map<String, Object> item = prescribe(1);
        long itemId = id(item.get("itemId"));
        long lineId = id(item.get("orderLineId"));
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        long manager = data.staff("BRANCH_MANAGER", branch);
        Map<String, Object> reassign = new LinkedHashMap<>();
        reassign.put("assigneeId", otherVet);
        reassign.put("reason", "BS cũ bị khóa giữa ca");
        data(exchange(token(manager), HttpMethod.POST, "/api/visits/" + visit + "/reassign", reassign), HttpStatus.OK);
        jdbc.update("UPDATE accounts SET is_locked = false WHERE id = ?", vet);
        State before = state();

        assertError(delete(vetToken, visit, itemId), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo(before);
        assertNoContent(delete(token(otherVet), visit, itemId));

        assertThat(countItem(itemId)).isZero();
        assertThat(countLine(lineId)).isZero();
    }

    /** Lượt có mũi tiêm: xóa thuốc không đụng dòng VACCINE, mũi hay lô vaccine. */
    @Test
    void removeLeavesTheVaccineLineAndItsLotAlone() {
        long type = catalog.vaccineType("DOG", "Dại");
        long protocol = catalog.protocol("DOG", type, 1, 365, false);
        long vaccine = catalog.product(type, "Vaccine IT", true);
        long vaccineLot = catalog.lot(branch, vaccine);
        long dose = catalog.dose(visit, type, protocol, 1, vaccine, vaccineLot, TODAY, TODAY.plusDays(365), vet, null);
        long vaccineLine = jdbc.queryForObject("SELECT order_line_id FROM vaccinations WHERE id = ?", Long.class, dose);
        long itemId = id(prescribe(1).get("itemId"));

        assertNoContent(delete(vetToken, visit, itemId));

        assertThat(countLine(vaccineLine)).as("dòng VACCINE còn").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccinations WHERE id = ?", Long.class, dose)).isEqualTo(1);
        assertThat(quantity(vaccineLot)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id = ?", Long.class,
                vaccineLot)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'DRUG'",
                Long.class, order)).isZero();
    }

    // ---------------------------------------------------------------- hệ quả với hoàn tất lượt và thu tiền

    /** Kê 2 dòng, xóa 1, hoàn tất → Order PENDING chỉ tính dịch vụ và thuốc còn lại. */
    @Test
    void removeThenCompleteChargesOnlyTheRemainingLines() {
        completeWithOneDrugKeptAndOneRemoved();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PENDING");
        assertThat(total()).as("100000 dịch vụ + 1 thuốc × 30000").isEqualTo(130_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ? AND line_type = 'DRUG'",
                Long.class, order)).isEqualTo(1);
    }

    /** Thuốc đã xóa không bị thu tiền cũng không bị trừ kho; chỉ thuốc còn lại xuất FEFO lúc thu. */
    @Test
    void aRemovedDrugIsNeitherChargedNorIssued() {
        completeWithOneDrugKeptAndOneRemoved();
        assertThat(quantity(lotNear) + quantity(lotFar)).as("kho chưa đổi lúc xóa và lúc hoàn tất").isEqualTo(5);

        long receptionist = data.staff("RECEPTIONIST", branch);
        shifts.add(jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, ?, 'OPEN', ?) RETURNING id""", Long.class, branch, receptionist, TODAY,
                Timestamp.from(T0)));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("orderIds", List.of(order));
        pay.put("method", "CASH");
        pay.put("amount", total());
        data(exchange(token(receptionist), HttpMethod.POST, "/api/payments", pay), HttpStatus.CREATED);

        assertThat(quantity(lotNear) + quantity(lotFar)).as("chỉ 1 thuốc còn lại bị trừ").isEqualTo(4);
        assertThat(quantity(lotNear)).as("FEFO: lô hạn gần trước").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id IN (?, ?)", Long.class,
                lotNear, lotFar)).isEqualTo(1);
    }

    /** Dịch vụ Khám 100000 + 2 dòng thuốc (1 và 2 viên); xóa dòng 2 viên, ghi chẩn đoán rồi hoàn tất. */
    private void completeWithOneDrugKeptAndOneRemoved() {
        long exam = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'EXAM', 100000) RETURNING id""", Long.class, "Khám " + UUID.randomUUID());
        services.add(exam);
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'SERVICE', ?, 'Khám', 1, 100000, 100000, ?, ?)""",
                order, exam, vet, vet);
        prescribe(1);
        long removed = id(prescribe(2).get("itemId"));
        assertNoContent(delete(vetToken, visit, removed));
        data(exchange(vetToken, HttpMethod.PUT, "/api/visits/" + visit + "/medical-record",
                Map.of("diagnosis", "Viêm da")), HttpStatus.OK);
        data(exchange(vetToken, HttpMethod.POST, "/api/visits/" + visit + "/complete", null), HttpStatus.OK);
    }

    // ---------------------------------------------------------------- rollback (docs/adr/0078, bảng F1–F7)

    @Test
    void aFailureRemovingTheOrderLineRollsBackTheItemTheTotalAndTheAudit() {
        prescribe(2);
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("DELETE", "order_lines",
                "OLD.order_id = " + order, () -> delete(vetToken, visit, itemId));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(state()).isEqualTo(before);
        assertThat(countItem(itemId)).isEqualTo(1);
    }

    @Test
    void aFailureWritingTheRemoveAuditRollsBackADrugItemAndAnExternalOne() {
        long drugItem = id(prescribe(1).get("itemId"));
        long externalItem = id(data(post(vetToken, visit, body(9, "EXTERNAL_PURCHASE")), HttpStatus.CREATED)
                .get("itemId"));
        State before = state();

        for (long itemId : new long[] {drugItem, externalItem}) {
            ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "audit_logs",
                    "NEW.action = 'PRESCRIPTION_ITEM_REMOVED' AND NEW.actor_account_id = " + vet,
                    () -> delete(vetToken, visit, itemId));

            assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
            assertThat(state()).isEqualTo(before);
            assertThat(countItem(itemId)).isEqualTo(1);
        }
    }

    @Test
    void lockAndDeleteOutsideATransactionAreRefusedAndTouchNothing() {
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        assertThatThrownBy(() -> itemRepository.lockItemInVisit(itemId, visit))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> itemRepository.deleteItem(itemId)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(state()).isEqualTo(before);
        assertThat(countItem(itemId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- race (docs/adr/0078, "Đồng thời")

    @Test
    void twoConcurrentRemovesOfTheSameItemAre204And404() throws Exception {
        long itemId = id(prescribe(1).get("itemId"));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> {
                },
                List.of(() -> delete(vetToken, visit, itemId), () -> delete(vetToken, visit, itemId)));

        assertThat(results.stream().map(r -> r.getStatusCode().value()).toList())
                .containsExactlyInAnyOrder(204, 404);
        assertThat(countItem(itemId)).isZero();
        assertThat(removals()).isEqualTo(1);
    }

    @Test
    void aCompletionCommittedWhileTheRemoveWaitsOnTheVisitLockMakesItA409WithNoWrite() throws Exception {
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                        Timestamp.from(T0), visit),
                List.of(() -> delete(vetToken, visit, itemId)));

        assertError(results.get(0), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        State after = state();
        assertThat(after.items()).isEqualTo(before.items());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.total()).isEqualTo(before.total());
        assertThat(after.removals()).isEqualTo(before.removals());
    }

    /** Quyền phụ trách đọc lại dưới khóa (READ COMMITTED): gán lại commit trong lúc chờ thì VET cũ nhận 403. */
    @Test
    void aReassignCommittedWhileTheRemoveWaitsIs403AndWritesNothing() throws Exception {
        long itemId = id(prescribe(1).get("itemId"));
        State before = state();

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> j.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit),
                List.of(() -> delete(vetToken, visit, itemId)));

        assertError(results.get(0), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        State after = state();
        assertThat(after.items()).isEqualTo(before.items());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertThat(after.removals()).isEqualTo(before.removals());
    }

    /** Kê #16 xếp hàng sau #17 trên khóa `visits`: cả hai thành công, dòng mới không bị #17 đụng tới. */
    @Test
    void aPrescriptionQueuedBehindARemoveIsKept() throws Exception {
        long itemId = id(prescribe(1).get("itemId"));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                j -> j.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit),
                "%from visits where id%for no key update%",
                j -> {
                },
                List.of(() -> delete(vetToken, visit, itemId), () -> post(vetToken, visit, body(1, null))));

        assertNoContent(results.get(0));
        long newItem = id(data(results.get(1), HttpStatus.CREATED).get("itemId"));
        assertThat(countItem(itemId)).isZero();
        assertThat(countItem(newItem)).isEqualTo(1);
        assertThat(state().items()).isEqualTo(1);
    }

    // ================================================================ helpers

    private Map<String, Object> body(int quantity, String resolution) {
        return body(drug, quantity, resolution);
    }

    private static Map<String, Object> body(long productId, int quantity, String resolution) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("quantity", quantity);
        body.put("dosageInstructions", DOSAGE);
        if (resolution != null) {
            body.put("shortageResolution", resolution);
        }
        return body;
    }

    private State state() {
        String lots = lotNear + ", " + lotFar + ", " + lotExpired + ", " + lotOtherBranch;
        return new State(
                jdbc.queryForObject("SELECT count(*) FROM prescription_items WHERE medical_record_id = ?", Long.class,
                        visit),
                jdbc.queryForObject("SELECT count(*) FROM medical_records WHERE visit_id = ?", Long.class, visit),
                jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class, order),
                total(), audits(), removals(),
                jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM stock_lots WHERE id IN (" + lots + ")",
                        Long.class),
                jdbc.queryForObject("SELECT xmin::text FROM orders WHERE id = ?", String.class, order),
                jdbc.queryForObject("SELECT xmin::text FROM visits WHERE id = ?", String.class, visit));
    }

    private long total() {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, order);
    }

    private long audits() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_ADDED'
                AND after_data->>'visitId' = ?""", Long.class, String.valueOf(visit));
    }

    /** Audit xóa dòng đơn (#17) của lượt; {@code before_data} mang {@code visitId} (không có {@code after}). */
    private long removals() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'PRESCRIPTION_ITEM_REMOVED'
                AND before_data->>'visitId' = ?""", Long.class, String.valueOf(visit));
    }

    private long quantity(long lot) {
        return jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Long.class, lot);
    }

    private String xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM " + table + " WHERE id = ?", String.class, id);
    }

    /** Tổng bộ đếm mã phiếu thu (mọi kỳ): chỉ so sánh trước / sau, nên không phụ thuộc kỳ của đồng hồ giả. */
    private long paymentCounter() {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(last_value), 0) FROM document_counters WHERE doc_type = 'payments'", Long.class);
    }

    private long saleMovements() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements WHERE movement_type = 'SALE' AND source_type = 'orders'
                  AND source_id = ?""", Long.class, order);
    }

    private ResponseEntity<Map<String, Object>> collect(String bearer, long amount) {
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("orderIds", List.of(order));
        pay.put("method", "CASH");
        pay.put("amount", amount);
        return exchange(bearer, HttpMethod.POST, "/api/payments", pay);
    }

    private long branch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    /** Lượt chèn thẳng (không qua tiếp nhận); {@code assignee} null chỉ khi {@code WAITING}. */
    private long visit(long branchId, String group, String status, Long assignee) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customer, pet, group, status,
                Timestamp.from(T0), assignee, vet, Timestamp.from(T0),
                "WAITING".equals(status) ? null : Timestamp.from(T0),
                "COMPLETED".equals(status) ? Timestamp.from(T0) : null);
    }

    /** Trigger tạm từ chối INSERT (23514 = check_violation → 409), giới hạn bằng {@code condition}. */
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

    private ResponseEntity<Map<String, Object>> post(String bearer, long visitId, Object body) {
        return exchange(bearer, HttpMethod.POST, PATH.formatted(visitId), body);
    }

    private ResponseEntity<Map<String, Object>> delete(String bearer, long visitId, long itemId) {
        return exchange(bearer, HttpMethod.DELETE, PATH.formatted(visitId) + "/" + itemId, null);
    }

    private static void assertNoContent(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    /** Kê một dòng đủ tồn (số lượng {@code quantity}) bằng API #16; trả về {@code data} của response. */
    private Map<String, Object> prescribe(int quantity) {
        return data(post(vetToken, visit, body(quantity, null)), HttpStatus.CREATED);
    }

    private long countItem(long itemId) {
        return jdbc.queryForObject("SELECT count(*) FROM prescription_items WHERE id = ?", Long.class, itemId);
    }

    private long countLine(long lineId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE id = ?", Long.class, lineId);
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

    private static long id(Object value) {
        return ((Number) value).longValue();
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
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "PrescriptionIT")).accessToken();
    }
}
