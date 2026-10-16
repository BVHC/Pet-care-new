package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;

/**
 * {@code GET /api/orders/{id}} và thêm / sửa / xóa dòng (sales-v1 #3–6; BR-BH-02, 03, 04, BR-SP-01, BR-TN-01;
 * docs/adr/0044) trên Postgres 17 thật qua HTTP: ma trận vai trò × loại dòng × trạng thái × chủ dòng, snapshot giá, tổng
 * tiền không lệch dưới tải song song, cờ canEdit / canDelete khớp hành vi thật, gán lại lượt đua với thêm dòng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderLineIT extends SalesItSupport {

    @Autowired
    private VisitOrderApi visitOrders;

    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long receptionist;
    private long otherReceptionist;
    private long vet;
    private long otherVet;
    private long caretaker;
    private long manager;
    private long admin;
    private long customer;
    private long visit;
    private long order;
    private long autoLine;
    private long examService;
    private long groomingService;
    private long goods;
    private long prescriptionDrug;
    private long vaccineProduct;
    private String tReceptionist;
    private String tOtherReceptionist;
    private String tVet;
    private String tOtherVet;
    private String tCaretaker;
    private String tManager;
    private String tAdmin;
    private String tOutsider;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = staff("RECEPTIONIST", branch);
        otherReceptionist = staff("RECEPTIONIST", branch);
        vet = staff("VET", branch);
        otherVet = staff("VET", branch);
        caretaker = staff("CARETAKER", branch);
        manager = staff("BRANCH_MANAGER", branch);
        admin = staff("ADMIN", null);
        long outsider = staff("RECEPTIONIST", otherBranch);
        tReceptionist = token(receptionist);
        tOtherReceptionist = token(otherReceptionist);
        tVet = token(vet);
        tOtherVet = token(otherVet);
        tCaretaker = token(caretaker);
        tManager = token(manager);
        tAdmin = token(admin);
        tOutsider = token(outsider);

        customer = newCustomer();
        long pet = newPet(customer);
        visit = newVisit(branch, customer, pet, "MEDICAL", "IN_PROGRESS", vet, receptionist);
        examService = newService("MEDICAL", 150_000);
        groomingService = newService("GROOMING", 90_000);
        enableService(branch, examService);
        enableService(branch, groomingService);
        goods = newPricedProduct("GOODS", false, 40_000);
        prescriptionDrug = newPricedProduct("DRUG", true, 30_000);
        vaccineProduct = newPricedProduct("VACCINE", false, 200_000);
        defaultLot(branch, goods, 10, receptionist);

        LineRef ref = tx.execute(s -> visitOrders.openForVisit(visit, branch, customer, examService, vet, receptionist));
        order = ref.orderId();
        autoLine = ref.orderLineId();
    }

    private ResponseEntity<Map<String, Object>> addLine(String token, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/orders/" + order + "/lines", token, body);
    }

    private static Map<String, Object> goodsBody(long productId, int quantity) {
        return Map.of("lineType", "GOODS", "productId", productId, "quantity", quantity);
    }

    private static Map<String, Object> serviceBody(long serviceId, int quantity) {
        return Map.of("lineType", "SERVICE", "serviceId", serviceId, "quantity", quantity);
    }

    private ResponseEntity<Map<String, Object>> patchLine(String token, long lineId, Map<String, Object> body) {
        return call(HttpMethod.PATCH, "/api/orders/" + order + "/lines/" + lineId, token, body);
    }

    private ResponseEntity<Map<String, Object>> deleteLine(String token, long lineId) {
        return call(HttpMethod.DELETE, "/api/orders/" + order + "/lines/" + lineId, token, null);
    }

    private long addGoodsAsReceptionist(int quantity) {
        Map<String, Object> result = data(addLine(tReceptionist, goodsBody(goods, quantity)), HttpStatus.CREATED);
        return number(result.get("lineId"));
    }

    private long addServiceAsVet() {
        Map<String, Object> result = data(addLine(tVet, serviceBody(examService, 1)), HttpStatus.CREATED);
        return number(result.get("lineId"));
    }

    private void setStatus(String status) {
        jdbc.update("UPDATE orders SET status = ?, pending_at = CASE WHEN ? = 'PENDING' THEN now() END WHERE id = ?",
                status, status, order);
    }

    private static void assertForbiddenWithRule(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat((String) response.getBody().get("message")).contains("BR-BH-02");
    }

    // ---------------------------------------------------------------- GET

    @Test
    void getReturnsTheContractShapeWithViewerFlags() {
        long goodsLine = addGoodsAsReceptionist(2);

        Map<String, Object> asVet = data(call(HttpMethod.GET, "/api/orders/" + order, tVet, null), HttpStatus.OK);

        assertThat(asVet).containsOnlyKeys("orderId", "code", "source", "status", "customerId", "customerName",
                "branchId", "visitId", "boardingBookingId", "totalAmount", "lines", "createdAt", "pendingAt", "paidAt",
                "paymentId", "cancelType", "cancelReason", "cancelledAt", "deliveryAllowed");
        assertThat(asVet).containsEntry("source", "VISIT").containsEntry("status", "OPEN")
                .containsEntry("deliveryAllowed", false).containsEntry("visitId", (int) visit)
                .containsEntry("totalAmount", 230_000);
        assertThat((String) asVet.get("code")).startsWith("DH-");
        assertThat((String) asVet.get("customerName")).startsWith("Khách sales IT");
        List<Map<String, Object>> lines = lines(asVet);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsOnlyKeys("lineId", "lineType", "serviceId", "productId", "description",
                "quantity", "unitPrice", "lineTotal", "isAutoGenerated", "addedBy", "ownerAccountId", "canEdit",
                "canDelete");
        assertThat(lines.get(0)).containsEntry("isAutoGenerated", true).containsEntry("canEdit", true)
                .containsEntry("canDelete", true);
        assertThat(lines.get(1)).containsEntry("lineType", "GOODS").containsEntry("canEdit", false)
                .containsEntry("canDelete", false);

        Map<String, Object> asReceptionist = data(call(HttpMethod.GET, "/api/orders/" + order, tOtherReceptionist,
                null), HttpStatus.OK);
        assertThat(lines(asReceptionist).get(0)).containsEntry("canEdit", false).containsEntry("canDelete", false);
        assertThat(lines(asReceptionist).get(1)).as("mọi lễ tân sửa được hàng bán lẻ")
                .containsEntry("lineId", (int) goodsLine).containsEntry("canEdit", true)
                .containsEntry("canDelete", true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> order) {
        return (List<Map<String, Object>>) order.get("lines");
    }

    @Test
    void viewFollowsRolesAndBranch() {
        for (String token : List.of(tReceptionist, tVet, tCaretaker, tManager)) {
            assertThat(call(HttpMethod.GET, "/api/orders/" + order, token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertError(call(HttpMethod.GET, "/api/orders/" + order, tAdmin, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, "/api/orders/" + order, tOutsider, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.GET, "/api/orders/" + order, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.GET, "/api/orders/999999999", tReceptionist, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- POST: hàng bán lẻ

    @Test
    void receptionistAddsGoodsWithSnapshotPriceAndTotal() {
        Map<String, Object> result = data(addLine(tReceptionist, goodsBody(goods, 3)), HttpStatus.CREATED);

        assertThat(result).containsOnlyKeys("order", "lineId", "stockWarning");
        assertThat(result.get("stockWarning")).isNull();
        Map<String, Object> created = map(result.get("order"));
        assertThat(created).containsEntry("totalAmount", 270_000);
        Map<String, Object> line = lines(created).stream()
                .filter(l -> number(l.get("lineId")) == number(result.get("lineId"))).findFirst().orElseThrow();
        assertThat(line).containsEntry("lineType", "GOODS").containsEntry("productId", (int) goods)
                .containsEntry("unitPrice", 40_000).containsEntry("lineTotal", 120_000)
                .containsEntry("addedBy", (int) receptionist).containsEntry("ownerAccountId", (int) receptionist)
                .containsEntry("isAutoGenerated", false);
        assertThat(orderTotal(order)).isEqualTo(sumOfLines(order)).isEqualTo(270_000);
    }

    @Test
    void shortStockWarnsButStillAdds() {
        addGoodsAsReceptionist(8);

        Map<String, Object> result = data(addLine(tReceptionist, goodsBody(goods, 5)), HttpStatus.CREATED);

        Map<String, Object> warning = map(result.get("stockWarning"));
        assertThat(warning).containsOnlyKeys("productId", "requested", "available")
                .containsEntry("productId", (int) goods).containsEntry("requested", 13).containsEntry("available", 10);
        assertThat(sumOfLines(order)).isEqualTo(150_000 + 13 * 40_000);
    }

    @Test
    void prescriptionVaccineInactiveAndUnknownProductsAreRefused() {
        assertRule(addLine(tReceptionist, goodsBody(prescriptionDrug, 1)), "BR-SP-01");
        assertRule(addLine(tReceptionist, goodsBody(vaccineProduct, 1)), "BR-KB-04");
        jdbc.update("UPDATE products SET is_active = false WHERE id = ?", goods);
        assertError(addLine(tReceptionist, goodsBody(goods, 1)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(addLine(tReceptionist, goodsBody(999_999_999L, 1)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }

    @Test
    void priceChangeAfterAddingKeepsOldLinesAndNewLinesUseTheNewPrice() {
        long first = addGoodsAsReceptionist(1);
        jdbc.update("UPDATE products SET price = 99000 WHERE id = ?", goods);
        long second = addGoodsAsReceptionist(1);

        Map<String, Object> view = data(call(HttpMethod.GET, "/api/orders/" + order, tReceptionist, null),
                HttpStatus.OK);
        Map<Long, Object> priceByLine = new java.util.HashMap<>();
        lines(view).forEach(l -> priceByLine.put(number(l.get("lineId")), l.get("unitPrice")));
        assertThat(priceByLine.get(first)).as("BR-BH-03").isEqualTo(40_000);
        assertThat(priceByLine.get(second)).isEqualTo(99_000);
        assertThat(orderTotal(order)).isEqualTo(150_000 + 40_000 + 99_000);
    }

    @Test
    void hugeAmountIsRefusedAndTheOrderStaysIntact() {
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", Long.MAX_VALUE / 2, goods);

        assertError(addLine(tReceptionist, goodsBody(goods, 3)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(orderTotal(order)).isEqualTo(150_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class, order))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- POST: dịch vụ và vai trò

    @Test
    void assignedVetAddsMedicalServiceAndOwnsIt() {
        Map<String, Object> result = data(addLine(tVet, serviceBody(examService, 1)), HttpStatus.CREATED);

        Map<String, Object> line = lines(map(result.get("order"))).stream()
                .filter(l -> number(l.get("lineId")) == number(result.get("lineId"))).findFirst().orElseThrow();
        assertThat(line).containsEntry("lineType", "SERVICE").containsEntry("ownerAccountId", (int) vet)
                .containsEntry("addedBy", (int) vet).containsEntry("isAutoGenerated", false);
        assertThat(result.get("stockWarning")).isNull();
        assertThat(orderTotal(order)).isEqualTo(300_000);
    }

    @Test
    void roleAndAssignmentDecideWhoMayAddWhat() {
        assertForbiddenWithRule(addLine(tOtherVet, serviceBody(examService, 1)));
        assertForbiddenWithRule(addLine(tCaretaker, serviceBody(groomingService, 1)));
        assertForbiddenWithRule(addLine(tVet, serviceBody(groomingService, 1)));
        assertForbiddenWithRule(addLine(tReceptionist, serviceBody(examService, 1)));
        assertForbiddenWithRule(addLine(tVet, goodsBody(goods, 1)));
        assertForbiddenWithRule(addLine(tCaretaker, goodsBody(goods, 1)));
        assertError(addLine(tManager, goodsBody(goods, 1)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(addLine(tAdmin, goodsBody(goods, 1)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(addLine(tOutsider, goodsBody(goods, 1)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }

    @Test
    void caretakerAssignedToAGroomingVisitAddsGrooming() {
        long pet = newPet(customer);
        long groomingVisit = newVisit(branch, customer, pet, "GROOMING", "IN_PROGRESS", caretaker, receptionist);
        LineRef ref = tx.execute(s -> visitOrders.openForVisit(groomingVisit, branch, customer, groomingService,
                caretaker, receptionist));

        Map<String, Object> result = data(call(HttpMethod.POST, "/api/orders/" + ref.orderId() + "/lines", tCaretaker,
                serviceBody(groomingService, 1)), HttpStatus.CREATED);

        assertThat(map(result.get("order"))).containsEntry("totalAmount", 180_000);
    }

    @Test
    void serviceNotEnabledAtTheBranchOrInactiveOrUnknownIsRefused() {
        long notEnabled = newService("MEDICAL", 100_000);
        assertError(addLine(tVet, serviceBody(notEnabled, 1)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        long inactive = newService("MEDICAL", 100_000);
        enableService(branch, inactive);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", inactive);
        assertError(addLine(tVet, serviceBody(inactive, 1)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(addLine(tVet, serviceBody(999_999_999L, 1)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void orderStateDecidesWhatCanStillBeAdded() {
        setStatus("PENDING");
        assertThat(addLine(tReceptionist, goodsBody(goods, 1)).getStatusCode()).as("lễ tân thêm hàng khi PENDING")
                .isEqualTo(HttpStatus.CREATED);
        assertError(addLine(tVet, serviceBody(examService, 1)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        setStatus("CANCELLED");
        assertError(addLine(tReceptionist, goodsBody(goods, 1)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(addLine(tVet, serviceBody(examService, 1)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void malformedAddBodiesAreBadRequests() {
        assertError(addLine(tReceptionist, Map.of("productId", goods, "quantity", 1)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(addLine(tReceptionist, goodsBody(goods, 0)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(addLine(tReceptionist, Map.of("lineType", "GOODS", "quantity", 1)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(addLine(tReceptionist, Map.of("lineType", "GOODS", "productId", goods, "serviceId", examService,
                "quantity", 1)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(addLine(tReceptionist, Map.of("lineType", "DRUG", "productId", goods, "quantity", 1)),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(callRaw(HttpMethod.POST, "/api/orders/" + order + "/lines", tReceptionist,
                "{\"lineType\":\"GOODS\",\"productId\":" + goods + ",\"quantity\":1.5}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }

    // ---------------------------------------------------------------- PATCH

    @Test
    void ownerLowersQuantityOthersAreForbiddenAndIncreaseIsRefused() {
        long mine = number(data(addLine(tVet, serviceBody(examService, 3)), HttpStatus.CREATED).get("lineId"));

        assertForbiddenWithRule(patchLine(tOtherVet, mine, Map.of("quantity", 1)));
        assertError(patchLine(tVet, mine, Map.of("quantity", 4)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(patchLine(tVet, mine, Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(patchLine(tVet, mine, Map.of("quantity", 0)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        Map<String, Object> result = data(patchLine(tVet, mine, Map.of("quantity", 2)), HttpStatus.OK);

        assertThat(result).containsEntry("totalAmount", 150_000 + 300_000);
        assertThat(orderTotal(order)).isEqualTo(sumOfLines(order)).isEqualTo(450_000);
    }

    @Test
    void assignedVetSwitchesTheAutoServiceAndThePriceIsSnapshottedAgain() {
        long other = newService("MEDICAL", 220_000);
        enableService(branch, other);

        Map<String, Object> result = data(patchLine(tVet, autoLine, Map.of("serviceId", other)), HttpStatus.OK);

        Map<String, Object> line = lines(result).get(0);
        assertThat(line).containsEntry("serviceId", (int) other).containsEntry("unitPrice", 220_000)
                .containsEntry("lineTotal", 220_000).containsEntry("isAutoGenerated", true);
        assertThat(result).containsEntry("totalAmount", 220_000);
        jdbc.update("UPDATE services SET price = 1 WHERE id = ?", other);
        assertThat(sumOfLines(order)).as("giá đã chụp").isEqualTo(220_000);
    }

    @Test
    void switchingToAServiceOfAnotherGroupOrAManualLineIsRefused() {
        assertForbiddenWithRule(patchLine(tVet, autoLine, Map.of("serviceId", groomingService)));
        long manual = addServiceAsVet();
        assertError(patchLine(tVet, manual, Map.of("serviceId", examService)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(patchLine(tVet, autoLine, Map.of("serviceId", 999_999_999L)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void anyReceptionistLowersRetailGoodsEvenWhenPending() {
        long line = addGoodsAsReceptionist(4);
        setStatus("PENDING");

        Map<String, Object> result = data(patchLine(tOtherReceptionist, line, Map.of("quantity", 1)), HttpStatus.OK);

        assertThat(result).containsEntry("totalAmount", 190_000);
        assertError(patchLine(tManager, line, Map.of("quantity", 1)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void servicesAreLockedOnPendingAndEverythingOnFinishedOrders() {
        setStatus("PENDING");
        assertForbiddenWithRule(patchLine(tVet, autoLine, Map.of("quantity", 1)));
        assertForbiddenWithRule(deleteLine(tVet, autoLine));

        // PAID cần phiếu thu (CHECK ck_orders_paid_payment) nên dùng CANCELLED; PAID có ở OrderLinePolicyTest
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order);
        assertError(patchLine(tVet, autoLine, Map.of("quantity", 1)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(deleteLine(tVet, autoLine), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void unknownLineOrALineOfAnotherOrderIsNotFound() {
        long pet = newPet(customer);
        long visit2 = newVisit(branch, customer, pet, "MEDICAL", "IN_PROGRESS", vet, receptionist);
        LineRef other = tx.execute(s -> visitOrders.openForVisit(visit2, branch, customer, examService, vet,
                receptionist));

        assertError(patchLine(tVet, 999_999_999L, Map.of("quantity", 1)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(patchLine(tVet, other.orderLineId(), Map.of("quantity", 1)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(deleteLine(tVet, other.orderLineId()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- DELETE

    @Test
    void ownerDeletesTheirLinesIncludingTheAutoGeneratedOne() {
        long mine = addServiceAsVet();

        Map<String, Object> afterManual = data(deleteLine(tVet, mine), HttpStatus.OK);
        assertThat(afterManual).containsEntry("totalAmount", 150_000);

        Map<String, Object> afterAuto = data(deleteLine(tVet, autoLine), HttpStatus.OK);
        assertThat(afterAuto).containsEntry("totalAmount", 0);
        assertThat(lines(afterAuto)).isEmpty();
        assertThat(orderTotal(order)).isZero();
    }

    @Test
    void nonOwnersAreForbiddenAndNothingChanges() {
        assertForbiddenWithRule(deleteLine(tOtherVet, autoLine));
        assertForbiddenWithRule(deleteLine(tReceptionist, autoLine));
        assertForbiddenWithRule(deleteLine(tCaretaker, autoLine));
        assertError(deleteLine(tManager, autoLine), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(deleteLine(tOutsider, autoLine), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }

    @Test
    void vaccineAndDrugLinesAreNeverDeletedDirectly() {
        LineRef drug = tx.execute(s -> visitOrders.addDrugLine(visit, prescriptionDrug, 2, vet));
        LineRef vaccine = tx.execute(s -> visitOrders.addVaccineLine(visit, vaccineProduct, vet));

        assertRule(deleteLine(tVet, vaccine.orderLineId()), "BR-BH-02");
        assertRule(deleteLine(tVet, drug.orderLineId()), "BR-BH-02");
        assertRule(patchLine(tVet, drug.orderLineId(), Map.of("quantity", 1)), "BR-BH-02");
        assertRule(deleteLine(tReceptionist, vaccine.orderLineId()), "BR-BH-02");

        assertThat(sumOfLines(order)).isEqualTo(150_000 + 60_000 + 200_000);
        setStatus("PENDING");
        assertRule(deleteLine(tVet, vaccine.orderLineId()), "BR-BH-02");
        assertForbiddenWithRule(patchLine(tVet, drug.orderLineId(), Map.of("quantity", 1)));
    }

    @Test
    void viewerFlagsMatchWhatTheEndpointsAcceptForEveryLine() {
        long goodsLine = addGoodsAsReceptionist(2);
        long manual = addServiceAsVet();
        LineRef drug = tx.execute(s -> visitOrders.addDrugLine(visit, prescriptionDrug, 1, vet));
        LineRef vaccine = tx.execute(s -> visitOrders.addVaccineLine(visit, vaccineProduct, vet));

        for (String token : List.of(tVet, tOtherVet, tReceptionist, tCaretaker)) {
            Map<String, Object> view = data(call(HttpMethod.GET, "/api/orders/" + order, token, null), HttpStatus.OK);
            for (Map<String, Object> line : lines(view)) {
                long id = number(line.get("lineId"));
                // PATCH giữ nguyên số lượng: chỉ để thử quyền, không đổi dữ liệu khi được phép
                int quantity = (Integer) line.get("quantity");
                HttpStatus expectedEdit = Boolean.TRUE.equals(line.get("canEdit")) ? HttpStatus.OK : null;
                ResponseEntity<Map<String, Object>> edit = patchLine(token, id, Map.of("quantity", quantity));
                if (expectedEdit != null) {
                    assertThat(edit.getStatusCode()).as("canEdit=true line " + id).isEqualTo(HttpStatus.OK);
                } else {
                    assertThat(edit.getStatusCode()).as("canEdit=false line " + id).isNotEqualTo(HttpStatus.OK);
                }
            }
        }
        // canDelete: chỉ kiểm phía "false" (xóa thật sẽ đổi dữ liệu), cộng một phía "true" ở cuối
        Map<String, Object> asReceptionist = data(call(HttpMethod.GET, "/api/orders/" + order, tReceptionist, null),
                HttpStatus.OK);
        for (Map<String, Object> line : lines(asReceptionist)) {
            long id = number(line.get("lineId"));
            if (Boolean.FALSE.equals(line.get("canDelete"))) {
                assertThat(deleteLine(tReceptionist, id).getStatusCode()).as("line " + id).isNotEqualTo(HttpStatus.OK);
            }
        }
        assertThat(lines(asReceptionist)).extracting(l -> number(l.get("lineId")))
                .contains(goodsLine, manual, drug.orderLineId(), vaccine.orderLineId());
        assertThat(deleteLine(tReceptionist, goodsLine).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- đồng thời

    @Test
    void parallelAddsKeepTheTotalEqualToTheSumOfLines() throws Exception {
        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int quantity = i + 1;
                String token = quantity % 2 == 0 ? tReceptionist : tOtherReceptionist;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return addLine(token, goodsBody(goods, quantity));
                }));
            }
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(orderTotal(order)).isEqualTo(sumOfLines(order)).isEqualTo(150_000 + 40_000L * 55);
    }

    @Test
    void twoReceptionistsDeletingTheSameLineGetOneOkAndOneNotFound() throws Exception {
        long line = addGoodsAsReceptionist(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<Map<String, Object>>> a = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return deleteLine(tReceptionist, line);
            };
            Callable<ResponseEntity<Map<String, Object>>> b = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return deleteLine(tOtherReceptionist, line);
            };
            Future<ResponseEntity<Map<String, Object>>> fa = pool.submit(a);
            Future<ResponseEntity<Map<String, Object>>> fb = pool.submit(b);
            List<HttpStatus> statuses = List.of(HttpStatus.valueOf(fa.get(60, TimeUnit.SECONDS).getStatusCode().value()),
                    HttpStatus.valueOf(fb.get(60, TimeUnit.SECONDS).getStatusCode().value()));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.NOT_FOUND);
        } finally {
            pool.shutdown();
        }
        assertThat(orderTotal(order)).isEqualTo(150_000);
    }

    @Test
    void reassignmentWinningTheLockTurnsTheOldVetsAddIntoForbidden() throws Exception {
        List<Callable<Object>> actions = List.of(() -> addLine(tVet, serviceBody(examService, 1)));

        List<Outcome> outcomes = whileOrderLocked(order, () -> {
            jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit);
            jdbc.update("UPDATE order_lines SET owner_account_id = ? WHERE order_id = ? AND owner_account_id = ?",
                    otherVet, order, vet);
        }, actions);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) outcomes.get(0).value();
        assertForbiddenWithRule(response);
        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }

    @Test
    void lineAddedBeforeReassignmentMovesToTheNewAssignee() {
        long mine = addServiceAsVet();

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", otherVet, visit);
            visitOrders.transferLineOwnership(visit, vet, otherVet);
        });

        assertForbiddenWithRule(deleteLine(tVet, mine));
        assertThat(deleteLine(tOtherVet, mine).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteLine(tOtherVet, autoLine).getStatusCode()).as("BR-TN-08: quyền xóa dòng tự sinh chuyển theo")
                .isEqualTo(HttpStatus.OK);
        assertThat(orderTotal(order)).isZero();
    }

    @Test
    void addingWhileTheOrderIsBeingPaidWaitsThenSeesTheFinalStatus() throws Exception {
        List<Callable<Object>> actions = List.of(() -> addLine(tReceptionist, goodsBody(goods, 1)));

        List<Outcome> outcomes = whileOrderLocked(order,
                () -> jdbc.update("UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), "
                        + "cancel_type = 'VISIT_CANCELLED' WHERE id = ?", order),
                actions);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) outcomes.get(0).value();
        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(sumOfLines(order)).isEqualTo(150_000);
    }
}
