package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * Nhà cung cấp — {@code /api/suppliers} (UC73; inventory-v1 #1–4; BR-KO-02; docs/adr/0042) qua HTTP trên Postgres 17
 * thật: quyền chỉ BRANCH_MANAGER, hợp đồng, bộ lọc, sửa một phần, ngừng hợp tác, xóa chỉ khi chưa có phiếu nào (kể cả
 * khi phiếu đang được chèn song song).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SupplierIT extends InventoryItSupport {

    private static final String PATH = "/api/suppliers";

    private long branch;
    private long managerId;
    private String manager;
    private String tag;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        managerId = staff("BRANCH_MANAGER", branch);
        manager = token(managerId);
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<Map<String, Object>> create(String name, String phone) {
        return call(HttpMethod.POST, PATH, manager, Map.of("name", name, "phone", phone));
    }

    private long createdId(ResponseEntity<Map<String, Object>> response) {
        return trackSupplier(number(data(response, HttpStatus.CREATED).get("supplierId")));
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void onlyBranchManagersManageSuppliers() {
        long supplier = createdId(create("NCC quyền " + tag, "0281234567"));
        for (String role : List.of("ADMIN", "SUPER_MANAGER", "RECEPTIONIST", "VET", "CARETAKER")) {
            String token = token(staff(role, branch));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, PATH, token, Map.of("name", "A", "phone", "0281234567")),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.PATCH, PATH + "/" + supplier, token, Map.of()), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
            assertError(call(HttpMethod.DELETE, PATH + "/" + supplier, token, null), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void staffWhoMustChangePasswordIsBlocked() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerId);

        assertRule(call(HttpMethod.GET, PATH, manager, null), "BR-TK-17");
    }

    @Test
    void anyBranchManagerSeesAndEditsTheSharedSuppliers() {
        long supplier = createdId(create("NCC chung " + tag, "0281234567"));
        String otherManager = token(staff("BRANCH_MANAGER", newBranch()));

        data(call(HttpMethod.PATCH, PATH + "/" + supplier, otherManager, Map.of("address", "Địa chỉ mới")),
                HttpStatus.OK);
        assertThat(list(call(HttpMethod.GET, PATH + "?q=" + tag, otherManager, null), HttpStatus.OK)).hasSize(1);
    }

    // ---------------------------------------------------------------- tạo, danh sách

    @Test
    void createMatchesTheContract() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, manager,
                Map.of("name", "  NCC mới " + tag + " ", "phone", "0281234567", "address", "  12 Nguyễn Huệ "));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        trackSupplier(number(data.get("supplierId")));
        assertThat(response.getBody()).containsEntry("message", "Đã thêm nhà cung cấp");
        assertThat(data).containsOnlyKeys("supplierId", "name", "phone", "address", "isActive")
                .containsEntry("name", "NCC mới " + tag).containsEntry("phone", "0281234567")
                .containsEntry("address", "12 Nguyễn Huệ").containsEntry("isActive", true);
    }

    @Test
    void createRejectsInvalidInput() {
        long before = jdbc.queryForObject("SELECT count(*) FROM suppliers", Long.class);
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("phone", "0281234567"),
                Map.<String, Object>of("name", "A"),
                Map.<String, Object>of("name", "  ", "phone", "0281234567"),
                Map.<String, Object>of("name", "A", "phone", "12345"),
                Map.<String, Object>of("name", "x".repeat(201), "phone", "0281234567"),
                Map.<String, Object>of("name", "A", "phone", "0281234567", "address", "x".repeat(301)))) {
            assertError(call(HttpMethod.POST, PATH, manager, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertError(callRaw(HttpMethod.POST, PATH, manager, "{bad"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers", Long.class)).isEqualTo(before);
    }

    @Test
    void listFiltersByActiveAndSearchesNameOrPhoneSortedByName() {
        long b = createdId(create("B nhà " + tag, "0281111111"));
        long a = createdId(create("A nhà " + tag, "0282222222"));
        long stopped = createdId(create("C nhà " + tag, "0283333333"));
        data(call(HttpMethod.PATCH, PATH + "/" + stopped, manager, Map.of("isActive", false)), HttpStatus.OK);

        assertThat(ids(list(call(HttpMethod.GET, PATH + "?q=" + tag, manager, null), HttpStatus.OK)))
                .containsExactly(a, b, stopped);
        assertThat(ids(list(call(HttpMethod.GET, PATH + "?q=" + tag + "&isActive=true", manager, null),
                HttpStatus.OK))).containsExactly(a, b);
        assertThat(ids(list(call(HttpMethod.GET, PATH + "?q=" + tag + "&isActive=false", manager, null),
                HttpStatus.OK))).containsExactly(stopped);
        assertThat(ids(list(call(HttpMethod.GET, PATH + "?q=0282222222", manager, null), HttpStatus.OK)))
                .contains(a).doesNotContain(b);
        assertThat(ids(list(call(HttpMethod.GET, PATH + "?q=" + ("A NHÀ " + tag).toUpperCase(), manager, null),
                HttpStatus.OK))).as("không phân biệt hoa thường").containsExactly(a);
        assertThat(list(call(HttpMethod.GET, PATH + "?q=%", manager, null), HttpStatus.OK))
                .as("% là ký tự thường, không phải wildcard").isEmpty();
    }

    // ---------------------------------------------------------------- sửa, ngừng hợp tác

    @Test
    void updateChangesOnlySentFieldsAndEmptyAddressClears() {
        long id = createdId(call(HttpMethod.POST, PATH, manager,
                Map.of("name", "NCC sửa " + tag, "phone", "0281234567", "address", "Địa chỉ cũ")));

        Map<String, Object> renamed = data(call(HttpMethod.PATCH, PATH + "/" + id, manager,
                Map.of("name", " Tên mới " + tag + " ")), HttpStatus.OK);
        assertThat(renamed).containsEntry("name", "Tên mới " + tag).containsEntry("phone", "0281234567")
                .containsEntry("address", "Địa chỉ cũ").containsEntry("isActive", true);

        Map<String, Object> cleared = data(call(HttpMethod.PATCH, PATH + "/" + id, manager,
                Map.of("address", "", "phone", "0912345678")), HttpStatus.OK);
        assertThat(cleared).containsEntry("address", null).containsEntry("phone", "0912345678");

        Map<String, Object> stopped = data(call(HttpMethod.PATCH, PATH + "/" + id, manager,
                Map.of("isActive", false)), HttpStatus.OK);
        assertThat(stopped).containsEntry("isActive", false);
        assertThat(data(call(HttpMethod.PATCH, PATH + "/" + id, manager, Map.of("isActive", true)), HttpStatus.OK))
                .containsEntry("isActive", true);
    }

    @Test
    void updateRejectsBadInputAndUnknownSupplier() {
        long id = createdId(create("NCC lỗi " + tag, "0281234567"));

        assertError(call(HttpMethod.PATCH, PATH + "/" + id, manager, Map.of("name", "  ")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/" + id, manager, Map.of("phone", "123")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/999999999", manager, Map.of("name", "A")), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?", String.class, id))
                .isEqualTo("NCC lỗi " + tag);
    }

    // ---------------------------------------------------------------- xóa

    @Test
    void deleteRemovesASupplierWithoutReceipts() {
        long id = createdId(create("NCC xóa " + tag, "0281234567"));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.DELETE, PATH + "/" + id, manager, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE id = ?", Long.class, id)).isZero();
        assertError(call(HttpMethod.DELETE, PATH + "/" + id, manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void anyReceiptStatusBlocksDeletion() {
        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED")) {
            long id = createdId(create("NCC " + status + " " + tag, "0281234567"));
            insertReceipt(id, status);

            assertRule(call(HttpMethod.DELETE, PATH + "/" + id, manager, null), "BR-KO-02");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE id = ?", Long.class, id))
                    .as(status).isOne();
        }
    }

    @Test
    void aReceiptBeingInsertedWhileTheDeleteWaitsIsSeenAndKeepsTheSupplier() throws Exception {
        long id = createdId(create("NCC đua " + tag, "0281234567"));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> inserter = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    insertReceipt(id, "DRAFT");
                    inserted.countDown();
                    try {
                        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> delete = CompletableFuture
                .supplyAsync(() -> call(HttpMethod.DELETE, PATH + "/" + id, manager, null));
        awaitBlockedOnSupplier();
        assertThat(delete).as("xóa phải chờ khóa FK của phiếu đang chèn").isNotDone();
        release.countDown();

        inserter.get(30, TimeUnit.SECONDS);
        assertRule(delete.get(30, TimeUnit.SECONDS), "BR-KO-02");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE id = ?", Long.class, id)).isOne();
    }

    // ---------------------------------------------------------------- nội bộ

    private void insertReceipt(long supplierId, String status) {
        jdbc.update("""
                INSERT INTO stock_receipts (code, branch_id, supplier_id, receipt_date, status, created_by)
                VALUES (?, ?, ?, current_date, ?, ?)
                """, "T" + UUID.randomUUID().toString().substring(0, 12), branch, supplierId, status, managerId);
    }

    private void awaitBlockedOnSupplier() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from suppliers%for update%'
                    """, Long.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("không thấy câu FOR UPDATE trên suppliers đang chờ khóa");
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> number(item.get("supplierId"))).toList();
    }
}
